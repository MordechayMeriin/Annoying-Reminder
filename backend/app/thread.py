"""The single chat thread: messages, their links to tasks, and the counts the guardrails need.

Messages live in one partition ordered by id (ids are handed out in time order), so "the latest N" and
"everything since X" read from the newest end and never touch old history. Each link from a message to
a task is also a small item (mtask#<task id> / <message id>), so "messages about this task" doesn't
have to search the whole thread. Per-kind lookups use the kind index.
"""

import json
from datetime import datetime
from typing import Any, Iterator

from . import db
from .db import pad
from .logic import ContactState

# Messages the assistant sent on its own initiative (they count toward the daily cap).
# 'session' (focus session start/end, Phase 2) and 'reentry' (after a lapse, Phase 3) count too.
PROACTIVE_KINDS = ("nudge", "reminder", "meeting", "morning", "checkin", "evening", "session", "reentry")

# What the user does in the chat: typed messages and taps (a tap writes an event, e.g. "[Marked done: …]").
ACTIVITY_KINDS = ("chat", "event")

ROLES = ("user", "assistant")
KINDS = ("chat", "morning", "checkin", "evening", "nudge", "reminder", "meeting", "event", "session", "reentry")


def _decode(raw: dict[str, Any]) -> dict[str, Any]:
    row = MESSAGE.row(raw)
    row["chips"] = json.loads(row["chips"]) if row["chips"] else []
    return row


MESSAGE = db.Entity(
    {"id": "int", "role": "str", "content": "str", "kind": "str", "task_id": "int", "task_ids": "ints",
     "chips": "str", "model": "str", "created_at": "dt"},
    lambda m: {"pk": "msg", "sk": pad(m["id"]), "g1pk": f"msg#{m['kind']}", "g1sk": pad(m["id"])},
    defaults={"kind": "chat", "task_ids": []},
)


def _store(row: dict[str, Any]) -> dict[str, Any]:
    """The row with chips as JSON text, the way it's stored."""
    return {**row, "chips": json.dumps(row["chips"] or [], ensure_ascii=False)}


def add(role: str, content: str, kind: str = "chat", task_ids: list[int] | tuple = (),
        chips: list[dict[str, Any]] | None = None, model: str | None = None) -> dict[str, Any]:
    """
    chips: the reply buttons under this message (logic.chips_json), chosen by code.
    model: the LLM that wrote it (None when code wrote it: templates, events, button answers).
    """
    if role not in ROLES or kind not in KINDS:
        raise ValueError(f"Unknown message role/kind: {role}/{kind}")
    ids = list(dict.fromkeys(int(i) for i in task_ids))
    row = {"id": db.next_id("message"), "role": role, "content": content, "kind": kind,
           "task_id": ids[0] if ids else None, "task_ids": ids, "chips": list(chips or []), "model": model,
           "created_at": db.now_utc()}
    db.put(MESSAGE, _store(row), new=True)
    _write_links(row["id"], ids, [])
    return row


def _write_links(message_id: int, new: list[int], old: list[int]) -> None:
    puts = [db.to_attr({"pk": f"mtask#{i}", "sk": pad(message_id)}) for i in new if i not in old]
    deletes = [(f"mtask#{i}", pad(message_id)) for i in old if i not in new]
    if puts or deletes:
        db.write_batch(puts, deletes)


def add_event(text: str, task_ids: list[int] | tuple = ()) -> dict[str, Any]:
    """Something that happened outside the chat, e.g. "[Marked done: Call plumber]".
    Stored as a user-side message so the assistant reads it as the user's action."""
    return add("user", text, "event", task_ids)


def link(message_ids: list[int], task_ids: list[int]) -> None:
    ids = list(dict.fromkeys(int(i) for i in task_ids))
    for mid in dict.fromkeys(message_ids):
        old: list[list[int]] = [[]]

        def change(m: dict[str, Any]) -> dict[str, Any]:
            old[0] = list(m["task_ids"])
            return {**m, "task_ids": ids, "task_id": ids[0] if ids else None}
        if db.mutate(MESSAGE, "msg", pad(mid), change) is not None:
            _write_links(mid, ids, old[0])


def _newest_first(**kw: Any) -> Iterator[dict[str, Any]]:
    """The thread from the newest message back, fetched page by page as needed."""
    for raw in db.query("msg", forward=False, page_size=kw.pop("page_size", 50), **kw):
        yield _decode(raw)


def _since_desc(start: datetime, inclusive: bool = True) -> Iterator[dict[str, Any]]:
    for m in _newest_first():
        if m["created_at"] < start or (not inclusive and m["created_at"] == start):
            return
        yield m


def since(start: datetime, limit: int = 40) -> list[dict[str, Any]]:
    """The current chat day (what the LLM sees), oldest first, capped for very busy days."""
    rows = []
    for m in _since_desc(start):
        rows.append(m)
        if len(rows) >= limit:
            break
    return rows[::-1]


def _get_many(ids: list[int]) -> list[dict[str, Any]]:
    """Messages by id, oldest first."""
    rows = db.get_many(MESSAGE, [("msg", pad(i)) for i in ids])
    for m in rows:
        m["chips"] = json.loads(m["chips"]) if m["chips"] else []
    return sorted(rows, key=lambda m: m["id"])


def for_tasks(task_ids: list[int], limit: int = 6) -> list[dict[str, Any]]:
    """Latest messages linked to any of these tasks (may be from earlier days), oldest first."""
    if not task_ids:
        return []
    ids: set[int] = set()
    for t in dict.fromkeys(task_ids):
        ids.update(int(it["sk"]) for it in db.query(f"mtask#{t}", forward=False, limit=limit))
    return _get_many(sorted(ids)[-limit:])


def _latest_of_kind(kind: str, role: str | None = None) -> dict[str, Any] | None:
    for raw in db.query(f"msg#{kind}", index="GSI1", forward=False, page_size=20):
        m = _decode(raw)
        if role is None or m["role"] == role:
            return m
    return None


def _latest_of_kinds(kinds: tuple[str, ...], role: str | None = None) -> dict[str, Any] | None:
    found = [m for m in (_latest_of_kind(k, role) for k in kinds) if m]
    return max(found, key=lambda m: m["id"]) if found else None


def last_proactive() -> dict[str, Any] | None:
    # Usually among the latest few messages; otherwise ask the kind index.
    for n, m in enumerate(_newest_first()):
        if m["kind"] in PROACTIVE_KINDS:
            return m
        if n >= 100:
            break
    return _latest_of_kinds(PROACTIVE_KINDS)


def recent_task_ids(start: datetime, last_n: int = 6) -> list[int]:
    """Tasks linked to the last few messages of today, most recent first."""
    out: list[int] = []
    for m in reversed(since(start, last_n)):
        for i in m["task_ids"] or []:
            if i not in out:
                out.append(i)
    return out


def user_replied_since(t: datetime) -> bool:
    """Typed or tapped: a user who only answers with taps is never counted as ignoring the app."""
    return any(m["role"] == "user" and m["kind"] in ACTIVITY_KINDS for m in _since_desc(t, inclusive=False))


def last_user_reply_at() -> datetime | None:
    """When the user last wrote in the chat or tapped a button."""
    m = _latest_of_kinds(ACTIVITY_KINDS, role="user")
    return m["created_at"] if m else None


def last_kind_at(kind: str) -> datetime | None:
    """When a message of this kind was last sent (e.g. 'reentry': one per lapse)."""
    m = _latest_of_kind(kind)
    return m["created_at"] if m else None


def last_user_text() -> str | None:
    """The user's latest typed message (decides the language of the reply buttons)."""
    m = _latest_of_kind("chat", role="user")
    return m["content"] if m else None


def contact_state(day_start: datetime) -> ContactState:
    # Today's messages from the newest end, so the cost doesn't grow with history.
    last_at = None
    sent = 0
    by_task: dict[int, int] = {}
    for m in _since_desc(day_start):
        if m["kind"] not in PROACTIVE_KINDS:
            continue
        last_at = last_at or m["created_at"]
        sent += 1
        if m["kind"] == "nudge":
            for i in m["task_ids"]:
                by_task[i] = by_task.get(i, 0) + 1
    if last_at is None:
        last = last_proactive()
        last_at = last["created_at"] if last else None
    return ContactState(last_proactive_at=last_at, sent_today=sent, sent_today_by_task=by_task)


def _public(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """For the UI: each message with its main task's title and status, and the titles of every linked task."""
    from . import tasks  # tasks doesn't import thread; this keeps it that way at import time
    ids = {i for m in rows for i in m["task_ids"]} | {m["task_id"] for m in rows if m["task_id"]}
    by_id = {t["id"]: t for t in tasks.get_many(sorted(ids))}
    out = []
    for m in rows:
        main = by_id.get(m["task_id"]) if m["task_id"] else None
        out.append({**m, "task_title": main["title"] if main else None,
                    "task_status": main["status"] if main else None,
                    "task_titles": {str(i): by_id[i]["title"] for i in m["task_ids"] if i in by_id}})
    return out


def page(before_id: int | None = None, limit: int = 50, after_id: int | None = None) -> list[dict[str, Any]]:
    """For the UI: the newest `limit` messages older than before_id / newer than after_id, oldest first."""
    kw: dict[str, Any] = {}
    if before_id is not None and after_id is not None:
        if before_id - after_id <= 1:
            return []
        kw = {"sk_op": "between", "sk": (pad(after_id + 1), pad(before_id - 1))}
    elif before_id is not None:
        kw = {"sk_op": "<", "sk": pad(before_id)}
    elif after_id is not None:
        kw = {"sk_op": ">", "sk": pad(after_id)}
    rows = [_decode(r) for r in db.query("msg", forward=False, limit=limit, **kw)]
    return _public(rows[::-1])


def by_ids(ids: list[int]) -> list[dict[str, Any]]:
    """Messages in the same shape as page(), oldest first."""
    return _public(_get_many(ids))


def all_since(start: datetime) -> list[dict[str, Any]]:
    """Every message since `start`, oldest first (reports)."""
    return list(_since_desc(start))[::-1]

