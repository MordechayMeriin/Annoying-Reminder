"""The single chat thread: messages, their links to tasks, and the counts the guardrails need."""

from datetime import datetime
from typing import Any

from . import db
from .logic import ContactState

# Messages the assistant sent on its own initiative (they count toward the daily cap).
PROACTIVE_KINDS = ("nudge", "reminder", "meeting", "morning", "checkin", "evening")

_COLS = "id, role, content, kind, task_id, task_ids, created_at"


def add(role: str, content: str, kind: str = "chat", task_ids: list[int] | tuple = ()) -> dict[str, Any]:
    ids = list(dict.fromkeys(task_ids))
    return db.fetch_one(
        f"""insert into messages (role, content, kind, task_id, task_ids)
            values (%s, %s, %s, %s, %s::bigint[]) returning {_COLS}""",
        (role, content, kind, ids[0] if ids else None, ids),
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
    row = db.fetch_one(
        "select 1 as x from messages where role = 'user' and kind = 'chat' and created_at > %s limit 1", (t,))
    return row is not None


def contact_state(day_start: datetime) -> ContactState:
    last = db.fetch_one(
        "select max(created_at) as t, count(*) filter (where created_at >= %s) as n "
        "from messages where kind = any(%s)",
        (day_start, list(PROACTIVE_KINDS)),
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


def page(before_id: int | None = None, limit: int = 50) -> list[dict[str, Any]]:
    """For the UI: newest `limit` messages (older than before_id), with the main task's title."""
    rows = db.fetch_all(
        """select m.id, m.role, m.content, m.kind, m.task_id, m.task_ids, m.created_at,
                  t.title as task_title, t.status as task_status
           from messages m left join tasks t on t.id = m.task_id
           where (%(before)s::bigint is null or m.id < %(before)s)
           order by m.created_at desc, m.id desc limit %(limit)s""",
        {"before": before_id, "limit": limit},
    )
    return rows[::-1]
