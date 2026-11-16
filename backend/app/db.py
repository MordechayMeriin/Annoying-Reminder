"""
DynamoDB storage: one table, and the few generic operations the data modules build on.

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
    push device     push                <endpoint>
    daily job run   job                 <job>#<date>
    id counter      counter             <name>

Times are stored as fixed-width UTC text (2026-11-16T08:30:00.000000Z), so they sort correctly, and
dates as YYYY-MM-DD. A missing attribute is None. Index keys are derived from the row on every write
(Entity.item), so they can't drift from the data.

Writes that depend on the current state use mutate(): read (strongly consistent), change in Python,
write back only if nobody else wrote in between (a version number), retry otherwise. That gives the
same guarantees as the old single-statement SQL updates (an overlapping tick can't claim a task or a
session end twice). Index queries are eventually consistent (normally milliseconds behind).

These functions block (boto3). Async code calls them through asyncio.to_thread, as before. The boto3
client is thread-safe and created once.
"""

import secrets
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

# The table (scripts/aws_setup.py creates it; the tests create it in moto). Provisioned capacity that
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


class ConflictError(RuntimeError):
    """mutate() kept losing the race with other writers (shouldn't happen with one user)."""


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


def close_pool() -> None:
    """Nothing to close (kept so shutdown code doesn't need to know the storage)."""


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


def get(entity: Entity, pk: str, sk: str) -> dict[str, Any] | None:
    raw = get_raw(pk, sk)
    return entity.row(raw) if raw else None


def get_many(entity: Entity, keys: list[tuple[str, str]]) -> list[dict[str, Any]]:
    """Several items by key (any order; missing ones are left out)."""
    out: list[dict[str, Any]] = []
    unique = list(dict.fromkeys(keys))
    for i in range(0, len(unique), 100):
        request = {_table(): {"Keys": [to_attr({"pk": pk, "sk": sk}) for pk, sk in unique[i:i + 100]],
                              "ConsistentRead": True}}
        for _ in range(10):
            r = client().batch_get_item(RequestItems=request)
            out.extend(entity.row(from_attr(it)) for it in r["Responses"].get(_table(), []))
            request = r.get("UnprocessedKeys") or {}
            if not request:
                break
    return out


def put(entity: Entity, row: dict[str, Any], *, new: bool = False) -> bool:
    """Write a whole item. new=True: only if no item has these keys yet (False when one exists)."""
    args: dict[str, Any] = {"TableName": _table(), "Item": entity.item(row, version=1)}
    if new:
        args["ConditionExpression"] = "attribute_not_exists(pk)"
    try:
        client().put_item(**args)
    except ClientError as e:
        if new and e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            return False
        raise
    return True


def put_raw(values: dict[str, Any]) -> None:
    client().put_item(TableName=_table(), Item=to_attr(values))


def delete(pk: str, sk: str) -> bool:
    """True when there was an item to delete."""
    r = client().delete_item(TableName=_table(), Key=to_attr({"pk": pk, "sk": sk}), ReturnValues="ALL_OLD")
    return "Attributes" in r


def mutate(entity: Entity, pk: str, sk: str,
           change: Callable[[dict[str, Any]], dict[str, Any] | None]) -> dict[str, Any] | None:
    """
    Read the row, let `change` return the new row (or None to leave it as it is), and write it back if
    nobody wrote in between. Returns the new row; None when the item doesn't exist or `change` said no.
    `change` may run more than once (after a lost race), so it must not have side effects.
    """
    for _ in range(8):
        raw = get_raw(pk, sk)
        if raw is None:
            return None
        row = entity.row(raw)
        new = change(dict(row))
        if new is None:
            return None
        version = int(raw.get("v", 0))
        try:
            client().put_item(
                TableName=_table(), Item=entity.item(new, version=version + 1),
                ConditionExpression="attribute_exists(pk) AND (attribute_not_exists(v) OR v = :v)",
                ExpressionAttributeValues=to_attr({":v": version}),
            )
            return new
        except ClientError as e:
            if e.response["Error"]["Code"] != "ConditionalCheckFailedException":
                raise
    raise ConflictError(f"Too many concurrent writes to {pk}/{sk}")


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


def query_rows(entity: Entity, pk: str, **kw: Any) -> list[dict[str, Any]]:
    return [entity.row(it) for it in query(pk, **kw)]


def next_id(name: str) -> int:
    """The next number in a counter (like a bigserial column)."""
    r = client().update_item(
        TableName=_table(), Key=to_attr({"pk": "counter", "sk": name}),
        UpdateExpression="ADD n :one", ExpressionAttributeValues=to_attr({":one": 1}),
        ReturnValues="UPDATED_NEW",
    )
    return int(from_attr(r["Attributes"])["n"])


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


def transact(puts: list[dict[str, Any]] = (), deletes: list[tuple[str, str]] = ()) -> None:
    """All or nothing (up to 100 items)."""
    items = ([{"Put": {"TableName": _table(), "Item": it}} for it in puts]
             + [{"Delete": {"TableName": _table(), "Key": to_attr({"pk": pk, "sk": sk})}} for pk, sk in deletes])
    if items:
        client().transact_write_items(TransactItems=items)


def rand() -> str:
    """A short random suffix that keeps sort keys unique when two items share a time."""
    return secrets.token_hex(4)
