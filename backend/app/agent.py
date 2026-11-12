"""
The chat agent: your messages → helpful replies and task operations (tool calling).

What the model sees: the system prompt (with the coach skill), a "likely topic" block, and today's
messages only (the chat resets daily at DAY_RESET_TIME; task descriptions and histories carry the
long-term memory). Which task a turn is about is the model's call: code only offers candidates,
and the turn is linked to the tasks the model actually acted on.
"""

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any, Callable

from . import context, tasks, thread
from .config import get_settings
from .llm_loop import run as run_tools
from .logic import (AVOID_CHIPS, chat_signals, chip_id_for_text, chip_label, chip_lang, chips_for, chips_json,
                    day_start, focus_day, link_task_ids, select_candidate_tasks, select_playbooks, task_stage)
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
    user_message: dict[str, Any] = field(default_factory=dict)    # the saved rows (for the app)
    reply_message: dict[str, Any] = field(default_factory=dict)


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


def likely_topic(candidates: list[dict[str, Any]], checkin: dict[str, Any] | None, now_local,
                 facts: context.Facts | None = None, today=None) -> str:
    parts = []
    if checkin:
        parts.append(f"Your last check-in ({context.fmt_when(checkin['created_at'], now_local)}): "
                     f"\"{checkin['content'][:400]}\"")
    blocks = context.task_contexts(candidates, now_local, facts=facts, today=today)
    if blocks:
        parts.append("Tasks it may be about (a guess from recent messages, not certain):\n" + "\n".join(blocks))
        parts.append("Earlier messages about these:\n"
                     + context.linked_history([t["id"] for t in candidates], now_local, 6))
        parts.append("The user's message may be about one of these, a different task, a new task, or nothing "
                     "task-related. Decide from what they wrote. If it's ambiguous and it matters, ask one short question.")
    else:
        parts.append("No obvious task. It may be a new task, a question, or about any task (use list_tasks).")
    return "\n\n".join(parts)


def tool_status(name: str, args: dict[str, Any], known_titles: dict[int, str] | None = None) -> dict[str, Any]:
    """
    A tool call → a small status for the chat UI ("Writing a new task: …"). Never raises.
    known_titles: titles of tasks already loaded for this turn, to skip a database lookup.
    """
    status: dict[str, Any] = {"type": "tool", "name": name}
    title = args.get("title")
    if isinstance(title, str) and title.strip():
        status["title"] = title.strip()[:120]
    if name == "add_task" and args.get("kind") == "meeting":
        status["kind"] = "meeting"
    task_id = args.get("id") if "id" in args else args.get("task_id")
    if isinstance(task_id, int) and not isinstance(task_id, bool):
        task_title = (known_titles or {}).get(task_id)
        if task_title is None:
            try:
                task = tasks.get(task_id)
            except Exception:  # only a label: never let it break the turn
                log.exception("Status lookup for task %s failed", task_id)
                task = None
            task_title = task["title"] if task else None
        if task_title:
            status["taskTitle"] = task_title
    return status


Progress = Callable[[dict[str, Any]], None]

TIMEOUT_REPLY = "That took too long, so I stopped. Anything I already changed is saved. Try again?"


@dataclass
class _Turn:
    """What the model needs for one chat turn, read from the database in one go."""
    history: list[dict[str, Any]]
    user_msg: dict[str, Any]
    checkin_ids: list[int]
    system: str
    titles: dict[int, str]
    open_kinds: dict[int, str]   # open tasks at the start of the turn: id → kind


def avoid_reason_tap(text: str, history: list[dict[str, Any]], last_proactive: dict[str, Any] | None) -> tuple[str, int] | None:
    """
    (reason, task id) when the message is a tap on Too big / Unclear / Boring under an avoided task's
    reason message: the latest assistant message (today's chat, else the last proactive one) carries
    that button and is about that task. Code records the reason, so it never depends on the model.
    """
    chip = chip_id_for_text(text)
    if chip not in AVOID_CHIPS:
        return None
    asked = next((m for m in reversed(history) if m["role"] == "assistant"), None) or last_proactive
    if asked and asked.get("task_ids") and any(c.get("id") == chip for c in asked.get("chips") or []):
        return chip, asked["task_ids"][0]
    return None


def _start_turn(text: str, now, on_saved: Callable[[dict[str, Any]], None]) -> _Turn:
    """Blocking: saves the user's message (then calls on_saved) and builds the prompt."""
    s = get_settings()
    now_local = now.astimezone(s.tz)
    start = day_start(now_local, s.day_reset_time)

    history = thread.since(start, 40)
    user_msg = thread.add("user", text)
    on_saved(user_msg)
    today = focus_day(now_local, s.day_reset_time)

    last = thread.last_proactive()
    recent = last if last and now - last["created_at"] <= timedelta(hours=s.reply_link_hours) else None
    open_tasks = tasks.list_tasks("open")
    candidate_ids = select_candidate_tasks(text, now, last, thread.recent_task_ids(start), open_tasks,
                                           s.reply_link_hours)
    reason = avoid_reason_tap(text, history, last)
    if reason:
        # A reason button: recorded here, and the task gets a fresh start (no more reason messages, no park).
        tasks.add_event(reason[1], "avoided", text.strip(), reason[0])
        tasks.reset_avoidance(reason[1])
        candidate_ids = [reason[1]] + [i for i in candidate_ids if i != reason[1]][:2]
    by_id = {t["id"]: t for t in open_tasks}
    missing = [i for i in candidate_ids if i not in by_id]
    by_id.update({t["id"]: t for t in tasks.get_many(missing)})
    candidates = [by_id[i] for i in candidate_ids if i in by_id]

    # Which playbooks (code decides): the likely tasks' stages, plus simple signals from this turn.
    facts = context.load_facts(candidates)
    last_reply = max((i for i, m in enumerate(history) if m["role"] == "assistant"), default=-1)
    signals = chat_signals(text, history[last_reply + 1:], facts.stuck_since(candidate_ids, start), now_local,
                           sum(1 for t in open_tasks if t.get("focus_date") == today))
    stages = [task_stage(t, facts.counts.get(t["id"]), today) for t in candidates]
    lang = chip_lang(text)
    system = render("system", playbooks=select_playbooks(None, stages, signals),
                    chips=[{"label": chip_label(c.id, lang)} for c in chips_for("chat_task", None, lang)],
                    now=f"{now_local:%Y-%m-%d %H:%M}", weekday=f"{now_local:%A}", tz=s.tz.key,
                    likely_topic=likely_topic(candidates, recent, now_local, facts, today))
    checkin_ids = [reason[1]] if reason else list(recent["task_ids"]) if recent else []
    return _Turn(history=history, user_msg=user_msg, checkin_ids=checkin_ids,
                 system=system, titles={i: t["title"] for i, t in by_id.items()},
                 open_kinds={t["id"]: t["kind"] for t in open_tasks})


def reply_chips(linked: list[int], ctx: ToolContext, open_kinds: dict[int, str], text: str,
                titles: dict[int, str]) -> list[dict[str, Any]]:
    """▶ Start 5 min · ✓ Done under a reply about exactly one open task (none once it's done)."""
    if len(linked) != 1 or linked[0] in ctx.closed:
        return []
    task_id = linked[0]
    if open_kinds.get(task_id, ctx.created.get(task_id)) != "task":
        return []
    lang = chip_lang(text, titles.get(task_id))
    return chips_json(chips_for("chat_task", None, lang), lang, task_id)


async def handle_user_message(text: str, on_progress: Progress | None = None) -> ChatResult:
    """
    Process one chat message. LLMUnavailable propagates (the user message is kept).

    on_progress, if given, gets {"type": "received", "userMessageId"} once the message is saved,
    then {"type": "thinking"} before each LLM call and a tool_status() before each tool runs.
    It must be thread-safe: database work runs in worker threads and reports from there.
    """
    titles: dict[int, str] = {}

    def progress(event: dict[str, Any]) -> None:
        if on_progress is None:
            return
        try:
            on_progress(tool_status(event["name"], event["args"], titles) if event["type"] == "tool" else event)
        except Exception:  # status updates are cosmetic
            log.exception("Progress callback failed")

    s = get_settings()
    now = tasks.now_utc()
    turn = await asyncio.to_thread(
        _start_turn, text, now, lambda m: progress({"type": "received", "userMessageId": m["id"]}))
    titles.update(turn.titles)
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": turn.system},
        *normalize_roles(turn.history + [{"role": "user", "content": text}]),
    ]

    ctx = ToolContext(mode="chat", now=now)
    try:
        reply = await asyncio.wait_for(
            run_tools(messages, CHAT_TOOLS, ctx, max_rounds=MAX_TOOL_ROUNDS, timeout=s.llm_timeout_s,
                      on_event=progress),
            timeout=s.chat_turn_timeout_s)
    except asyncio.TimeoutError:
        log.warning("Chat turn stopped after %ss", s.chat_turn_timeout_s)
        reply = TIMEOUT_REPLY
    finally:
        linked = link_task_ids(ctx.acted_on, turn.checkin_ids)
        await asyncio.to_thread(thread.link, [turn.user_msg["id"]], linked)
    if not reply:
        reply = "Done." if ctx.changed else "Sorry, I couldn't process that. Try again?"

    chips = reply_chips(linked, ctx, turn.open_kinds, text, titles)
    reply_msg = await asyncio.to_thread(thread.add, "assistant", reply, "chat", linked, chips)
    user_msg = {**turn.user_msg, "task_ids": linked, "task_id": linked[0] if linked else None}
    return ChatResult(reply=reply, changed=ctx.changed, user_message_id=user_msg["id"],
                      reply_message_id=reply_msg["id"], task_ids=linked,
                      user_message=user_msg, reply_message=reply_msg)
