"""
The phone's backup (Android Phase B): the Android app keeps your data in its own database and is the only
one that changes it. This module is the other end: it hands the phone everything once (restore, which is
also the handover), and stores what the phone changed since (upload).

Rows travel in the stored format (db.py): times as fixed-width UTC text (2026-11-16T08:30:00.000000Z),
dates as YYYY-MM-DD, ids as numbers, a message's chips as JSON text. So a row restored and uploaded again
comes back byte for byte.

The handover: the first restore with handover=true marks the data as the phone's (`sync/status`). The daily
"is the phone still alive?" check (alive.py) starts counting from then.

Restore comes in pages (`paged: true`, then the `next` cursor of each answer), each well under the 6 MB a Lambda
function URL can return, so it keeps working however long the chat grows. Uploads are small (the phone sends at
most a few hundred rows per request) and stop with db.Busy before the Lambda's timeout when DynamoDB throttles.
"""

import base64
import json
import logging
import time
from typing import Any

from . import db
from .db import Entity, pad, ts

log = logging.getLogger("sync")


# ---------------------------------------------------------------- what the phone keeps, as stored items
# The fields of each kind of row and its keys (see the layout in db.py). The phone's Room tables have the same
# fields (android/app/src/main/java/com/prominder/myapp/data/Rows.kt); test_sync.py checks them against the
# Android tests' sample.

TASK = Entity(
    {"id": "int", "parent_id": "int", "title": "str", "description": "str", "kind": "str", "priority": "int",
     "status": "str", "due_at": "dt", "remind_at": "dt", "remind_requested": "bool", "recurrence": "str",
     "nag_count": "int", "escalated": "bool", "blocked_reason": "str", "blocked_until": "dt",
     "last_notified_at": "dt", "created_at": "dt", "completed_at": "dt", "next_action": "str",
     "estimate_min": "int", "focus_date": "date", "focus_rank": "int", "resume_note": "str",
     "parked_until": "dt", "stale_offered_at": "dt", "avoid_asked_at": "dt", "avoid_asks": "int",
     "avoid_reset_at": "dt"},
    lambda t: {"pk": "task", "sk": pad(t["id"])},
    defaults={"kind": "task", "priority": 1, "status": "open", "remind_requested": False, "nag_count": 0,
              "escalated": False, "avoid_asks": 0},
)


def _event_sk(task_id: int, created_at: str, rid: str) -> str:
    return f"{pad(task_id)}#{created_at}#{rid}"


# Every event in one partition, by task and time: restore reads them with one query, whatever their kind.
EVENT = Entity(
    {"task_id": "int", "kind": "str", "note": "str", "avoid_reason": "str", "created_at": "dt", "rid": "str"},
    lambda e: {"pk": "event", "sk": _event_sk(e["task_id"], ts(e["created_at"]), e["rid"])},
)

MESSAGE = Entity(
    {"id": "int", "role": "str", "content": "str", "kind": "str", "task_id": "int", "task_ids": "ints",
     "chips": "str", "model": "str", "created_at": "dt"},
    lambda m: {"pk": "msg", "sk": pad(m["id"])},
    defaults={"kind": "chat", "task_ids": []},
)

SESSION = Entity(
    {"id": "int", "task_id": "int", "started_at": "dt", "planned_min": "int", "ended_at": "dt", "outcome": "str",
     "end_sent_at": "dt", "note_closed_at": "dt"},
    lambda x: {"pk": "sess", "sk": pad(x["id"])},
)

PREFERENCE = Entity({"id": "int", "text": "str", "created_at": "dt"}, lambda p: {"pk": "pref", "sk": pad(p["id"])})

# The tables the phone keeps, by their name on the wire.
ENTITIES: dict[str, Entity] = {"tasks": TASK, "events": EVENT, "messages": MESSAGE, "sessions": SESSION,
                               "preferences": PREFERENCE}
TABLES = (*ENTITIES, "settings", "focus")
# Ids handed out by a counter, so the phone can continue after the highest one.
COUNTERS = {"tasks": "task", "messages": "message", "sessions": "session", "preferences": "preference"}
MAX_ROWS = 2000        # per upload request; the phone sends larger changes in several requests
# Each table's partition, in the order restore reads them.
PARTITIONS = (("tasks", "task"), ("events", "event"), ("messages", "msg"), ("sessions", "sess"),
              ("preferences", "pref"), ("settings", "setting"), ("focus", "focus"))
PAGE_BYTES = 1_500_000     # a restore page's rows, as JSON: far below the function URL's 6 MB, even without gzip
PAGE_SECONDS = 20          # and the time one page may take to read
WRITE_SECONDS = 40         # an upload's writes give up (db.Busy) after this, before the function's 60 s timeout


class SyncError(ValueError):
    """A row the phone sent doesn't fit the stored format. The message says which and why."""


# ---------------------------------------------------------------- status and the handover

_STATUS_KEY = ("sync", "status")


def status() -> dict[str, Any]:
    raw = db.get_raw(*_STATUS_KEY) or {}
    return {"handedOverAt": raw.get("handover_at"), "lastUploadAt": raw.get("last_upload_at"),
            "lastRestoreAt": raw.get("last_restore_at"), "device": raw.get("device")}


def handed_over() -> bool:
    return bool((db.get_raw(*_STATUS_KEY) or {}).get("handover_at"))


def _touch(**fields: str) -> None:
    """Set fields on the status item (creating it), keeping the others."""
    names = {f"#{k}": k for k in fields}
    values = {f":{k}": v for k, v in fields.items()}
    db.client().update_item(
        TableName=db._table(), Key=db.to_attr({"pk": _STATUS_KEY[0], "sk": _STATUS_KEY[1]}),
        UpdateExpression="SET " + ", ".join(f"#{k} = :{k}" for k in fields),
        ExpressionAttributeNames=names, ExpressionAttributeValues=db.to_attr(values))


# ---------------------------------------------------------------- restore

def _plain(entity: Entity, raw: dict[str, Any]) -> dict[str, Any]:
    """A stored item → its row in the stored format (what the phone keeps)."""
    row = entity.row(raw)
    return {name: (None if row[name] is None else db._encode_value(kind, row[name]))
            for name, kind in entity.fields.items()}


def _restored_row(table: str, raw: dict[str, Any]) -> dict[str, Any]:
    if table == "settings":
        return {"key": raw["sk"], "value": raw.get("value"), "updated_at": raw.get("updated_at")}
    if table == "focus":
        return {"date": raw["sk"], "ids": [int(i) for i in raw.get("ids", [])]}
    return _plain(ENTITIES[table], raw)


def _encode_cursor(part: int, after: dict[str, Any] | None) -> str:
    state = {"p": part, "sk": after["sk"]["S"] if after else None}
    return base64.urlsafe_b64encode(json.dumps(state).encode()).decode()


def _decode_cursor(cursor: str | None) -> tuple[int, dict[str, Any] | None]:
    if not cursor:
        return 0, None
    try:
        state = json.loads(base64.urlsafe_b64decode(cursor.encode()))
        part, sk = int(state["p"]), state.get("sk")
        if not 0 <= part <= len(PARTITIONS) or not (sk is None or isinstance(sk, str)):
            raise ValueError
    except (ValueError, TypeError, KeyError, json.JSONDecodeError):
        raise SyncError("restore: the cursor isn't one this server gave out") from None
    after = None if sk is None or part == len(PARTITIONS) else db.to_attr({"pk": PARTITIONS[part][1], "sk": sk})
    return part, after


def export_page(cursor: str | None = None, *, max_bytes: int | None = None,
                max_seconds: float | None = None) -> tuple[dict[str, list[dict[str, Any]]], str | None]:
    """
    The rows the phone keeps, from `cursor` on (None: from the start), up to about max_bytes of JSON or max_seconds
    of reading (None: no limit). Returns them by table, and the cursor to continue from (None: that was all).
    Read with Query only (the function's role may not Scan, see scripts/aws_lambda_setup.py).
    """
    tables: dict[str, list[dict[str, Any]]] = {name: [] for name in TABLES}
    part, after = _decode_cursor(cursor)
    size, started = 0, time.monotonic()
    while part < len(PARTITIONS):
        table, pk = PARTITIONS[part]
        items, after = db.query_page(pk, after=after)
        for raw in items:
            row = _restored_row(table, raw)
            tables[table].append(row)
            size += len(json.dumps(row, ensure_ascii=False))
        if after is None:
            part += 1
        if part < len(PARTITIONS) and (
                (max_bytes is not None and size >= max_bytes)
                or (max_seconds is not None and time.monotonic() - started >= max_seconds)):
            return tables, _encode_cursor(part, after)
    return tables, None


def counters() -> dict[str, int]:
    return {raw["sk"]: int(raw.get("n", 0)) for raw in db.query("counter")}


def export_all() -> dict[str, Any]:
    """Every row the phone keeps, and the id counters, in one piece (scripts/metrics.py; app versions before paging)."""
    tables, _ = export_page()
    found = counters()
    for table, counter in COUNTERS.items():   # never behind the data (an import that skipped a counter)
        found[counter] = max([found.get(counter, 0), *(row["id"] for row in tables[table])])
    return {"tables": tables, "counters": found}


def restore(*, handover: bool, device: str, paged: bool = False, cursor: str | None = None) -> dict[str, Any]:
    """
    Everything, for an empty phone. handover=True also makes the phone the owner of the data.

    paged=True: one page, with `next` (the cursor for the following page, None on the last one). The counters come
    with the last page; the phone hands out ids after both them and its highest id, so they're never behind the data.
    Without paged (app versions before paging): everything in one answer.
    """
    if not migrate(deadline=time.monotonic() + WRITE_SECONDS):
        raise db.Busy("The backup is being reorganized. Try again in a minute.")
    now = db.ts(db.now_utc())
    if cursor is None:   # the first (or only) page: record the restore once
        fields = {"last_restore_at": now, "device": device}
        if handover and not handed_over():
            fields["handover_at"] = now
            log.warning("Handover: the phone (%s) owns the data from now on", device)
        _touch(**fields)
    if not paged:
        return {**export_all(), "status": status(), "serverTime": now}
    tables, next_cursor = export_page(cursor, max_bytes=PAGE_BYTES, max_seconds=PAGE_SECONDS)
    return {"tables": tables, "counters": counters() if next_cursor is None else {}, "next": next_cursor,
            "status": status(), "serverTime": now}


# ---------------------------------------------------------------- upload

def _decode(entity: Entity, table: str, row: dict[str, Any]) -> dict[str, Any]:
    """A row from the phone → the row the app works with, checking every value."""
    if not isinstance(row, dict):
        raise SyncError(f"{table}: a row must be an object")
    unknown = set(row) - set(entity.fields)
    if unknown:
        raise SyncError(f"{table}: unknown fields {sorted(unknown)}")
    out: dict[str, Any] = {}
    for name, kind in entity.fields.items():
        value = row.get(name)
        try:
            out[name] = None if value is None else db._decode_value(kind, value)
        except (TypeError, ValueError) as e:
            raise SyncError(f"{table}: {name}={value!r} isn't a valid {kind} ({e})") from None
    return out


def _require(table: str, row: dict[str, Any], *names: str) -> None:
    missing = [n for n in names if row.get(n) is None]
    if missing:
        raise SyncError(f"{table}: missing {missing}")


def build_upload(tables: dict[str, list[dict[str, Any]]],
                 deletes: dict[str, list[Any]]) -> tuple[list[dict[str, Any]], list[tuple[str, str]], dict[str, int]]:
    """
    Checks everything first (nothing is written if one row is wrong). Returns the typed items to put, the keys to
    delete, and the counters to raise.
    """
    unknown = (set(tables) | set(deletes)) - set(TABLES)
    if unknown:
        raise SyncError(f"Unknown tables {sorted(unknown)}")
    if sum(len(rows) for rows in tables.values()) + sum(len(keys) for keys in deletes.values()) > MAX_ROWS:
        raise SyncError(f"Too many rows in one upload (at most {MAX_ROWS})")

    puts: list[dict[str, Any]] = []
    removes: list[tuple[str, str]] = []
    counters: dict[str, int] = {}

    for table, entity in ENTITIES.items():
        for raw in tables.get(table, []):
            row = _decode(entity, table, raw)
            if table == "tasks":
                _require(table, row, "id", "title", "status", "created_at")
            elif table == "events":
                _require(table, row, "task_id", "kind", "created_at", "rid")
            elif table == "messages":
                _require(table, row, "id", "role", "content", "created_at")
                row["task_ids"] = list(dict.fromkeys(row["task_ids"] or []))
                row["task_id"] = row["task_ids"][0] if row["task_ids"] else row["task_id"]
            elif table == "sessions":
                _require(table, row, "id", "task_id", "started_at", "planned_min")
            elif table == "preferences":
                _require(table, row, "id", "text", "created_at")
            if table in COUNTERS:
                counters[COUNTERS[table]] = max(counters.get(COUNTERS[table], 0), row["id"])
            puts.append(entity.item(row, version=1))

    for raw in tables.get("settings", []):
        if not isinstance(raw, dict) or not isinstance(raw.get("key"), str) or not isinstance(raw.get("value"), str):
            raise SyncError("settings: each row needs a text key and value")
        updated = raw.get("updated_at") or db.ts(db.now_utc())
        try:
            db.parse_ts(updated)
        except (TypeError, ValueError):
            raise SyncError(f"settings: updated_at={updated!r} isn't a time") from None
        puts.append(db.to_attr({"pk": "setting", "sk": raw["key"][:100], "value": raw["value"][:1000],
                                "updated_at": updated}))
    for raw in tables.get("focus", []):
        try:
            day = db._decode_value("date", raw["date"]).isoformat()
            ids = [int(i) for i in raw.get("ids") or []]
        except (KeyError, TypeError, ValueError):
            raise SyncError(f"focus: {raw!r} needs a date and a list of ids") from None
        puts.append(db.to_attr({"pk": "focus", "sk": day, "ids": ids}))

    keys = {"tasks": lambda k: [("task", pad(k))], "messages": lambda k: [("msg", pad(k))],
            "sessions": lambda k: [("sess", pad(k))], "preferences": lambda k: [("pref", pad(k))],
            "settings": lambda k: [("setting", str(k))], "focus": lambda k: [("focus", str(k))],
            # an event's key: [task_id, created_at, rid]; also where layout 1 kept it, in case it wasn't moved yet
            "events": lambda k: [("event", _event_sk(int(k[0]), str(k[1]), str(k[2]))),
                                 (f"ev#{int(k[0])}", f"{k[1]}#{k[2]}")]}
    for table, items in deletes.items():
        for k in items:
            try:
                removes += keys[table](k)
            except (TypeError, ValueError, IndexError, KeyError):
                raise SyncError(f"{table}: can't delete {k!r}") from None
    # A row that's written and deleted in the same upload exists on the phone (it deletes its own row when it
    # writes a tombstone), so the write wins. Sending both would also make DynamoDB refuse the whole batch.
    written = {(it["pk"]["S"], it["sk"]["S"]) for it in puts}
    removes = [k for k in dict.fromkeys(removes) if k not in written]
    return puts, removes, counters


def upload(tables: dict[str, list[dict[str, Any]]], deletes: dict[str, list[Any]], *, device: str) -> dict[str, Any]:
    """
    Store what the phone changed. The phone's copy always wins: rows are written as they are. Raises db.Busy when
    DynamoDB throttles past WRITE_SECONDS; what was written stays, and the phone sends the same request again later.
    """
    puts, removes, counters = build_upload(tables, deletes)
    db.write_batch(puts, removes, deadline=time.monotonic() + WRITE_SECONDS)
    for name, value in counters.items():
        db.set_counter_at_least(name, value)
    now = db.ts(db.now_utc())
    _touch(last_upload_at=now, device=device)
    return {"ok": True, "written": len(puts), "deleted": len(removes), "serverTime": now}


# ---------------------------------------------------------------- layout 2 (one-off migration)

_LAYOUT_KEY = ("sync", "layout")
LAYOUT = 2


def migrated() -> bool:
    return int((db.get_raw(*_LAYOUT_KEY) or {}).get("v", 0)) >= LAYOUT


def migrate(deadline: float | None = None) -> bool:
    """
    Moves a table from layout 1 to layout 2 (db.py): each task's events from their own partition (ev#<task_id>) into
    the one `event` partition, and deletes the items nothing reads (a task's steps, message ↔ task links). Safe to
    stop at any point and run again: progress is saved after each task, and an event is written at its new key
    before its old one is deleted. Returns True once the table is on layout 2; False when the deadline
    (time.monotonic()) came first. Tasks are never deleted, so going through them finds every old event.
    """
    state = db.get_raw(*_LAYOUT_KEY) or {}
    if int(state.get("v", 0)) >= LAYOUT:
        return True

    def save(**fields: Any) -> None:
        state.update(fields)
        db.put_raw({**state, "pk": _LAYOUT_KEY[0], "sk": _LAYOUT_KEY[1]})

    def out_of_time() -> bool:
        return deadline is not None and time.monotonic() >= deadline

    if not state.get("children_done"):
        db.write_batch(deletes=[(it["pk"], it["sk"]) for it in db.query("children")], deadline=deadline)
        save(children_done=True)
    after = state.get("after")
    for task in (db.query("task", sk_op=">", sk=after) if after else db.query("task")):
        if out_of_time():
            return False
        task_id = int(task["sk"])
        old = list(db.query(f"ev#{task_id}"))
        db.write_batch([EVENT.item(EVENT.row(it), version=1) for it in old], deadline=deadline)
        links = list(db.query(f"mtask#{task_id}"))
        db.write_batch(deletes=[(it["pk"], it["sk"]) for it in old + links], deadline=deadline)
        save(after=task["sk"])
    save(v=LAYOUT, migrated_at=db.ts(db.now_utc()))
    log.warning("The backup is on layout %d now", LAYOUT)
    return True
