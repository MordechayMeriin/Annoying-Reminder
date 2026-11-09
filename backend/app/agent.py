"""
The chat agent: your messages → helpful replies and task operations (tool calling).

What the model sees: the system prompt (with the coach skill), a "likely topic" block, and today's
messages only (the chat resets daily at DAY_RESET_TIME; task descriptions and histories carry the
long-term memory). Which task a turn is about is the model's call: code only offers candidates,
and the turn is linked to the tasks the model actually acted on.
"""

import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Callable

from . import context, tasks, thread
from .config import get_settings
from .llm_loop import run as run_tools
from .logic import day_start, link_task_ids, select_candidate_tasks
from .prompting import render
from .tools import CHAT_TOOLS, ToolContext

log = logging.getLogger("agent")

MAX_TOOL_ROUNDS = 6


@dataclass
class ChatResult:
    reply: str
    changed: bool
    user_message_id: int
    reply_message_id: int
    task_ids: list[int]


def normalize_roles(history: list[dict[str, Any]]) -> list[dict[str, str]]:
    """
    Chat history → alternating user/assistant turns (Gemma's template rejects two of the same in a
    row, e.g. two proactive messages, or an event followed by a user message). Same-role neighbours
    are merged. Events are user-side. The first turn must be the user's.
    """
    out: list[dict[str, str]] = []
    for m in history:
        role = "user" if m["role"] == "user" else "assistant"
        content = m["content"]
        if out and out[-1]["role"] == role:
            out[-1]["content"] += "\n\n" + content
        else:
            out.append({"role": role, "content": content})
    if out and out[0]["role"] == "assistant":
        out.insert(0, {"role": "user", "content": "(start of today's chat)"})
    return out


def likely_topic(candidate_ids: list[int], checkin: dict[str, Any] | None, now_local) -> str:
    parts = []
    if checkin:
        parts.append(f"Your last check-in ({context.fmt_when(checkin['created_at'], now_local)}): "
                     f"\"{checkin['content'][:400]}\"")
    by_id = {t["id"]: t for t in tasks.get_many(candidate_ids)}
    blocks = [context.task_context(by_id[i], now_local) for i in candidate_ids if i in by_id]
    if blocks:
        parts.append("Tasks it may be about (a guess from recent messages, not certain):\n" + "\n".join(blocks))
        parts.append("Earlier messages about these:\n" + context.linked_history(candidate_ids, now_local, 6))
        parts.append("The user's message may be about one of these, a different task, a new task, or nothing "
                     "task-related. Decide from what they wrote. If it's ambiguous and it matters, ask one short question.")
    else:
        parts.append("No obvious task. It may be a new task, a question, or about any task (use list_tasks).")
    return "\n\n".join(parts)


def tool_status(name: str, args: dict[str, Any]) -> dict[str, Any]:
    """A tool call → a small status for the chat UI ("Writing a new task: …"). Never raises."""
    status: dict[str, Any] = {"type": "tool", "name": name}
    title = args.get("title")
    if isinstance(title, str) and title.strip():
        status["title"] = title.strip()[:120]
    if name == "add_task" and args.get("kind") == "meeting":
        status["kind"] = "meeting"
    task_id = args.get("id") if "id" in args else args.get("task_id")
    if isinstance(task_id, int) and not isinstance(task_id, bool):
        try:
            task = tasks.get(task_id)
        except Exception:  # only a label: never let it break the turn
            log.exception("Status lookup for task %s failed", task_id)
            task = None
        if task:
            status["taskTitle"] = task["title"]
    return status


Progress = Callable[[dict[str, Any]], None]


async def handle_user_message(text: str, on_progress: Progress | None = None) -> ChatResult:
    """
    Process one chat message. LLMUnavailable propagates (the user message is kept).

    on_progress, if given, gets {"type": "received", "userMessageId"} once the message is saved,
    then {"type": "thinking"} before each LLM call and a tool_status() before each tool runs.
    """
    def progress(event: dict[str, Any]) -> None:
        if on_progress is None:
            return
        try:
            on_progress(tool_status(event["name"], event["args"]) if event["type"] == "tool" else event)
        except Exception:  # status updates are cosmetic
            log.exception("Progress callback failed")

    s = get_settings()
    now = tasks.now_utc()
    now_local = now.astimezone(s.tz)
    start = day_start(now_local, s.day_reset_time)

    history = thread.since(start, 40)
    user_msg = thread.add("user", text)
    progress({"type": "received", "userMessageId": user_msg["id"]})

    last = thread.last_proactive()
    recent = last if last and now - last["created_at"] <= timedelta(hours=s.reply_link_hours) else None
    checkin_ids = list(recent["task_ids"]) if recent else []
    candidates = select_candidate_tasks(text, now, last, thread.recent_task_ids(start),
                                        tasks.list_tasks("open"), s.reply_link_hours)

    system = render("system", now=f"{now_local:%Y-%m-%d %H:%M}", weekday=f"{now_local:%A}", tz=s.tz.key,
                    likely_topic=likely_topic(candidates, recent, now_local))
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": system},
        *normalize_roles(history + [{"role": "user", "content": text}]),
    ]

    ctx = ToolContext(mode="chat", now=now)
    try:
        reply = await run_tools(messages, CHAT_TOOLS, ctx, max_rounds=MAX_TOOL_ROUNDS, timeout=s.llm_timeout_s,
                                on_event=progress)
    finally:
        linked = link_task_ids(ctx.acted_on, checkin_ids)
        thread.link([user_msg["id"]], linked)
    if not reply:
        reply = "Done." if ctx.changed else "Sorry, I couldn't process that. Try again?"

    reply_msg = thread.add("assistant", reply, "chat", linked)
    return ChatResult(reply=reply, changed=ctx.changed, user_message_id=user_msg["id"],
                      reply_message_id=reply_msg["id"], task_ids=linked)
