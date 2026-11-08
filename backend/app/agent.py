"""The chat agent: turns your messages into task operations using tool calling."""

import json
import logging
from datetime import datetime
from typing import Any

from . import db, tasks
from .config import get_settings
from .llm import chat_completion
from .logic import parse_local_datetime

log = logging.getLogger("agent")

MAX_TOOL_ROUNDS = 6
HISTORY_MESSAGES = 16

SYSTEM_PROMPT = """You are "Annoying Reminder", a personal assistant that makes sure the user actually gets things done.
The user writes in Hebrew or English (sometimes mixed). Always answer in the language of their latest message.

Current local time: {now} ({weekday}). Timezone: {tz}. The work week is Sunday–Thursday.

Your job:
- When the user mentions something to do, a meeting or a deadline, save it with add_task. Don't ask for confirmation
  for obvious cases; just save it and say what you saved (title + time) in one short line.
- Resolve relative times ("tomorrow at 9", "בעוד שעתיים", "next Sunday") into an exact local ISO datetime,
  e.g. 2026-11-09T09:00. If no time is given for a task, leave due_at empty unless a day is clear (then use 09:00).
- Meetings: kind="meeting", due_at = start time. Reminders before meetings are automatic.
- priority: 1 normal, 2 important, 3 critical (only when the user signals it can't be missed).
- When the user says something is done, find it (list_tasks) and call complete_task.
- When asked "what's on today" etc., use list_tasks and answer briefly.
- Be concise and direct. A little firm about unfinished things is good; never preachy.
"""


TOOLS: list[dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "add_task",
            "description": "Save a new task, meeting or deadline.",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "Short title in the user's language"},
                    "kind": {"type": "string", "enum": ["task", "meeting"]},
                    "due_at": {"type": "string", "description": "Local ISO datetime of deadline / meeting start, e.g. 2026-11-09T14:00"},
                    "remind_at": {"type": "string", "description": "Optional explicit reminder time (local ISO). Omit to use the default."},
                    "priority": {"type": "integer", "enum": [1, 2, 3]},
                    "recurrence": {"type": "string", "enum": ["daily", "weekdays", "weekly", "monthly"]},
                    "notes": {"type": "string"},
                },
                "required": ["title"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_tasks",
            "description": "List tasks. Use before completing/updating to find the right id.",
            "parameters": {
                "type": "object",
                "properties": {
                    "scope": {"type": "string", "enum": ["today", "overdue", "upcoming", "open", "done_today"]},
                    "days": {"type": "integer", "description": "For 'upcoming': how many days ahead (default 7)"},
                },
                "required": ["scope"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "update_task",
            "description": "Change a task's title, time, priority, notes or recurrence.",
            "parameters": {
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "title": {"type": "string"},
                    "kind": {"type": "string", "enum": ["task", "meeting"]},
                    "due_at": {"type": "string"},
                    "remind_at": {"type": "string"},
                    "priority": {"type": "integer", "enum": [1, 2, 3]},
                    "recurrence": {"type": "string", "enum": ["daily", "weekdays", "weekly", "monthly", "none"]},
                    "notes": {"type": "string"},
                },
                "required": ["id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "complete_task",
            "description": "Mark a task as done.",
            "parameters": {"type": "object", "properties": {"id": {"type": "integer"}}, "required": ["id"]},
        },
    },
    {
        "type": "function",
        "function": {
            "name": "snooze_task",
            "description": "Postpone the next reminder of a task.",
            "parameters": {
                "type": "object",
                "properties": {"id": {"type": "integer"}, "minutes": {"type": "integer"}},
                "required": ["id", "minutes"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "cancel_task",
            "description": "Cancel / delete a task that is no longer relevant.",
            "parameters": {"type": "object", "properties": {"id": {"type": "integer"}}, "required": ["id"]},
        },
    },
]


def _brief(task: dict | None) -> dict | None:
    if task is None:
        return None
    t = tasks.to_public(task)
    return {k: t[k] for k in ("id", "title", "kind", "priority", "status", "due_at", "remind_at", "recurrence")}


def run_tool(name: str, args: dict[str, Any]) -> Any:
    tz = get_settings().tz
    if name == "add_task":
        return _brief(tasks.create(
            args["title"],
            kind=args.get("kind", "task"),
            priority=int(args.get("priority", 1)),
            notes=args.get("notes"),
            due_at=parse_local_datetime(args.get("due_at"), tz),
            remind_at=parse_local_datetime(args.get("remind_at"), tz),
            recurrence=args.get("recurrence"),
        ))
    if name == "list_tasks":
        return [_brief(t) for t in tasks.list_tasks(args.get("scope", "open"), int(args.get("days", 7)))]
    if name == "update_task":
        fields = {k: v for k, v in args.items() if k != "id"}
        for key in ("due_at", "remind_at"):
            if key in fields:
                fields[key] = parse_local_datetime(fields[key], tz)
        if fields.get("recurrence") == "none":
            fields["recurrence"] = None
        return _brief(tasks.update(int(args["id"]), **fields))
    if name == "complete_task":
        return _brief(tasks.complete(int(args["id"])))
    if name == "snooze_task":
        return _brief(tasks.snooze(int(args["id"]), int(args.get("minutes", 60))))
    if name == "cancel_task":
        return _brief(tasks.cancel(int(args["id"])))
    raise ValueError(f"Unknown tool {name}")


def system_message() -> dict[str, str]:
    s = get_settings()
    now = datetime.now(s.tz)
    return {"role": "system", "content": SYSTEM_PROMPT.format(
        now=now.strftime("%Y-%m-%d %H:%M"), weekday=now.strftime("%A"), tz=s.tz.key)}


async def handle_user_message(text: str) -> tuple[str, bool]:
    """Process one chat message. Returns (reply, tasks_changed)."""
    db.execute("insert into messages (role, content) values ('user', %s)", (text,))
    history = db.fetch_all(
        "select role, content from messages order by created_at desc limit %s", (HISTORY_MESSAGES,)
    )[::-1]
    messages: list[dict[str, Any]] = [system_message(), *({"role": h["role"], "content": h["content"]} for h in history)]

    changed = False
    reply = ""
    for _ in range(MAX_TOOL_ROUNDS):
        msg = await chat_completion(messages, tools=TOOLS)
        calls = msg.get("tool_calls") or []
        if not calls:
            reply = (msg.get("content") or "").strip()
            break
        messages.append({"role": "assistant", "content": msg.get("content") or "", "tool_calls": calls})
        for call in calls:
            name = call["function"]["name"]
            try:
                args = json.loads(call["function"].get("arguments") or "{}")
                result = run_tool(name, args)
                if name != "list_tasks":
                    changed = True
            except Exception as e:  # report errors back to the model so it can recover
                log.warning("Tool %s failed: %s", name, e)
                result = {"error": str(e)}
            messages.append({
                "role": "tool",
                "tool_call_id": call["id"],
                "content": json.dumps(result, ensure_ascii=False, default=str),
            })
    if not reply:
        reply = "Done." if changed else "Sorry, I couldn't process that. Try again?"

    db.execute("insert into messages (role, content) values ('assistant', %s)", (reply,))
    return reply, changed
