"""Task storage and the operations shared by the agent, the UI and notification buttons."""

from datetime import datetime, timedelta, timezone
from typing import Any

from . import db
from .config import get_settings
from .logic import next_occurrence

COLUMNS = ("id, title, notes, kind, priority, status, due_at, remind_at, recurrence, "
           "nag_count, escalated, last_notified_at, created_at, completed_at")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def to_public(task: dict[str, Any]) -> dict[str, Any]:
    """JSON-friendly task with times in local ISO format."""
    tz = get_settings().tz
    out = dict(task)
    for key in ("due_at", "remind_at", "last_notified_at", "created_at", "completed_at"):
        if out.get(key) is not None:
            out[key] = out[key].astimezone(tz).isoformat(timespec="minutes")
    return out


def get(task_id: int) -> dict[str, Any] | None:
    return db.fetch_one(f"select {COLUMNS} from tasks where id = %s", (task_id,))


def create(title: str, *, kind: str = "task", priority: int = 1, notes: str | None = None,
           due_at: datetime | None = None, remind_at: datetime | None = None,
           recurrence: str | None = None) -> dict[str, Any]:
    s = get_settings()
    if remind_at is None and due_at is not None:
        lead = s.meeting_lead_min if kind == "meeting" else 0
        remind_at = due_at - timedelta(minutes=lead)
    return db.fetch_one(
        f"""insert into tasks (title, kind, priority, notes, due_at, remind_at, recurrence)
            values (%s, %s, %s, %s, %s, %s, %s) returning {COLUMNS}""",
        (title, kind, priority, notes, due_at, remind_at, recurrence),
    )


UPDATABLE = {"title", "notes", "kind", "priority", "due_at", "remind_at", "recurrence", "status"}


def update(task_id: int, **fields: Any) -> dict[str, Any] | None:
    fields = {k: v for k, v in fields.items() if k in UPDATABLE}
    if not fields:
        return get(task_id)
    # Changing the reminder time resets the nagging state.
    if "remind_at" in fields or "due_at" in fields:
        fields.update(nag_count=0, escalated=False)
    sets = ", ".join(f"{k} = %({k})s" for k in fields)
    fields["id"] = task_id
    return db.fetch_one(f"update tasks set {sets} where id = %(id)s returning {COLUMNS}", fields)


def complete(task_id: int) -> dict[str, Any] | None:
    """Mark done. Recurring tasks roll forward to the next occurrence instead."""
    task = get(task_id)
    if task is None or task["status"] != "open":
        return task
    if task["recurrence"] and task["due_at"]:
        tz = get_settings().tz
        nxt = next_occurrence(task["due_at"], task["recurrence"], tz)
        while nxt <= now_utc():  # catch up if it was overdue for a while
            nxt = next_occurrence(nxt, task["recurrence"], tz)
        lead = (task["due_at"] - task["remind_at"]) if task["remind_at"] else timedelta(0)
        return db.fetch_one(
            f"""update tasks set due_at = %s, remind_at = %s, nag_count = 0, escalated = false,
                last_notified_at = null where id = %s returning {COLUMNS}""",
            (nxt, nxt - lead, task_id),
        )
    return db.fetch_one(
        f"""update tasks set status = 'done', completed_at = now(), remind_at = null
            where id = %s returning {COLUMNS}""",
        (task_id,),
    )


def snooze(task_id: int, minutes: int = 60) -> dict[str, Any] | None:
    return db.fetch_one(
        f"""update tasks set remind_at = now() + make_interval(mins => %s), nag_count = 0,
            escalated = false where id = %s and status = 'open' returning {COLUMNS}""",
        (minutes, task_id),
    )


def cancel(task_id: int) -> dict[str, Any] | None:
    return db.fetch_one(
        f"update tasks set status = 'cancelled', remind_at = null where id = %s returning {COLUMNS}",
        (task_id,),
    )


def day_bounds(days_ahead: int = 0) -> tuple[datetime, datetime]:
    """Start and end of a local day, as aware datetimes."""
    tz = get_settings().tz
    start = datetime.now(tz).replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=days_ahead)
    return start, start + timedelta(days=1)


def list_tasks(scope: str = "open", days: int = 7) -> list[dict[str, Any]]:
    start, end = day_bounds()
    if scope == "today":
        return db.fetch_all(
            f"""select {COLUMNS} from tasks where status = 'open'
                and (due_at < %s or remind_at < %s) order by coalesce(due_at, remind_at)""",
            (end, end),
        )
    if scope == "overdue":
        return db.fetch_all(
            f"select {COLUMNS} from tasks where status = 'open' and due_at < now() order by due_at", ()
        )
    if scope == "upcoming":
        return db.fetch_all(
            f"""select {COLUMNS} from tasks where status = 'open' and due_at >= %s and due_at < %s
                order by due_at""",
            (end, end + timedelta(days=days)),
        )
    if scope == "done_today":
        return db.fetch_all(
            f"select {COLUMNS} from tasks where status = 'done' and completed_at >= %s order by completed_at",
            (start,),
        )
    # "open": everything not finished
    return db.fetch_all(
        f"select {COLUMNS} from tasks where status = 'open' order by due_at nulls last, priority desc, id", ()
    )
