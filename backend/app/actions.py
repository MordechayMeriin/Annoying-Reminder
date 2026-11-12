"""Task actions from a notification button, a reply button (chip), the focus card, the session screen or
the task list, plus What now?.

They run without the LLM. Each one also writes a short event into the chat, so the assistant knows
what happened (and a tap counts as the user's activity, like a typed reply).

Focus sessions (F5): ▶ Start opens a session (a timer; one check-in at the end, sent by the scheduler).
At most one is open: starting another closes it first. Done, Another round and Stop record its outcome;
silence after the end check-in records `paused` (scheduler.plan_sessions).
"""

from datetime import datetime, timedelta
from typing import Any

from . import sessions, tasks, thread
from .config import get_settings
from .logic import (DEFAULT_MULTIPLIER, FOCUS_HOLD_MIN, START_MIN_NOTIFICATION, actual_minutes, chip_label, chip_lang,
                    chips_for, chips_json, focus_day, later_time, meeting_occurrences, pick_next,
                    session_close_outcome, session_end_at, session_length, session_text, what_now_text)

ACTIONS = ("done", "snooze", "cancel", "start", "later", "another", "stop")


def _fmt_minutes(minutes: int) -> str:
    if minutes % (24 * 60) == 0:
        d = minutes // (24 * 60)
        return f"{d} day" + ("s" if d != 1 else "")
    if minutes % 60 == 0:
        return f"{minutes // 60}h"
    return f"{minutes} min"


def apply(task_id: int, action: str, minutes: int | None = None) -> dict[str, Any] | None:
    """
    done / cancel (also closes the task's open session); snooze `minutes` (default 60);
    start: a focus session of `minutes` (default 5) on the next action, with a check-in when it's up;
    another: a new session as long as the task's last one; stop: ends the task's open session;
    later: decided by code (logic.later_time), so the user never picks a time.
    """
    before = tasks.get(task_id)
    if before is None:
        return None
    s = get_settings()
    if action == "done":
        close_task_sessions(task_id, "done")
        task = tasks.complete(task_id)
        recurring = bool(before["recurrence"] and before["due_at"])
        text = f"[Marked done: {before['title']}" + (" — next one scheduled]" if recurring else "]")
    elif action == "snooze":
        minutes = minutes or 60
        task = tasks.snooze(task_id, minutes)
        text = f"[Snoozed {_fmt_minutes(minutes)}: {before['title']}]"
    elif action == "cancel":
        close_task_sessions(task_id, "stopped")
        task = tasks.cancel(task_id)
        text = f"[Cancelled: {before['title']}]"
    elif action in ("start", "another"):
        session = start_session(task_id, minutes, another=action == "another")
        return tasks.get(task_id) if session else None
    elif action == "stop":
        return stop_session(task_id)
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


# ---------------------------------------------------------------- focus sessions

def close_session(session: dict[str, Any], outcome: str, ended_at: datetime | None = None) -> dict[str, Any] | None:
    """Record an outcome (with a session_ended event). None if it was already closed."""
    closed = sessions.end(session["id"], outcome, ended_at)
    if closed is not None:
        mins = actual_minutes(closed, tasks.now_utc())
        tasks.add_event(closed["task_id"], "paused" if outcome == "paused" else "session_ended",
                        f"{outcome}, {mins} of {closed['planned_min']} min")
    return closed


def close_task_sessions(task_id: int, outcome: str) -> None:
    """The task is done or cancelled: its open session ends with it (the timer stops)."""
    for open_ in sessions.open_sessions():
        if open_["task_id"] == task_id:
            close_session(open_, outcome)


def start_session(task_id: int, minutes: int | None = None, *, another: bool = False) -> dict[str, Any] | None:
    """
    ▶ Start (another=True: ▶ Another round, as long as the task's last session). Opens the session and
    writes "[▶ 25 min: next action]" in the chat. Any other open session is closed first. The task's own
    check-in that would fall inside the session (+ the focus hold) is dropped: the end check-in replaces it.
    Returns the session, or None if the task isn't open.
    """
    task = tasks.get(task_id)
    if task is None or task["status"] != "open":
        return None
    now = tasks.now_utc()
    last = sessions.latest_for_task(task_id) if another else None
    for open_ in sessions.open_sessions():
        if another and last and open_["id"] == last["id"]:
            close_session(open_, "another")
        else:
            outcome, ended_at = session_close_outcome(open_, now)
            close_session(open_, outcome, ended_at)
    minutes = session_length(minutes or (last["planned_min"] if last else None), START_MIN_NOTIFICATION)
    session = sessions.start(task_id, minutes)
    tasks.add_event(task_id, "session_started", f"{minutes} min" + (" (another round)" if another else ""))
    if task.get("remind_at") and task["remind_at"] <= session_end_at(session) + timedelta(minutes=FOCUS_HOLD_MIN):
        tasks.update(task_id, remind_at=None)
    head = "▶ Another round, " if another else "▶ "
    step = task.get("next_action") or task["title"]
    thread.add_event(f"[{head}{minutes} min: {step}" + (f" ({task['title']})]" if step != task["title"] else "]"),
                     [task_id])
    return session


def stop_session(task_id: int) -> dict[str, Any] | None:
    """Stop: the task's open session ends as `stopped` (the app then offers "Where did you stop?")."""
    task = tasks.get(task_id)
    if task is None:
        return None
    open_ = next((x for x in sessions.open_sessions() if x["task_id"] == task_id), None)
    closed = close_session(open_, "stopped") if open_ else None
    if closed:
        text = f"[Stopped after {actual_minutes(closed, tasks.now_utc())} min: {task['title']}]"
    else:
        text = f"[Stopped: {task['title']}]"
    thread.add_event(text, [task_id])
    return task


def end_session(session_id: int, outcome: str) -> dict[str, Any] | None:
    """From the session screen: done / another / stopped. Returns the session that's open afterwards (another), or the closed one."""
    session = sessions.get(session_id)
    if session is None:
        return None
    if outcome == "done":
        apply(session["task_id"], "done")
    elif outcome == "another":
        return start_session(session["task_id"], session["planned_min"], another=True)
    elif outcome == "stopped":
        stop_session(session["task_id"])
    else:
        raise ValueError(f"Unknown outcome: {outcome}")
    return sessions.get(session_id)


def set_session_length(session_id: int, minutes: int) -> dict[str, Any] | None:
    """5 / 15 / 25 / 45, or +5 on the session screen. Only an open session."""
    return sessions.set_planned(session_id, session_length(minutes))


def save_breadcrumb(session_id: int, text: str | None) -> dict[str, Any] | None:
    """
    "Where did you stop?" (optional): saves the line as the task's resume note, shown on the next session
    screen and used by the next check-in. text=None just dismisses the field. Either way it isn't offered again.
    """
    session = sessions.get(session_id)
    if session is None:
        return None
    sessions.close_note(session_id)
    text = (text or "").strip()
    if not text:
        return tasks.get(session["task_id"])
    task = tasks.update(session["task_id"], resume_note=text)
    if task:
        thread.add_event(f"[Where I stopped: {text}]", [task["id"]])
    return task


def current_session() -> tuple[dict[str, Any] | None, dict[str, Any] | None]:
    """The open session and its task (or None, None)."""
    open_ = sessions.open_sessions()
    if not open_:
        return None, None
    session = open_[-1]
    return session, tasks.get(session["task_id"])


# ---------------------------------------------------------------- What now? (F4)

def next_fixed_event(now: datetime, hours: int = 12) -> tuple[datetime, dict[str, Any]] | None:
    """The next meeting start (fences join in Phase 3): What now? never picks something longer."""
    tz = get_settings().tz
    end = now + timedelta(hours=hours)
    occ = [(w, m) for w, m in meeting_occurrences(tasks.meetings_between(now, end), now, end, tz) if w > now]
    return occ[0] if occ else None


def what_now(low_energy: bool = False, exclude: list[int] | tuple = (), chip: str = "what_now") -> list[dict[str, Any]]:
    """
    One task to do now, decided by code in one step (logic.pick_next), no LLM: an event for the tap
    ("[What now?]") and the answer, with ▶ Start · Low energy · Something else. Returns both messages.
    """
    s = get_settings()
    now = tasks.now_utc()
    now_local = now.astimezone(s.tz)
    today = focus_day(now_local, s.day_reset_time)
    open_tasks = tasks.list_tasks("open")
    _, done = tasks.focus_today(today)
    fixed = next_fixed_event(now)
    pick = pick_next(open_tasks, now, fixed[0] if fixed else None, DEFAULT_MULTIPLIER, low_energy,
                     today=today, done_ids=done, exclude=exclude)
    lang = chip_lang(thread.last_user_text(), " ".join(t["title"] for t in open_tasks[:5]))
    event = thread.add_event(f"[{chip_label(chip, lang)}]")

    text = what_now_text(pick, lang, fixed[0].astimezone(s.tz) if fixed else None)
    chips: list[dict[str, Any]] = []
    task_ids: list[int] = []
    if pick is not None and pick.task is not None:
        task_ids = [pick.task["id"]]
        offered = list(dict.fromkeys([*exclude, pick.task["id"]]))
        for c in chips_json(chips_for("what_now", None, lang), lang, pick.task["id"]):
            if c["id"] == "start":
                c["minutes"] = pick.minutes
            elif c["id"] == "low_energy":
                if low_energy or pick.reason == "bonus":
                    continue
                c["low_energy"] = True
            elif c["id"] == "something_else":
                c.update(exclude=offered, low_energy=low_energy)
            chips.append(c)
    reply = thread.add("assistant", text, "chat", task_ids, chips)
    return [event, reply]


def session_end_message(session: dict[str, Any], task: dict[str, Any]) -> tuple[str, list[dict[str, Any]], str]:
    """(text, chips, lang) of the check-in at the end of a session: a template, no LLM."""
    lang = chip_lang(thread.last_user_text(), task["title"])
    text = session_text("session_end", task, session["planned_min"], lang)
    return text, chips_json(chips_for("session_end", None, lang), lang, task["id"]), lang
