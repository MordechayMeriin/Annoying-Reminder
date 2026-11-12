"""The single chat thread: messages, their links to tasks, and the counts the guardrails need."""

import json
from datetime import datetime
from typing import Any

from . import db
from .logic import ContactState

# Messages the assistant sent on its own initiative (they count toward the daily cap).
# 'session' (focus session start/end, Phase 2) and 'reentry' (after a lapse, Phase 3) count too.
PROACTIVE_KINDS = ("nudge", "reminder", "meeting", "morning", "checkin", "evening", "session", "reentry")

# What the user does in the chat: typed messages and taps (a tap writes an event, e.g. "[Marked done: …]").
ACTIVITY_KINDS = ("chat", "event")

_COLS = "id, role, content, kind, task_id, task_ids, chips, created_at"


def add(role: str, content: str, kind: str = "chat", task_ids: list[int] | tuple = (),
        chips: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    """chips: the reply buttons under this message (logic.chips_json), chosen by code."""
    ids = list(dict.fromkeys(task_ids))
    return db.fetch_one(
        f"""insert into messages (role, content, kind, task_id, task_ids, chips)
            values (%s, %s, %s, %s, %s::bigint[], %s::jsonb) returning {_COLS}""",
        (role, content, kind, ids[0] if ids else None, ids, json.dumps(chips or [], ensure_ascii=False)),
    )


def add_event(text: str, task_ids: list[int] | tuple = ()) -> dict[str, Any]:
    """Something that happened outside the chat, e.g. "[Marked done: Call plumber]".
    Stored as a user-side message so the assistant reads it as the user's action."""
    return add("user", text, "event", task_ids)


def link(message_ids: list[int], task_ids: list[int]) -> None:
    ids = list(dict.fromkeys(task_ids))
    if not message_ids:
        return
    db.execute("update messages set task_ids = %s::bigint[], task_id = %s where id = any(%s::bigint[])",
               (ids, ids[0] if ids else None, list(message_ids)))


def since(start: datetime, limit: int = 40) -> list[dict[str, Any]]:
    """The current chat day (what the LLM sees), oldest first, capped for very busy days."""
    rows = db.fetch_all(
        f"select {_COLS} from messages where created_at >= %s order by created_at desc, id desc limit %s",
        (start, limit),
    )
    return rows[::-1]


def for_tasks(task_ids: list[int], limit: int = 6) -> list[dict[str, Any]]:
    """Latest messages linked to any of these tasks (may be from earlier days), oldest first."""
    if not task_ids:
        return []
    rows = db.fetch_all(
        f"select {_COLS} from messages where task_ids && %s::bigint[] order by created_at desc, id desc limit %s",
        (list(task_ids), limit),
    )
    return rows[::-1]


def last_proactive() -> dict[str, Any] | None:
    return db.fetch_one(
        f"select {_COLS} from messages where kind = any(%s) order by created_at desc, id desc limit 1",
        (list(PROACTIVE_KINDS),),
    )


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
    row = db.fetch_one(
        "select 1 as x from messages where role = 'user' and kind = any(%s) and created_at > %s limit 1",
        (list(ACTIVITY_KINDS), t))
    return row is not None


def last_user_reply_at() -> datetime | None:
    """When the user last wrote in the chat or tapped a button (one query, for several tasks at once)."""
    row = db.fetch_one(
        "select created_at from messages where role = 'user' and kind = any(%s) order by created_at desc limit 1",
        (list(ACTIVITY_KINDS),))
    return row["created_at"] if row else None


def last_kind_at(kind: str) -> datetime | None:
    """When a message of this kind was last sent (e.g. 'reentry': one per lapse)."""
    row = db.fetch_one("select created_at from messages where kind = %s order by created_at desc limit 1", (kind,))
    return row["created_at"] if row else None


def last_user_text() -> str | None:
    """The user's latest typed message (decides the language of the reply buttons)."""
    row = db.fetch_one(
        "select content from messages where role = 'user' and kind = 'chat' order by created_at desc limit 1")
    return row["content"] if row else None


def contact_state(day_start: datetime) -> ContactState:
    # Both halves walk the created_at index from the newest end, so the cost doesn't grow with history.
    last = db.fetch_one(
        """select (select created_at from messages where kind = any(%(kinds)s)
                   order by created_at desc limit 1) as t,
                  (select count(*) from messages where kind = any(%(kinds)s)
                   and created_at >= %(start)s) as n""",
        {"kinds": list(PROACTIVE_KINDS), "start": day_start},
    ) or {}
    per_task = db.fetch_all(
        """select t.id as task_id, count(*) as n
           from messages m cross join unnest(m.task_ids) as t(id)
           where m.kind = 'nudge' and m.created_at >= %s group by t.id""",
        (day_start,),
    )
    return ContactState(
        last_proactive_at=last.get("t"),
        sent_today=int(last.get("n") or 0),
        sent_today_by_task={r["task_id"]: int(r["n"]) for r in per_task},
    )


# For the UI: each message with its main task's title and status, and the titles of every linked task.
_PUBLIC = """select m.id, m.role, m.content, m.kind, m.task_id, m.task_ids, m.chips, m.created_at,
                    t.title as task_title, t.status as task_status,
                    (select coalesce(jsonb_object_agg(x.id::text, x.title), '{}'::jsonb)
                     from tasks x where x.id = any(m.task_ids)) as task_titles
             from messages m left join tasks t on t.id = m.task_id"""


def page(before_id: int | None = None, limit: int = 50, after_id: int | None = None) -> list[dict[str, Any]]:
    """For the UI: the newest `limit` messages older than before_id / newer than after_id, oldest first."""
    rows = db.fetch_all(
        f"""{_PUBLIC}
            where (%(before)s::bigint is null or m.id < %(before)s)
              and (%(after)s::bigint is null or m.id > %(after)s)
            order by m.created_at desc, m.id desc limit %(limit)s""",
        {"before": before_id, "after": after_id, "limit": limit},
    )
    return rows[::-1]


def by_ids(ids: list[int]) -> list[dict[str, Any]]:
    """Messages in the same shape as page(), oldest first."""
    if not ids:
        return []
    return db.fetch_all(f"{_PUBLIC} where m.id = any(%s::bigint[]) order by m.id", (list(ids),))
