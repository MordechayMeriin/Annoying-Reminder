"""
Focus sessions storage: ▶ Start → a timer → one check-in at the end (F5 of the brief).

Only reads and writes rows; the rules (when a session has ended, when silence means paused, the focus
hold) are pure functions in logic.py, and actions.py runs the lifecycle (with its chat events).
At most one session is open at a time: starting a new one closes the old one first.

A session row: id, task_id, started_at, planned_min, ended_at (null while open), outcome
(done | another | stopped | paused), end_sent_at (the end check-in went out), note_closed_at
("Where did you stop?" answered or dismissed).
"""

from datetime import datetime
from typing import Any

from . import db
from .config import get_settings
from .db import pad
from .logic import actual_minutes, session_end_at

OUTCOMES = ("done", "another", "stopped", "paused")

SESSION = db.Entity(
    {"id": "int", "task_id": "int", "started_at": "dt", "planned_min": "int", "ended_at": "dt", "outcome": "str",
     "end_sent_at": "dt", "note_closed_at": "dt"},
    # GSI1 lists only open sessions; GSI2 a task's sessions.
    lambda x: {"pk": "sess", "sk": pad(x["id"]), "g2pk": f"sesstask#{x['task_id']}", "g2sk": pad(x["id"]),
               **({"g1pk": "sess#open", "g1sk": pad(x["id"])} if x.get("ended_at") is None else {})},
)


def _mutate(session_id: int, change) -> dict[str, Any] | None:
    return db.mutate(SESSION, "sess", pad(session_id), change)


def start(task_id: int, planned_min: int) -> dict[str, Any]:
    if planned_min <= 0 or planned_min % 5:
        raise ValueError("planned_min must be a positive multiple of 5")
    row = SESSION.row({})
    row.update(id=db.next_id("session"), task_id=task_id, started_at=db.now_utc(), planned_min=planned_min)
    db.put(SESSION, row, new=True)
    return row


def get(session_id: int) -> dict[str, Any] | None:
    return db.get(SESSION, "sess", pad(session_id))


def latest() -> dict[str, Any] | None:
    """The most recent session (open or closed): the focus hold, the breadcrumb and the mid-day skip use it."""
    rows = db.query_rows(SESSION, "sess", forward=False, limit=1)
    return rows[0] if rows else None


def open_sessions() -> list[dict[str, Any]]:
    """Sessions without an outcome yet (normally at most one)."""
    rows = [get(x["id"]) for x in db.query_rows(SESSION, "sess#open", index="GSI1")]
    return sorted((x for x in rows if x and x["ended_at"] is None), key=lambda x: (x["started_at"], x["id"]))


def latest_for_task(task_id: int) -> dict[str, Any] | None:
    rows = db.query_rows(SESSION, f"sesstask#{task_id}", index="GSI2", forward=False, limit=1)
    return get(rows[0]["id"]) if rows else None


def end(session_id: int, outcome: str, ended_at: datetime | None = None) -> dict[str, Any] | None:
    """Record the outcome. None if the session was already closed (e.g. a second tap)."""
    if outcome not in OUTCOMES:
        raise ValueError(f"Unknown outcome: {outcome}")
    when = ended_at or db.now_utc()
    return _mutate(session_id, lambda x: {**x, "ended_at": when, "outcome": outcome} if x["ended_at"] is None else None)


def set_planned(session_id: int, planned_min: int) -> dict[str, Any] | None:
    """A new length for an open session (5/15/25/45 or +5). A sent end check-in will come again at the new end."""
    if planned_min <= 0 or planned_min % 5:
        raise ValueError("planned_min must be a positive multiple of 5")
    return _mutate(session_id, lambda x: {**x, "planned_min": planned_min, "end_sent_at": None}
                   if x["ended_at"] is None else None)


def claim_end(session_id: int) -> bool:
    """Mark the end check-in as sent (before sending, so an overlapping tick can't send it twice)."""
    now = db.now_utc()
    return _mutate(session_id, lambda x: {**x, "end_sent_at": now}
                   if x["ended_at"] is None and x["end_sent_at"] is None else None) is not None


def close_note(session_id: int) -> None:
    """The breadcrumb field was answered or dismissed."""
    now = db.now_utc()
    _mutate(session_id, lambda x: {**x, "note_closed_at": now} if x["note_closed_at"] is None else None)


def close_notes_for_task(task_id: int) -> None:
    """A resume note was saved another way (the chat): don't offer the field for this task again."""
    for x in db.query_rows(SESSION, f"sesstask#{task_id}", index="GSI2"):
        if x["note_closed_at"] is None:
            close_note(x["id"])


def since(start: datetime) -> list[dict[str, Any]]:
    """Sessions started since `start`, newest first."""
    out = []
    for x in db.query_rows(SESSION, "sess", forward=False, page_size=50):
        if x["started_at"] < start:
            break
        out.append(x)
    return out


def stats_since(start: datetime, now: datetime) -> tuple[int, int]:
    """(sessions, minutes) started since `start`: the wins strip and the evening review."""
    rows = since(start)
    return len(rows), sum(actual_minutes(r, now) for r in rows)


def to_public(session: dict[str, Any] | None, task: dict[str, Any] | None = None) -> dict[str, Any] | None:
    """JSON for the app's session screen: times in local ISO, with the task's title, next action and note."""
    if session is None:
        return None
    tz = get_settings().tz
    out: dict[str, Any] = {
        "id": session["id"], "task_id": session["task_id"], "planned_min": session["planned_min"],
        "outcome": session.get("outcome"),
        "started_at": session["started_at"].astimezone(tz).isoformat(timespec="seconds"),
        "ends_at": session_end_at(session).astimezone(tz).isoformat(timespec="seconds"),
        "ended_at": session["ended_at"].astimezone(tz).isoformat(timespec="seconds") if session.get("ended_at") else None,
        "end_sent": session.get("end_sent_at") is not None,
    }
    if task is not None:
        out.update(title=task["title"], next_action=task.get("next_action"), resume_note=task.get("resume_note"),
                   task_status=task["status"])
    return out
