"""
The phone's backup (Android Phase B): the Android app keeps your data in its own database and is the only
one that changes it. This module is the other end: it hands the phone everything once (restore, which is
also the handover), and stores what the phone changed since (upload), in the same items the web app used.

Rows travel in the stored format (db.py): times as fixed-width UTC text (2026-11-16T08:30:00.000000Z),
dates as YYYY-MM-DD, ids as numbers, a message's chips as JSON text. So a row restored and uploaded again
comes back byte for byte, and the old server could still read the data in an emergency.

The handover: the first restore with handover=true marks the data as the phone's (`sync/status`). From then
on the web app's endpoints answer 409 and /tick does nothing, so the old app can't remind you twice or
write a second, drifting copy. To give the data back to the web app (only if the phone app has to be
abandoned): delete the item pk=sync, sk=status in the DynamoDB console, and turn the cron job on again.
"""

import logging
import threading
import time
from collections import defaultdict
from typing import Any

from . import db
from .db import Entity, pad
from .preferences import PREFERENCE
from .sessions import SESSION
from .tasks import EVENT, EVENT_KINDS, TASK
from .thread import MESSAGE

log = logging.getLogger("sync")

# The tables the phone keeps, by their name on the wire.
ENTITIES: dict[str, Entity] = {"tasks": TASK, "events": EVENT, "messages": MESSAGE, "sessions": SESSION,
                               "preferences": PREFERENCE}
TABLES = (*ENTITIES, "settings", "focus")
# Ids handed out by a counter (db.next_id), so the phone can continue after the highest one.
COUNTERS = {"tasks": "task", "messages": "message", "sessions": "session", "preferences": "preference"}
MAX_ROWS = 2000        # per upload request; the phone sends larger changes in several requests


class SyncError(ValueError):
    """A row the phone sent doesn't fit the stored format. The message says which and why."""


# ---------------------------------------------------------------- status and the handover

_STATUS_KEY = ("sync", "status")
_STATUS_TTL_S = 30
_handover_cache: tuple[float, bool] = (0.0, False)
_cache_lock = threading.Lock()


def status() -> dict[str, Any]:
    raw = db.get_raw(*_STATUS_KEY) or {}
    return {"handedOverAt": raw.get("handover_at"), "lastUploadAt": raw.get("last_upload_at"),
            "lastRestoreAt": raw.get("last_restore_at"), "device": raw.get("device")}


def handed_over() -> bool:
    """Whether the phone owns the data (cached for a few seconds: every web request asks)."""
    global _handover_cache
    checked_at, value = _handover_cache
    if time.monotonic() - checked_at < _STATUS_TTL_S:
        return value
    try:
        value = bool((db.get_raw(*_STATUS_KEY) or {}).get("handover_at"))
    except Exception:
        log.exception("Could not read the sync status; assuming the web app is still in use")
        return False
    with _cache_lock:
        _handover_cache = (time.monotonic(), value)
    return value


def _forget_cache() -> None:
    global _handover_cache
    with _cache_lock:
        _handover_cache = (0.0, False)


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


def export_all() -> dict[str, Any]:
    """
    Every row the phone keeps, and the id counters. Read with Query only (the backend's AWS user may not Scan,
    see scripts/aws_setup.py): one partition per kind of item, and the events through their kind index.
    """
    tables: dict[str, list[dict[str, Any]]] = {name: [] for name in TABLES}
    for pk, table in (("task", "tasks"), ("msg", "messages"), ("sess", "sessions"), ("pref", "preferences")):
        tables[table] = [_plain(ENTITIES[table], raw) for raw in db.query(pk)]
    for kind in sorted(EVENT_KINDS):
        tables["events"] += [_plain(EVENT, raw) for raw in db.query(f"ev#{kind}", index="GSI1")]
    tables["settings"] = [{"key": raw["sk"], "value": raw.get("value"), "updated_at": raw.get("updated_at")}
                          for raw in db.query("setting")]
    tables["focus"] = [{"date": raw["sk"], "ids": [int(i) for i in raw.get("ids", [])]} for raw in db.query("focus")]
    counters = {raw["sk"]: int(raw.get("n", 0)) for raw in db.query("counter")}
    for name, rows in tables.items():
        rows.sort(key=lambda row: [str(row.get(k)) for k in ("date", "key", "task_id", "created_at", "rid")]
                  if "id" not in row else [row["id"]])
    for table, counter in COUNTERS.items():   # never behind the data (an import that skipped a counter)
        counters[counter] = max([counters.get(counter, 0), *(row["id"] for row in tables[table])])
    return {"tables": tables, "counters": counters}


def restore(*, handover: bool, device: str) -> dict[str, Any]:
    """Everything, for an empty phone. handover=True also makes the phone the owner of the data."""
    data = export_all()
    now = db.ts(db.now_utc())
    fields = {"last_restore_at": now, "device": device}
    if handover and not handed_over_now():
        fields["handover_at"] = now
        log.warning("Handover: the phone (%s) owns the data from now on; the web app is switched off", device)
    _touch(**fields)
    _forget_cache()
    return {**data, "status": status(), "serverTime": now}


def handed_over_now() -> bool:
    """Uncached (for the handover itself)."""
    return bool((db.get_raw(*_STATUS_KEY) or {}).get("handover_at"))


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
                 deletes: dict[str, list[Any]]) -> tuple[list[dict[str, Any]], list[tuple[str, str]],
                                                         dict[int, set[int]], dict[str, int]]:
    """
    Checks everything first (nothing is written if one row is wrong). Returns the typed items to put, the keys
    to delete, the steps to add to each parent's list, and the counters to raise.
    """
    unknown = (set(tables) | set(deletes)) - set(TABLES)
    if unknown:
        raise SyncError(f"Unknown tables {sorted(unknown)}")
    if sum(len(rows) for rows in tables.values()) + sum(len(keys) for keys in deletes.values()) > MAX_ROWS:
        raise SyncError(f"Too many rows in one upload (at most {MAX_ROWS})")

    puts: list[dict[str, Any]] = []
    removes: list[tuple[str, str]] = []
    children: dict[int, set[int]] = defaultdict(set)
    counters: dict[str, int] = {}

    for table, entity in ENTITIES.items():
        for raw in tables.get(table, []):
            row = _decode(entity, table, raw)
            if table == "tasks":
                _require(table, row, "id", "title", "status", "created_at")
                if row["parent_id"] is not None:
                    children[row["parent_id"]].add(row["id"])
            elif table == "events":
                _require(table, row, "task_id", "kind", "created_at", "rid")
            elif table == "messages":
                _require(table, row, "id", "role", "content", "created_at")
                row["task_ids"] = list(dict.fromkeys(row["task_ids"] or []))
                row["task_id"] = row["task_ids"][0] if row["task_ids"] else row["task_id"]
                puts += [db.to_attr({"pk": f"mtask#{t}", "sk": pad(row["id"])}) for t in row["task_ids"]]
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

    keys = {"tasks": lambda k: ("task", pad(k)), "messages": lambda k: ("msg", pad(k)),
            "sessions": lambda k: ("sess", pad(k)), "preferences": lambda k: ("pref", pad(k)),
            "settings": lambda k: ("setting", str(k)), "focus": lambda k: ("focus", str(k)),
            # an event's key: [task_id, created_at, rid]
            "events": lambda k: (f"ev#{int(k[0])}", f"{k[1]}#{k[2]}")}
    for table, items in deletes.items():
        for k in items:
            try:
                removes.append(keys[table](k))
            except (TypeError, ValueError, IndexError, KeyError):
                raise SyncError(f"{table}: can't delete {k!r}") from None
    return puts, removes, children, counters


def upload(tables: dict[str, list[dict[str, Any]]], deletes: dict[str, list[Any]], *, device: str) -> dict[str, Any]:
    """Store what the phone changed. The phone's copy always wins: rows are written as they are."""
    puts, removes, children, counters = build_upload(tables, deletes)
    db.write_batch(puts, removes)
    for parent, ids in children.items():
        db.client().update_item(
            TableName=db._table(), Key=db.to_attr({"pk": "children", "sk": pad(parent)}),
            UpdateExpression="ADD ids :ids", ExpressionAttributeValues={":ids": {"NS": [str(i) for i in sorted(ids)]}})
    for name, value in counters.items():
        db.set_counter_at_least(name, value)
    now = db.ts(db.now_utc())
    _touch(last_upload_at=now, device=device)
    return {"ok": True, "written": len(puts), "deleted": len(removes), "serverTime": now}
