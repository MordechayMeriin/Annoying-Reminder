"""
Writes the proactive messages: task check-ins (grouped), meeting reminders and the daily plans.

Code decides WHEN (scheduler.py + logic.py), WHICH playbook and WHICH reply buttons. This module gets
the words from the LLM when it's available and a fixed template when it isn't: a reminder is never
lost because of the LLM. Meeting facts (title, time, minutes left) always come from code; the LLM only
adds text around them. Everywhere a task appears, its next action leads.
"""

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

from . import context, sessions, tasks, thread
from .config import get_settings
from .llm import LLMUnavailable, chat_completion
from .llm_loop import run as run_tools
from .logic import (WEEK_WINS_DAYS, chip_label, chip_lang, chips_for, chips_json, clip, day_start, focus_day,
                    meeting_occurrences, pick_focus, select_playbooks, task_stage)
from .logic import text as fixed_text
from .prompting import render
from .tools import COACH_TOOLS, MORNING_TOOLS, ToolContext

log = logging.getLogger("coach")

PRIORITY_PREFIX = {1: "", 2: "❗ ", 3: "🚨 "}
MAX_TEXT = 1200
PUSH_TITLE_MAX = 90


@dataclass
class Composed:
    text: str
    kind: str                      # nudge | reminder | meeting
    title: str                     # push notification title
    followups: dict[int, datetime] = field(default_factory=dict)
    used_llm: bool = False
    chips: list[dict[str, Any]] = field(default_factory=list)          # reply buttons (chat)
    push_actions: list[dict[str, str]] = field(default_factory=list)   # notification buttons


# ================================================================ pure helpers

def message_kind(group: list[dict[str, Any]]) -> str:
    """
    meeting  — only meetings
    reminder — only reminders the user asked for (and meetings, and fences: the user asked for those too)
    nudge    — anything else. A mixed group counts as a nudge for every task in it: that can only
               make the per-task cap stricter, never let it be exceeded.
    """
    if all(t["kind"] == "meeting" for t in group):
        return "meeting"
    if all(t["kind"] in ("meeting", "fence") or t.get("remind_requested") for t in group):
        return "reminder"
    return "nudge"


def meeting_line(task: dict[str, Any], now: datetime) -> str:
    tz = get_settings().tz
    start = task["due_at"]
    mins = int((start - now).total_seconds() // 60)
    if mins < -2:  # e.g. held back by Shabbat / quiet hours
        when = f"started {-mins} min ago"
    else:
        when = "starting now" if mins <= 1 else f"in {mins} min"
    return f"📅 {task['title']} — {start.astimezone(tz):%H:%M} ({when})"


def fence_line(task: dict[str, Any], lang: str = "en") -> str:
    """A hard stop, written by code like a meeting line: "⛔ 17:00 — Pick up the kids. Time to stop." """
    at = f"{task['due_at'].astimezone(get_settings().tz):%H:%M}" if task.get("due_at") else ""
    return fixed_text("fence", lang, at=at, title=task["title"])


def template_line(task: dict[str, Any], now: datetime) -> str:
    """The fixed text when the LLM can't help: the next action first, no counting failures."""
    tz = get_settings().tz
    if task["kind"] == "meeting" and task.get("due_at"):
        return meeting_line(task, now)
    if task["kind"] == "fence":
        return fence_line(task)
    title = PRIORITY_PREFIX.get(task["priority"], "") + task["title"]
    when = ""
    if task.get("due_at") and task["due_at"] < now:
        when = f"overdue since {task['due_at'].astimezone(tz):%a %H:%M}"
    elif task.get("due_at"):
        when = f"due {task['due_at'].astimezone(tz):%a %H:%M}"
    if task.get("next_action"):
        line = f"▶ {task['next_action']} — {title}" + (f" ({when})" if when else "")
        return line + (f"\n  You stopped at: {task['resume_note']}" if task.get("resume_note") else "")
    if when:
        return f"{title} — {when}."
    if task.get("nag_count", 0) > 0:
        return f"{title} — still open. One small step?"
    return f"{title} — time to do this."


def fallback_text(group: list[dict[str, Any]], now: datetime) -> str:
    return "\n".join(template_line(t, now) for t in group)


def push_title(group: list[dict[str, Any]]) -> str:
    first = group[0]
    icon = {"meeting": "📅 ", "fence": "⛔ "}.get(first["kind"]) or PRIORITY_PREFIX.get(first["priority"], "")
    title = icon + first["title"]
    if first["kind"] == "task" and first.get("next_action"):
        title += f": {first['next_action']}"
    title = clip(title, PUSH_TITLE_MAX)
    return title if len(group) == 1 else f"{title} (+{len(group) - 1})"


def push_actions(group: list[dict[str, Any]], lang: str) -> list[dict[str, str]]:
    """▶ Start 5 min and ✓ Done on the notification, for a check-in about exactly one task."""
    if len(group) != 1 or group[0]["kind"] != "task":
        return []
    return [{"action": "start", "title": chip_label("start5", lang)},
            {"action": "done", "title": chip_label("done", lang)}]


def checkin_chips(group: list[dict[str, Any]], stage: str | None, lang: str,
                  avoided: bool = False) -> list[dict[str, Any]]:
    """
    Chips about the message's first task (the most important one); none for meetings and fences only.
    avoided: the reason message gets Too big · Unclear · Boring · Not now instead.
    """
    first = next((t for t in group if t["kind"] == "task"), None)
    if first is None:
        return []
    return chips_json(chips_for("avoided" if avoided else "checkin", stage, lang), lang, first["id"])


# ================================================================ task check-ins

# The reason message about an avoided task (F7): sent once, instead of more check-ins.
AVOID_ASK = ("This task keeps getting postponed or ignored, so this is NOT an ordinary check-in. Write one "
             "short, kind line: say plainly that it keeps slipping and that's fine, and ask what's in the way. "
             "Don't name a next action, don't push, don't count how often. The reply buttons carry the answers.")


@dataclass
class _CheckinPrep:
    user: str
    chips: list[dict[str, Any]]
    playbooks: list[str]
    lang: str


async def compose(group: list[dict[str, Any]], now: datetime,
                  contacts_today: dict[int, int] | None = None, avoided: bool = False) -> Composed:
    """
    One message for everything due in this tick (meetings and fences first, as ordered by the planner).
    avoided: the group is one avoided task and this is its reason message, not an ordinary check-in:
    one kind line, with Too big · Unclear · Boring · Not now (scheduler.plan_tick decides).
    Fences are written by code alone (no LLM call for a message with only fences).
    """
    s = get_settings()
    meetings = [t for t in group if t["kind"] == "meeting" and t.get("due_at")]
    fences = [t for t in group if t["kind"] == "fence"]
    others = [t for t in group if t not in meetings and t not in fences]
    kind, title = message_kind(group), push_title(group)
    first_task = next((t for t in group if t["kind"] == "task"), None)
    lang = chip_lang(None, (first_task or group[0])["title"])
    chips = checkin_chips(group, None, lang, avoided)

    body, followups, used = "", {}, False
    if meetings or others:
        try:
            prep = await asyncio.to_thread(_checkin_prep, group, now, contacts_today or {}, avoided)
            chips, lang = prep.chips, prep.lang
            ctx = ToolContext(mode="coach", now=now)
            body = await asyncio.wait_for(_llm_body(group, now, prep, ctx), timeout=s.llm_timeout_s)
            followups, used = dict(ctx.followups), bool(body.strip())
        except (LLMUnavailable, asyncio.TimeoutError) as e:
            log.warning("Check-in falls back to template: %s", e or type(e).__name__)
        except Exception:
            log.exception("Check-in LLM path failed; using template")

    if not used:
        followups = {}
        if avoided and first_task:
            body = fixed_text("avoid_ask", lang, title=first_task["title"])
        else:
            body = "\n".join(template_line(t, now) for t in others)
    if avoided:
        followups = {}  # the next look at an avoided task is decided by code (3 days)
    head = "\n".join([meeting_line(m, now) for m in meetings] + [fence_line(f, lang) for f in fences])
    text = clip("\n".join(x for x in (head, body.strip()) if x), MAX_TEXT)
    return Composed(text=text, kind=kind, title=title, followups=followups, used_llm=used, chips=chips,
                    push_actions=push_actions(group, lang))


async def _llm_body(group: list[dict[str, Any]], now: datetime, prep: _CheckinPrep, ctx: ToolContext) -> str:
    s = get_settings()
    now_local = now.astimezone(s.tz)
    messages = [
        {"role": "system", "content": render("checkin", playbooks=prep.playbooks, chips=prep.chips,
                                             now=f"{now_local:%Y-%m-%d %H:%M}", weekday=f"{now_local:%A}")},
        {"role": "user", "content": prep.user},
    ]
    has_tasks = any(t["kind"] == "task" for t in group)
    return await run_tools(messages, COACH_TOOLS if has_tasks else [], ctx, max_rounds=3,
                           timeout=s.llm_timeout_s)


def _checkin_prep(group: list[dict[str, Any]], now: datetime, contacts_today: dict[int, int],
                  avoided: bool = False) -> _CheckinPrep:
    """Blocking: the check-in prompt's facts, its playbooks and reply buttons, read from the database."""
    s = get_settings()
    now_local = now.astimezone(s.tz)
    start = day_start(now_local, s.day_reset_time)
    today = focus_day(now_local, s.day_reset_time)
    ids = [t["id"] for t in group]
    facts = context.load_facts(group)
    stages = {t["id"]: task_stage(t, facts.counts.get(t["id"]), today) for t in group if t["kind"] == "task"}
    first_task = next((t for t in group if t["kind"] == "task"), None)
    lang = chip_lang(thread.last_user_text(), first_task["title"] if first_task else None)
    chips = checkin_chips(group, stages.get(first_task["id"]) if first_task else None, lang, avoided)
    if avoided:
        playbooks = ["avoiding"]
    else:
        playbooks = select_playbooks(None, stages.values(), {"stuck"} if facts.stuck_since(ids, start) else ())

    blocks = []
    for t, block in zip(group, context.task_contexts(group, now_local, contacts_today, facts, today)):
        if t["kind"] == "meeting" and t.get("due_at"):
            block += f"\n  (Shown above your text by the system: {meeting_line(t, now)})"
        elif t["kind"] == "fence":
            block += f"\n  (A hard stop. Shown above your text by the system: {fence_line(t, lang)})"
        elif t.get("remind_requested"):
            block += "\n  (The user asked to be reminded now.)"
        blocks.append(block)

    today_msgs = thread.since(start, 8)
    open_today = tasks.list_tasks("today")
    user = "\n\n".join([
        "Due for a check-in now:\n" + "\n".join(blocks),
        "Earlier messages about these tasks:\n" + context.linked_history(ids, now_local),
        "Today's conversation so far (latest last):\n"
        + (context.format_messages(today_msgs, now_local) or "(nothing yet)"),
        "Other open items today:\n" + context.format_open_items(open_today, now_local, set(ids)),
        AVOID_ASK if avoided else "Write the check-in message now.",
    ])
    return _CheckinPrep(user=user, chips=chips, playbooks=playbooks, lang=lang)


# ================================================================ daily jobs

JOB_PROMPTS = {"morning": "daily_morning", "checkin": "daily_checkin", "evening": "daily_evening"}
JOB_TITLES = {"morning": "☀️ Morning plan", "checkin": "⏰ Check-in", "evening": "🌙 Evening review",
              "reentry": "👋 Good to see you", "meeting": "📅 This week's meetings"}
JOB_CHIPS = {"morning": "morning", "checkin": "midday", "evening": "evening"}


@dataclass
class Daily:
    text: str
    chips: list[dict[str, Any]] = field(default_factory=list)
    task_ids: list[int] = field(default_factory=list)   # the morning plan is linked to Today's 3
    used_llm: bool = False
    kind: str | None = None        # the message kind when it isn't the job's name ('reentry', 'meeting')


@dataclass
class _DailyFacts:
    snapshot: str
    open_count: int
    done_count: int                       # finished today (steps and recurring tasks too)
    sessions: tuple[int, int]             # focus sessions today: (count, minutes)
    today_msgs: list[dict[str, Any]]
    lang: str
    focus: list[dict[str, Any]]           # Today's 3 (already set: mid-day / evening)
    focus_done: set[int]
    suggested: list[dict[str, Any]]       # morning: the code's pick for Today's 3
    meetings: list[tuple[datetime, dict[str, Any]]]
    stale: list[dict[str, Any]] = field(default_factory=list)   # Sunday cleanup: old tasks to drop or keep
    week_wins: int = 0                                          # Sunday: things finished in the last 7 days


def _focus_lines(focus: list[dict[str, Any]], done: set[int]) -> str:
    return "\n".join(f"{i}. [{'x' if t['id'] in done else ' '}] #{t['id']} {t['title']}"
                     + (f" → {t['next_action']}" if t.get("next_action") else "")
                     for i, t in enumerate(focus, 1))


def _daily_facts(job: str, now: datetime, sunday: bool = False) -> _DailyFacts:
    """Blocking: the task snapshot, Today's 3, meetings and today's messages (sunday: + the cleanup facts)."""
    s = get_settings()
    now_local = now.astimezone(s.tz)
    today_d = focus_day(now_local, s.day_reset_time)
    today = tasks.list_tasks("today")
    parts = []
    if today:
        shown = today[:12]
        counts = tasks.event_counts_many([t["id"] for t in shown], 7)
        parts.append("Open for today (incl. overdue):\n" + "\n".join(
            context.format_task(t, now_local, counts=counts.get(t["id"], {}), today=today_d) for t in shown))
    since = day_start(now_local, s.day_reset_time)
    done = tasks.done_since(since)
    n_sessions, session_min = sessions.stats_since(since, now)
    parts.append("Done today (steps count too):\n"
                 + ("\n".join(f"- {t['title']}" + (" (a step)" if t.get("parent_id") else "") for t in done)
                    or "(nothing yet)"))
    if n_sessions:
        parts.append(f"Focus sessions today: {n_sessions} ({session_min} min)")
    upcoming = tasks.list_tasks("upcoming", 7)
    if upcoming:
        parts.append("Next 7 days:\n" + context.format_open_items(upcoming, now_local, set()))
    open_all = tasks.list_tasks("open")
    undated = [t for t in open_all if t["due_at"] is None and t["parent_id"] is None and t["kind"] == "task"]
    if undated:
        parts.append("Open without a date:\n" + context.format_open_items(undated, now_local, set()))

    focus, focus_done, suggested, meetings = [], set(), [], []
    midnight = now_local.replace(hour=0, minute=0, second=0, microsecond=0)
    d = (midnight + timedelta(days=1)).date()
    end_of_day = datetime(d.year, d.month, d.day, tzinfo=s.tz)
    if job == "morning":
        by_id = {t["id"]: t for t in open_all}
        suggested = [by_id[i] for i in pick_focus(open_all, now_local)]
        meetings = meeting_occurrences(tasks.meetings_between(now, end_of_day), now, end_of_day, s.tz)
        parts.append("Suggested Today's 3 (the code's pick: overdue, then due today, then priority):\n"
                     + (_focus_lines(suggested, set()) or "(no open tasks)"))
        parts.append("Meetings today:\n" + ("\n".join(f"{w.astimezone(s.tz):%H:%M} {m['title']}"
                                                      for w, m in meetings) or "(none)"))
    else:
        focus, focus_done = tasks.focus_today(today_d)
        if focus:
            parts.append("Today's 3 ([x] = done):\n" + _focus_lines(focus, focus_done))
    today_msgs = thread.since(day_start(now_local, s.day_reset_time), 12)
    titles = " ".join(t["title"] for t in (today or open_all)[:5])
    lang = chip_lang(thread.last_user_text(), titles)
    stale, week_wins = [], 0
    if sunday and job == "morning":
        picked = {t["id"] for t in suggested}
        stale = [t for t in tasks.stale_tasks() if t["id"] not in picked]  # not "do it today" and "drop it?" at once
        week_wins = len(tasks.done_since(now - timedelta(days=WEEK_WINS_DAYS)))
    return _DailyFacts(snapshot="\n\n".join(parts), open_count=len(today), done_count=len(done),
                       sessions=(n_sessions, session_min), today_msgs=today_msgs, lang=lang,
                       focus=focus, focus_done=focus_done, suggested=suggested, meetings=meetings,
                       stale=stale, week_wins=week_wins)


async def compose_daily(job: str, now: datetime, sunday: bool = False) -> Daily | None:
    """
    The daily message, or None if there's nothing worth saying (an empty mid-day check-in).
    sunday: the first morning of the week also carries last week's wins and the cleanup (F8).
    """
    s = get_settings()
    now_local = now.astimezone(s.tz)
    f = await asyncio.to_thread(_daily_facts, job, now, sunday)
    if job == "checkin" and f.open_count == 0 and not (set(t["id"] for t in f.focus) - f.focus_done):
        return None
    if job == "morning":
        return await _compose_morning(f, now, sunday)

    first_open = next((t for t in f.focus if t["id"] not in f.focus_done), None)
    chips = chips_json(chips_for(JOB_CHIPS[job], None, f.lang), f.lang, first_open["id"] if first_open else None)
    messages = [
        {"role": "system", "content": render(JOB_PROMPTS[job], chips=chips, now=f"{now_local:%Y-%m-%d %H:%M}",
                                             weekday=f"{now_local:%A}")},
        {"role": "user", "content": f.snapshot + "\n\nToday's conversation so far:\n"
                                    + (context.format_messages(f.today_msgs, now_local) or "(nothing yet)")},
    ]
    try:
        msg = await chat_completion(messages, temperature=0.5)
        text = (msg.get("content") or "").strip()
        if text:
            return Daily(clip(text, MAX_TEXT), chips, used_llm=True)
    except LLMUnavailable as e:
        log.warning("Daily job %s falls back to template: %s", job, e or type(e).__name__)
    except Exception:
        log.exception("Daily job %s failed; using template", job)
    if job == "checkin" and first_open:
        step = first_open.get("next_action") or first_open["title"]
        return Daily(f"Next from Today's 3: ▶ {step} ({first_open['title']})", chips)
    if job == "evening":
        return Daily(evening_template(f), chips)
    if f.open_count == 0:
        return Daily("Nothing open for today." if job != "evening" else "Nothing left open today. 👍", chips)
    return Daily(f"{f.open_count} open item(s) for today. Open the app to see them.", chips)


def evening_template(f: _DailyFacts) -> str:
    """The evening review without the LLM: wins first (steps and sessions count), then what carries over."""
    n, minutes = f.sessions
    wins = f"Done today: {f.done_count}" + (f" · {n} focus session{'s' if n != 1 else ''} ({minutes} min)" if n else "")
    rest = f"{f.open_count} open item(s) carry over to tomorrow." if f.open_count else "Nothing left open today."
    return f"🌙 {wins}. {rest}"


def morning_template(focus: list[dict[str, Any]], meetings: list[tuple[datetime, dict[str, Any]]]) -> str:
    """The morning plan without the LLM: Today's 3 (with the first step of #1), then meetings."""
    tz = get_settings().tz
    lines = ["☀️ Today's 3:" if focus else "☀️ Nothing open for today."]
    for i, t in enumerate(focus, 1):
        line = f"{i}. {t['title']}"
        if i == 1 and t.get("next_action"):
            line += f" — first step: {t['next_action']}"
        lines.append(line)
    if meetings:
        lines.append("📅 Meetings:")
        lines += [f"{w.astimezone(tz):%H:%M} {m['title']}" for w, m in meetings]
    return "\n".join(lines)


def sunday_lines(f: _DailyFacts, focus_ids: list[int]) -> tuple[str, list[int]]:
    """
    The Sunday additions, written by code so the buttons act on exactly what's shown: one line of last
    week's wins, and up to three old tasks to drop or keep. Returns (text, the ids Drop them acts on).
    """
    lines = []
    if f.week_wins:
        lines.append(fixed_text("wins_week_1" if f.week_wins == 1 else "wins_week", f.lang, n=f.week_wins))
    stale = [t for t in f.stale if t["id"] not in focus_ids]
    if stale:
        lines.append(fixed_text("stale", f.lang, titles=" · ".join(t["title"] for t in stale)))
    return "\n".join(lines), [t["id"] for t in stale]


async def _compose_morning(f: _DailyFacts, now: datetime, sunday: bool = False) -> Daily:
    """
    The morning plan proposes Today's 3 and saves it right away (silence accepts it). The LLM picks
    with set_focus; if it doesn't (or can't), the code's pick stands.
    """
    s = get_settings()
    now_local = now.astimezone(s.tz)
    suggested_ids = [t["id"] for t in f.suggested]
    # The chips name "the first one": as suggested, until the LLM changes the pick.
    preview = chips_json(chips_for("morning", None, f.lang), f.lang, suggested_ids[0] if suggested_ids else None)
    messages = [
        {"role": "system", "content": render("daily_morning", chips=preview,
                                             playbooks=select_playbooks("morning", signals={"sunday"} if sunday else ()),
                                             now=f"{now_local:%Y-%m-%d %H:%M}", weekday=f"{now_local:%A}")},
        {"role": "user", "content": f.snapshot + "\n\nToday's conversation so far:\n"
                                    + (context.format_messages(f.today_msgs, now_local) or "(nothing yet)")},
    ]
    ctx = ToolContext(mode="morning", now=now)
    text, used = "", False
    if suggested_ids:
        try:
            text = await asyncio.wait_for(
                run_tools(messages, MORNING_TOOLS, ctx, max_rounds=3, temperature=0.5, timeout=s.llm_timeout_s),
                timeout=s.llm_timeout_s * 2)
            used = bool(text.strip())
        except (LLMUnavailable, asyncio.TimeoutError) as e:
            log.warning("Morning plan falls back to template: %s", e or type(e).__name__)
        except Exception:
            log.exception("Morning plan LLM path failed; using template")

    if ctx.focus:
        focus_ids = ctx.focus
        by_id = {t["id"]: t for t in await asyncio.to_thread(tasks.get_many, focus_ids)}
        focus = [by_id[i] for i in focus_ids if i in by_id]
    else:
        focus = await asyncio.to_thread(tasks.set_focus, suggested_ids, focus_day(now_local, s.day_reset_time)) \
            if suggested_ids else []
    ids = [t["id"] for t in focus]
    chips = chips_json(chips_for("morning", None, f.lang), f.lang, ids[0] if ids else None) if ids else []
    text = clip(text.strip(), MAX_TEXT) if used else morning_template(focus, f.meetings)
    if sunday:
        extra, stale_ids = sunday_lines(f, ids)
        if extra:
            text += "\n\n" + extra
        if stale_ids:
            chips = chips + chips_json(chips_for("sunday", None, f.lang), f.lang, None, stale_ids)
            await asyncio.to_thread(tasks.mark_stale_offered, stale_ids)
    return Daily(text, chips, ids, used_llm=used)


# ================================================================ re-entry after a lapse (F7)

def _reentry_facts(now: datetime) -> tuple[str, bool, list[dict[str, Any]]]:
    """Blocking: (language, is anything overdue, a few open tasks for the LLM's eyes only)."""
    open_all = [t for t in tasks.list_tasks("open") if t["kind"] == "task" and not t.get("parent_id")]
    lang = chip_lang(thread.last_user_text(), " ".join(t["title"] for t in open_all[:5]))
    overdue = any(t["due_at"] is not None and t["due_at"] < now for t in open_all)
    return lang, overdue, open_all[:5]


async def compose_reentry(now: datetime) -> Daily:
    """
    The morning message after a lapse (48 hours with no chat or tap): no plan, no counts, no overdue
    list. "Good to see you. Want me to pick one thing for today?" with Pick one for me · Move overdue to
    next week (the second only if something is overdue). The LLM writes it; the template never fails.
    """
    s = get_settings()
    now_local = now.astimezone(s.tz)
    lang, overdue, some = await asyncio.to_thread(_reentry_facts, now)
    chips = [c for c in chips_json(chips_for("reentry", None, lang), lang, None)
             if overdue or c["id"] != "move_overdue"]
    messages = [
        {"role": "system", "content": render("daily_reentry", playbooks=select_playbooks("reentry"), chips=chips,
                                             now=f"{now_local:%Y-%m-%d %H:%M}", weekday=f"{now_local:%A}")},
        {"role": "user", "content": "The user has been away for a few days. Some of their open tasks, only so "
                                    "you know their language (don't list, name or count them):\n"
                                    + ("\n".join(f"- {t['title']}" for t in some) or "(none)")},
    ]
    try:
        msg = await chat_completion(messages, temperature=0.5)
        text = (msg.get("content") or "").strip()
        if text:
            return Daily(clip(text, 300), chips, used_llm=True, kind="reentry")
    except LLMUnavailable as e:
        log.warning("Re-entry message falls back to template: %s", e or type(e).__name__)
    except Exception:
        log.exception("Re-entry message failed; using template")
    return Daily(fixed_text("reentry", lang), chips, kind="reentry")


# ================================================================ meeting digests
#
# Extra meeting reminders on top of the ones before / at the start of each meeting:
#   week_meetings — at the start of the week: every meeting in the coming 7 days
#   meetings_eve  — in the evening: every meeting tomorrow
# Built from code only (meeting facts never come from the LLM). Nothing is sent when there are none.

MEETING_JOBS = ("week_meetings", "meetings_eve")
MEETING_JOB_TITLES = {"week_meetings": "📅 This week's meetings", "meetings_eve": "📅 Tomorrow's meetings"}


@dataclass
class Digest:
    text: str
    title: str
    task_ids: list[int]


def meeting_digest(job: str, now: datetime) -> Digest | None:
    tz = get_settings().tz
    now_local = now.astimezone(tz)
    midnight = now_local.replace(hour=0, minute=0, second=0, microsecond=0)
    if job == "week_meetings":
        start, end = now_local, _local_midnight(midnight, 7, tz)
    elif job == "meetings_eve":
        start, end = _local_midnight(midnight, 1, tz), _local_midnight(midnight, 2, tz)
    else:
        raise ValueError(f"Unknown meeting job: {job}")

    occurrences = meeting_occurrences(tasks.meetings_between(start, end), start, end, tz)
    if not occurrences:
        return None
    if job == "week_meetings":
        today = now_local.date()
        lines = [f"{_day_label(w.astimezone(tz), today)} {w.astimezone(tz):%H:%M} — {m['title']}"
                 for w, m in occurrences]
        head = "📅 Your meetings this week:"
    else:
        lines = [f"{w.astimezone(tz):%H:%M} — {m['title']}" for w, m in occurrences]
        head = f"📅 Tomorrow ({start:%a %d/%m}):"
    text = clip("\n".join([head, *lines]), MAX_TEXT)
    ids = list(dict.fromkeys(m["id"] for _, m in occurrences))
    return Digest(text=text, title=MEETING_JOB_TITLES[job], task_ids=ids)


def _local_midnight(midnight: datetime, days: int, tz) -> datetime:
    """Local midnight `days` later (re-localised, so a DST change doesn't shift it by an hour)."""
    d = (midnight + timedelta(days=days)).date()
    return datetime(d.year, d.month, d.day, tzinfo=tz)


def _day_label(when: datetime, today) -> str:
    if when.date() == today:
        return "Today"
    if when.date() == today + timedelta(days=1):
        return "Tomorrow"
    return f"{when:%a %d/%m}"
