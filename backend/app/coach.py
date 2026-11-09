"""
Writes the proactive messages: task check-ins (grouped), meeting reminders and the daily plans.

Code decides WHEN (scheduler.py + logic.py). This module decides WHAT, with the LLM when it's
available and a fixed template when it isn't — a reminder is never lost because of the LLM.
Meeting facts (title, time, minutes left) always come from code; the LLM only adds text around them.
"""

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from . import context, tasks, thread
from .config import get_settings
from .llm import LLMUnavailable, chat_completion
from .llm_loop import run as run_tools
from .logic import day_start
from .prompting import render
from .tools import COACH_TOOLS, ToolContext

log = logging.getLogger("coach")

PRIORITY_PREFIX = {1: "", 2: "❗ ", 3: "🚨 "}
MAX_TEXT = 1200


@dataclass
class Composed:
    text: str
    kind: str                      # nudge | reminder | meeting
    title: str                     # push notification title
    followups: dict[int, datetime] = field(default_factory=dict)
    used_llm: bool = False


# ================================================================ pure helpers

def message_kind(group: list[dict[str, Any]]) -> str:
    """
    meeting  — only meetings
    reminder — only reminders the user asked for (and meetings)
    nudge    — anything else. A mixed group counts as a nudge for every task in it: that can only
               make the per-task cap stricter, never let it be exceeded.
    """
    if all(t["kind"] == "meeting" for t in group):
        return "meeting"
    if all(t["kind"] == "meeting" or t.get("remind_requested") for t in group):
        return "reminder"
    return "nudge"


def meeting_line(task: dict[str, Any], now: datetime) -> str:
    tz = get_settings().tz
    start = task["due_at"]
    mins = int((start - now).total_seconds() // 60)
    when = "starting now" if mins <= 1 else f"in {mins} min"
    return f"📅 {task['title']} — {start.astimezone(tz):%H:%M} ({when})"


def template_line(task: dict[str, Any], now: datetime) -> str:
    """The old fixed reminder text: the fallback when the LLM can't help."""
    tz = get_settings().tz
    title = PRIORITY_PREFIX.get(task["priority"], "") + task["title"]
    if task["kind"] == "meeting" and task.get("due_at"):
        return meeting_line(task, now)
    if task.get("due_at") and task["due_at"] < now:
        return f"{title} — overdue since {task['due_at'].astimezone(tz):%a %H:%M}. Do it or snooze it."
    if task.get("nag_count", 0) > 0:
        return f"{title} — reminder #{task['nag_count'] + 1}, still not done."
    if task.get("due_at"):
        return f"{title} — due {task['due_at'].astimezone(tz):%a %H:%M}."
    return f"{title} — time to do this."


def fallback_text(group: list[dict[str, Any]], now: datetime) -> str:
    return "\n".join(template_line(t, now) for t in group)


def push_title(group: list[dict[str, Any]]) -> str:
    first = group[0]
    icon = "📅 " if first["kind"] == "meeting" else PRIORITY_PREFIX.get(first["priority"], "")
    title = icon + first["title"]
    return title if len(group) == 1 else f"{title} (+{len(group) - 1})"


# ================================================================ task check-ins

async def compose(group: list[dict[str, Any]], now: datetime,
                  contacts_today: dict[int, int] | None = None) -> Composed:
    """One message for everything due in this tick (meetings first, as ordered by the planner)."""
    s = get_settings()
    meetings = [t for t in group if t["kind"] == "meeting" and t.get("due_at")]
    head = "\n".join(meeting_line(m, now) for m in meetings)
    kind, title = message_kind(group), push_title(group)

    body, followups, used = "", {}, False
    try:
        ctx = ToolContext(mode="coach", now=now)
        body = await asyncio.wait_for(_llm_body(group, now, contacts_today or {}, ctx), timeout=s.llm_timeout_s)
        followups, used = dict(ctx.followups), bool(body.strip())
    except (LLMUnavailable, asyncio.TimeoutError) as e:
        log.warning("Check-in falls back to template: %s", e or type(e).__name__)
    except Exception:
        log.exception("Check-in LLM path failed; using template")

    if not used:
        others = [t for t in group if t not in meetings]
        body, followups = "\n".join(template_line(t, now) for t in others), {}
    text = "\n".join(x for x in (head, body.strip()) if x)[:MAX_TEXT]
    return Composed(text=text, kind=kind, title=title, followups=followups, used_llm=used)


async def _llm_body(group: list[dict[str, Any]], now: datetime, contacts_today: dict[int, int],
                    ctx: ToolContext) -> str:
    s = get_settings()
    now_local = now.astimezone(s.tz)
    ids = [t["id"] for t in group]

    blocks = []
    for t in group:
        block = context.task_context(t, now_local, contacts_today.get(t["id"], 0))
        if t["kind"] == "meeting" and t.get("due_at"):
            block += f"\n  (Shown above your text by the system: {meeting_line(t, now)})"
        elif t.get("remind_requested"):
            block += "\n  (The user asked to be reminded now.)"
        blocks.append(block)

    today = thread.since(day_start(now_local, s.day_reset_time), 8)
    open_today = tasks.list_tasks("today")
    user = "\n\n".join([
        "Due for a check-in now:\n" + "\n".join(blocks),
        "Earlier messages about these tasks:\n" + context.linked_history(ids, now_local),
        "Today's conversation so far (latest last):\n" + (context.format_messages(today, now_local) or "(nothing yet)"),
        "Other open items today:\n" + context.format_open_items(open_today, now_local, set(ids)),
        "Write the check-in message now.",
    ])
    messages = [
        {"role": "system", "content": render("checkin", now=f"{now_local:%Y-%m-%d %H:%M}", weekday=f"{now_local:%A}")},
        {"role": "user", "content": user},
    ]
    has_tasks = any(t["kind"] != "meeting" for t in group)
    return await run_tools(messages, COACH_TOOLS if has_tasks else [], ctx, max_rounds=3,
                           timeout=s.llm_timeout_s)


# ================================================================ daily jobs

JOB_PROMPTS = {"morning": "daily_morning", "checkin": "daily_checkin", "evening": "daily_evening"}
JOB_TITLES = {"morning": "☀️ Morning plan", "checkin": "⏰ Check-in", "evening": "🌙 Evening review"}


def _snapshot_text(now_local: datetime) -> tuple[str, int]:
    today = tasks.list_tasks("today")
    parts = []
    if today:
        parts.append("Open for today (incl. overdue):\n" + "\n".join(
            context.format_task(t, now_local, counts=tasks.event_counts(t["id"], 7)) for t in today[:12]))
    done = tasks.list_tasks("done_today")
    parts.append("Done today:\n" + ("\n".join(f"- {t['title']}" for t in done) or "(nothing yet)"))
    upcoming = tasks.list_tasks("upcoming", 7)
    if upcoming:
        parts.append("Next 7 days:\n" + context.format_open_items(upcoming, now_local, set()))
    undated = [t for t in tasks.list_tasks("open") if t["due_at"] is None and t["parent_id"] is None]
    if undated:
        parts.append("Open without a date:\n" + context.format_open_items(undated, now_local, set()))
    return "\n\n".join(parts), len(today)


async def compose_daily(job: str, now: datetime) -> str | None:
    """Text for a daily job, or None if there's nothing worth saying (empty mid-day check-in)."""
    s = get_settings()
    now_local = now.astimezone(s.tz)
    snapshot, open_count = _snapshot_text(now_local)
    if job == "checkin" and open_count == 0:
        return None
    today = thread.since(day_start(now_local, s.day_reset_time), 12)
    messages = [
        {"role": "system", "content": render(JOB_PROMPTS[job], now=f"{now_local:%Y-%m-%d %H:%M}",
                                             weekday=f"{now_local:%A}")},
        {"role": "user", "content": snapshot + "\n\nToday's conversation so far:\n"
                                    + (context.format_messages(today, now_local) or "(nothing yet)")},
    ]
    try:
        msg = await chat_completion(messages, temperature=0.5)
        text = (msg.get("content") or "").strip()
        if text:
            return text[:MAX_TEXT]
    except LLMUnavailable as e:
        log.warning("Daily job %s falls back to template: %s", job, e)
    except Exception:
        log.exception("Daily job %s failed; using template", job)
    if open_count == 0:
        return "Nothing open for today." if job != "evening" else "Nothing left open today. 👍"
    return f"{open_count} open item(s) for today. Open the app to see them."
