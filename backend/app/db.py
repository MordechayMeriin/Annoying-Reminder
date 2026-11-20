"""
DynamoDB storage: one table, and the few generic operations the backup (sync.py) and the Lambda build on.

Single-table layout. Every item has a partition key `pk` and a sort key `sk` (both strings), plus up to
two global secondary indexes (GSI1 and GSI2, keys g1pk/g1sk and g2pk/g2sk, projecting every
attribute). Numeric ids are zero-padded in keys so text order is number order.

    item            pk                  sk                  GSI1                            GSI2
    task            task                <id>                task#<status> / <id>; done ones
                                                            by completed_at
    task's steps    children            <parent id>         (a number set of step ids)
    Today's 3       focus               <date>              (the ids in order)
    task event      ev#<task_id>        <time>#<rand>       ev#<kind> / <time>
    message         msg                 <id>                msg#<kind> / <id>
    message link    mtask#<task_id>     <message id>        (one per task a message is about)
    focus session   sess                <id>                sess#open / <id> (open only)    sesstask#<task_id> / <id>
    preference      pref                <id>
    setting         setting             <key>
    id counter      counter             <name>
    backup status   sync                status / alive_alert
    LLM calls       llm                 day#<date>          (the daily cap, llm_proxy.py)

The web app's items (push devices, daily job runs) may still be in the table; nothing reads them.

Times are stored as fixed-width UTC text (2026-11-16T08:30:00.000000Z), so they sort correctly, and
dates as YYYY-MM-DD. A missing attribute is None. Index keys are derived from the row on every write
(Entity.item), so they can't drift from the data.

These functions block (boto3). The boto3 client is thread-safe and created once.
"""

import threading
import time
from datetime import date, datetime, timezone
from decimal import Decimal
from typing import Any, Callable, Iterator

import boto3
from boto3.dynamodb.types import TypeDeserializer, TypeSerializer
from botocore.config import Config
from botocore.exceptions import ClientError

from . import config

_TIME_FMT = "%Y-%m-%dT%H:%M:%S.%fZ"

# The table (scripts/aws_lambda_setup.py creates it; the tests create it in moto). Provisioned capacity that
# adds up to DynamoDB's always-free 25 read + 25 write units per account and region.
CAPACITY = {"table": (13, 11), "GSI1": (8, 9), "GSI2": (4, 5)}


def table_spec(name: str) -> dict[str, Any]:
    """CreateTable arguments."""
    def throughput(part: str) -> dict[str, int]:
        r, w = CAPACITY[part]
        return {"ReadCapacityUnits": r, "WriteCapacityUnits": w}

    return {
        "TableName": name,
        "BillingMode": "PROVISIONED",
        "AttributeDefinitions": [{"AttributeName": a, "AttributeType": "S"}
                                 for a in ("pk", "sk", "g1pk", "g1sk", "g2pk", "g2sk")],
        "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}, {"AttributeName": "sk", "KeyType": "RANGE"}],
        "ProvisionedThroughput": throughput("table"),
        "GlobalSecondaryIndexes": [
            {"IndexName": f"GSI{n}",
             "KeySchema": [{"AttributeName": f"g{n}pk", "KeyType": "HASH"},
                           {"AttributeName": f"g{n}sk", "KeyType": "RANGE"}],
             "Projection": {"ProjectionType": "ALL"},
             "ProvisionedThroughput": throughput(f"GSI{n}")}
            for n in (1, 2)
        ],
    }

_client = None
_client_lock = threading.Lock()
_ser, _de = TypeSerializer(), TypeDeserializer()


def client():
    global _client
    if _client is None:
        with _client_lock:
            if _client is None:
                _client = boto3.client(
                    "dynamodb",
                    region_name=config.aws_region(),
                    # Standard retries back off on throttling, so a burst beyond the free capacity slows
                    # down instead of failing.
                    config=Config(retries={"max_attempts": 8, "mode": "standard"},
                                  connect_timeout=5, read_timeout=10, max_pool_connections=20),
                )
    return _client


def _table() -> str:
    return config.dynamodb_table()


# ---------------------------------------------------------------- values

def ts(dt: datetime) -> str:
    """A time as sortable UTC text."""
    if dt.tzinfo is None:
        raise ValueError("naive datetime")
    return dt.astimezone(timezone.utc).strftime(_TIME_FMT)


def parse_ts(text: str) -> datetime:
    return datetime.strptime(text, _TIME_FMT).replace(tzinfo=timezone.utc)


def pad(n: int) -> str:
    return f"{int(n):012d}"


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def _encode_value(kind: str, value: Any) -> Any:
    if kind == "dt":
        return ts(value)
    if kind == "date":
        return value.isoformat()
    if kind == "int":
        return int(value)
    if kind == "bool":
        return bool(value)
    if kind == "ints":
        return [int(v) for v in value]
    return value


def _decode_value(kind: str, value: Any) -> Any:
    if kind == "dt":
        return parse_ts(value)
    if kind == "date":
        return date.fromisoformat(value)
    if kind == "int":
        return int(value)
    if kind == "bool":
        return bool(value)
    if kind == "ints":
        return [int(v) for v in value]
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    return value


def to_attr(value: dict[str, Any]) -> dict[str, Any]:
    """Plain Python values → DynamoDB's typed format (None values are left out)."""
    return {k: _ser.serialize(v) for k, v in value.items() if v is not None}


def from_attr(item: dict[str, Any]) -> dict[str, Any]:
    return {k: _de.deserialize(v) for k, v in item.items()}


class Entity:
    """
    One kind of item: its fields (name → kind: str | int | bool | dt | date | ints | json-able),
    their defaults, and how a row maps to keys and index keys.
    """

    def __init__(self, fields: dict[str, str], keys: Callable[[dict[str, Any]], dict[str, str]],
                 defaults: dict[str, Any] | None = None):
        self.fields = fields
        self.keys = keys
        self.defaults = defaults or {}

    def row(self, raw: dict[str, Any]) -> dict[str, Any]:
        """A stored item (plain values) → the row the app works with: every field, None when missing."""
        out: dict[str, Any] = {}
        for name, kind in self.fields.items():
            value = raw.get(name)
            if value is None:
                default = self.defaults.get(name)
                out[name] = list(default) if isinstance(default, list) else default
            else:
                out[name] = _decode_value(kind, value)
        return out

    def item(self, row: dict[str, Any], version: int | None = None) -> dict[str, Any]:
        """A row → a typed DynamoDB item, with its keys and index keys."""
        plain = {name: _encode_value(kind, row[name]) for name, kind in self.fields.items()
                 if row.get(name) is not None}
        plain.update(self.keys(row))
        if version is not None:
            plain["v"] = version
        return to_attr(plain)


# ---------------------------------------------------------------- operations

def get_raw(pk: str, sk: str) -> dict[str, Any] | None:
    r = client().get_item(TableName=_table(), Key=to_attr({"pk": pk, "sk": sk}), ConsistentRead=True)
    return from_attr(r["Item"]) if "Item" in r else None


def put_raw(values: dict[str, Any]) -> None:
    client().put_item(TableName=_table(), Item=to_attr(values))


def query(pk: str, *, index: str | None = None, sk_op: str | None = None, sk: Any = None,
          forward: bool = True, limit: int | None = None, page_size: int | None = None) -> Iterator[dict[str, Any]]:
    """
    Items in one partition, in sort-key order (forward=False: newest / largest first), fetched page by
    page as the caller iterates. sk_op: "=", "<", "<=", ">", ">=", "begins_with" or "between" (sk is then
    a pair). On an index, pk / sk name that index's keys. limit: stop after this many items.
    """
    pk_name, sk_name = ("pk", "sk") if index is None else (f"g{index[-1]}pk", f"g{index[-1]}sk")
    cond = "#pk = :pk"
    values: dict[str, Any] = {":pk": pk}
    names = {"#pk": pk_name}
    if sk_op:
        names["#sk"] = sk_name
        if sk_op == "between":
            cond += " AND #sk BETWEEN :a AND :b"
            values.update({":a": sk[0], ":b": sk[1]})
        elif sk_op == "begins_with":
            cond += " AND begins_with(#sk, :a)"
            values[":a"] = sk
        else:
            cond += f" AND #sk {sk_op} :a"
            values[":a"] = sk
    args: dict[str, Any] = {"TableName": _table(), "KeyConditionExpression": cond,
                            "ExpressionAttributeNames": names, "ExpressionAttributeValues": to_attr(values),
                            "ScanIndexForward": forward}
    if index is None:
        args["ConsistentRead"] = True
    else:
        args["IndexName"] = index
    if limit is not None or page_size is not None:
        args["Limit"] = page_size or min(limit, 100)
    n = 0
    while True:
        r = client().query(**args)
        for it in r.get("Items", []):
            yield from_attr(it)
            n += 1
            if limit is not None and n >= limit:
                return
        if "LastEvaluatedKey" not in r:
            return
        args["ExclusiveStartKey"] = r["LastEvaluatedKey"]


def set_counter_at_least(name: str, value: int) -> None:
    """For imports: make sure the counter won't hand out ids up to `value` again."""
    try:
        client().update_item(
            TableName=_table(), Key=to_attr({"pk": "counter", "sk": name}),
            UpdateExpression="SET n = :n", ConditionExpression="attribute_not_exists(n) OR n < :n",
            ExpressionAttributeValues=to_attr({":n": int(value)}),
        )
    except ClientError as e:
        if e.response["Error"]["Code"] != "ConditionalCheckFailedException":
            raise


def write_batch(puts: list[dict[str, Any]] = (), deletes: list[tuple[str, str]] = ()) -> None:
    """Many plain puts (typed items) and deletes, 25 per request, retrying what DynamoDB sends back."""
    requests = ([{"PutRequest": {"Item": it}} for it in puts]
                + [{"DeleteRequest": {"Key": to_attr({"pk": pk, "sk": sk})}} for pk, sk in deletes])
    for i in range(0, len(requests), 25):
        pending = {_table(): requests[i:i + 25]}
        for attempt in range(40):
            r = client().batch_write_item(RequestItems=pending)
            pending = r.get("UnprocessedItems") or {}
            if not pending:
                break
            time.sleep(min(0.2 * 2 ** attempt, 5))   # over the provisioned capacity: wait for it to refill
        else:
            raise RuntimeError("DynamoDB kept refusing part of a batch write")
