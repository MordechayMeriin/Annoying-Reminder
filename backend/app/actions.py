"""Task actions from a notification button, a reply button (chip), the focus card or the task list.

They run without the LLM. Each one also writes a short event into the chat, so the assistant knows
what happened (and a tap counts as the user's activity, like a typed reply).
"""

from datetime import timedelta
from typing import Any

from . import tasks, thread
from .config import get_settings
from .logic import START_MIN_NOTIFICATION, later_time

ACTIONS = ("done", "snooze", "cancel", "start", "later")


def _fmt_minutes(minutes: int) -> str:
    if minutes % (24 * 60) == 0:
        d = minutes // (24 * 60)
        return f"{d} day" + ("s" if d != 1 else "")
    if minutes % 60 == 0:
        return f"{minutes // 60}h"
    return f"{minutes} min"


def apply(task_id: int, action: str, minutes: int | None = None) -> dict[str, Any] | None:
    """
    done / cancel; snooze `minutes` (default 60);
    start: a `minutes`-long start (default 5) on the next action, with a check-in when it's up
           (the timer screen and session records come with Phase 2);
    later: decided by code (logic.later_time), so the user never picks a time.
    """
    before = tasks.get(task_id)
    if before is None:
        return None
    s = get_settings()
    if action == "done":
        task = tasks.complete(task_id)
        recurring = bool(before["recurrence"] and before["due_at"])
        text = f"[Marked done: {before['title']}" + (" — next one scheduled]" if recurring else "]")
    elif action == "snooze":
        minutes = minutes or 60
        task = tasks.snooze(task_id, minutes)
        text = f"[Snoozed {_fmt_minutes(minutes)}: {before['title']}]"
    elif action == "cancel":
        task = tasks.cancel(task_id)
        text = f"[Cancelled: {before['title']}]"
    elif action == "start":
        minutes = minutes or START_MIN_NOTIFICATION
        task = tasks.set_followup(task_id, tasks.now_utc() + timedelta(minutes=minutes), requested=True)
        if task:
            tasks.add_event(task_id, "session_started", f"{minutes} min")
        step = before.get("next_action") or before["title"]
        text = f"[▶ Started {minutes} min: {step}" + (f" ({before['title']})]" if step != before["title"] else "]")
    elif action == "later":
        when = later_time(tasks.now_utc().astimezone(s.tz), s.morning_time)
        task = tasks.set_followup(task_id, when, requested=True)
        if task:
            tasks.add_event(task_id, "postponed", "later (button)")
        text = f"[Later: {before['title']} — next check-in {when.astimezone(s.tz):%a %H:%M}]"
    else:
        raise ValueError(f"Unknown action: {action}")
    if task is not None and before["status"] == "open":
        thread.add_event(text, [task_id])
    return task
