"""Done / Snooze / Cancel from a notification button or the task list.

Each one also writes a short event into the chat, so the assistant knows what happened.
"""

from typing import Any

from . import tasks, thread


def _fmt_minutes(minutes: int) -> str:
    if minutes % (24 * 60) == 0:
        d = minutes // (24 * 60)
        return f"{d} day" + ("s" if d != 1 else "")
    if minutes % 60 == 0:
        return f"{minutes // 60}h"
    return f"{minutes} min"


def apply(task_id: int, action: str, minutes: int = 60) -> dict[str, Any] | None:
    before = tasks.get(task_id)
    if before is None:
        return None
    if action == "done":
        task = tasks.complete(task_id)
        recurring = bool(before["recurrence"] and before["due_at"])
        text = f"[Marked done: {before['title']}" + (" — next one scheduled]" if recurring else "]")
    elif action == "snooze":
        task = tasks.snooze(task_id, minutes)
        text = f"[Snoozed {_fmt_minutes(minutes)}: {before['title']}]"
    elif action == "cancel":
        task = tasks.cancel(task_id)
        text = f"[Cancelled: {before['title']}]"
    else:
        raise ValueError(f"Unknown action: {action}")
    if task is not None and before["status"] == "open":
        thread.add_event(text, [task_id])
    return task
