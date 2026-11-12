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
from .logic import actual_minutes, session_end_at

COLUMNS = "id, task_id, started_at, planned_min, ended_at, outcome, end_sent_at, note_closed_at"


def start(task_id: int, planned_min: int) -> dict[str, Any]:
    return db.fetch_one(
        f"insert into sessions (task_id, planned_min) values (%s, %s) returning {COLUMNS}",
        (task_id, planned_min))


def get(session_id: int) -> dict[str, Any] | None:
    return db.fetch_one(f"select {COLUMNS} from sessions where id = %s", (session_id,))


def latest() -> dict[str, Any] | None:
    """The most recent session (open or closed): the focus hold, the breadcrumb and the mid-day skip use it."""
    return db.fetch_one(f"select {COLUMNS} from sessions order by started_at desc, id desc limit 1")


def open_sessions() -> list[dict[str, Any]]:
    """Sessions without an outcome yet (normally at most one)."""
    return db.fetch_all(f"select {COLUMNS} from sessions where ended_at is null order by started_at")


def latest_for_task(task_id: int) -> dict[str, Any] | None:
    return db.fetch_one(
        f"select {COLUMNS} from sessions where task_id = %s order by started_at desc, id desc limit 1", (task_id,))


def end(session_id: int, outcome: str, ended_at: datetime | None = None) -> dict[str, Any] | None:
    """Record the outcome. None if the session was already closed (e.g. a second tap)."""
    return db.fetch_one(
        f"""update sessions set ended_at = coalesce(%s, now()), outcome = %s
            where id = %s and ended_at is null returning {COLUMNS}""",
        (ended_at, outcome, session_id))


def set_planned(session_id: int, planned_min: int) -> dict[str, Any] | None:
    """A new length for an open session (5/15/25/45 or +5). A sent end check-in will come again at the new end."""
    return db.fetch_one(
        f"""update sessions set planned_min = %s, end_sent_at = null
            where id = %s and ended_at is null returning {COLUMNS}""",
        (planned_min, session_id))


def claim_end(session_id: int) -> bool:
    """Mark the end check-in as sent (before sending, so an overlapping tick can't send it twice)."""
    row = db.fetch_one(
        "update sessions set end_sent_at = now() where id = %s and ended_at is null and end_sent_at is null "
        "returning id", (session_id,))
    return row is not None


def close_note(session_id: int) -> None:
    """The breadcrumb field was answered or dismissed."""
    db.execute("update sessions set note_closed_at = now() where id = %s and note_closed_at is null", (session_id,))


def close_notes_for_task(task_id: int) -> None:
    """A resume note was saved another way (the chat): don't offer the field for this task again."""
    db.execute("update sessions set note_closed_at = now() where task_id = %s and note_closed_at is null",
               (task_id,))


def stats_since(start: datetime, now: datetime) -> tuple[int, int]:
    """(sessions, minutes) started since `start`: the wins strip and the evening review."""
    rows = db.fetch_all(f"select {COLUMNS} from sessions where started_at >= %s", (start,))
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
