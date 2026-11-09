"""
Compact, plain-text context for the LLM (easier for a mid-size model than JSON dumps).

The format_* functions are pure; task_context() fetches what they need.
"""

from datetime import datetime
from typing import Any

from . import tasks, thread

PRIORITY = {1: "", 2: "important", 3: "CRITICAL"}


def fmt_when(dt: datetime | None, now_local: datetime) -> str:
    if dt is None:
        return ""
    d = dt.astimezone(now_local.tzinfo)
    days = (d.date() - now_local.date()).days
    day = {0: "today", 1: "tomorrow", -1: "yesterday"}.get(days, d.strftime("%a %d %b"))
    return f"{day} {d:%H:%M}"


def format_task(task: dict[str, Any], now_local: datetime, *, subtasks: list[dict] = (),
                events: list[dict] = (), counts: dict[str, int] | None = None,
                contacts_today: int = 0) -> str:
    head = [f'#{task["id"]} "{task["title"]}"']
    if task["kind"] == "meeting":
        head.append("meeting")
    if PRIORITY.get(task["priority"]):
        head.append(PRIORITY[task["priority"]])
    if task.get("due_at"):
        overdue = task["due_at"] < now_local and task["kind"] != "meeting"
        head.append(("OVERDUE since " if overdue else "due ") + fmt_when(task["due_at"], now_local))
    if task.get("recurrence"):
        head.append(f"repeats {task['recurrence']}")
    if task.get("status") and task["status"] != "open":
        head.append(task["status"])
    if contacts_today:
        head.append(f"checked in {contacts_today}x today")
    lines = [" · ".join(head)]
    if task.get("description"):
        lines.append(f"  Description: {task['description']}")
    if task.get("blocked_reason"):
        lines.append(f"  Blocked: {task['blocked_reason']} (check again {fmt_when(task.get('blocked_until'), now_local)})")
    if subtasks:
        steps = "; ".join(f"[{'x' if s['status'] == 'done' else ' '}] #{s['id']} {s['title']}" for s in subtasks)
        lines.append(f"  Steps: {steps}")
    if events:
        hist = "; ".join(f"{fmt_when(e['created_at'], now_local)} {e['kind']}" + (f" ({e['note']})" if e.get("note")
                         and e["kind"] != "description_changed" else "") for e in events)
        lines.append(f"  History (newest first): {hist}")
    if counts:
        shown = {k: v for k, v in counts.items() if k in ("postponed", "ignored", "committed", "progress", "stuck")}
        if shown:
            lines.append("  Last 7 days: " + ", ".join(f"{k} {v}x" for k, v in sorted(shown.items())))
    return "\n".join(lines)


def format_messages(messages: list[dict[str, Any]], now_local: datetime, max_chars: int = 300) -> str:
    out = []
    for m in messages:
        who = {"user": "User", "assistant": "You"}[m["role"]]
        if m["kind"] == "event":
            who = "Event"
        text = m["content"].replace("\n", " ")
        if len(text) > max_chars:
            text = text[: max_chars - 1] + "…"
        out.append(f"[{fmt_when(m['created_at'], now_local)}] {who}: {text}")
    return "\n".join(out)


def format_open_items(items: list[dict[str, Any]], now_local: datetime, exclude: set[int]) -> str:
    rows = [t for t in items if t["id"] not in exclude][:10]
    if not rows:
        return "(nothing else)"
    return "\n".join(
        f'- #{t["id"]} {t["title"]}' + (f" ({fmt_when(t['due_at'], now_local)})" if t.get("due_at") else "")
        for t in rows
    )


def task_context(task: dict[str, Any], now_local: datetime, contacts_today: int = 0) -> str:
    """One task with its subtasks, recent history and 7-day counts."""
    return format_task(
        task, now_local,
        subtasks=tasks.subtasks(task["id"]),
        events=tasks.recent_events(task["id"], 8),
        counts=tasks.event_counts(task["id"], 7),
        contacts_today=contacts_today,
    )


def linked_history(task_ids: list[int], now_local: datetime, limit: int = 8) -> str:
    msgs = thread.for_tasks(task_ids, limit)
    return format_messages(msgs, now_local) if msgs else "(no earlier messages about this)"
