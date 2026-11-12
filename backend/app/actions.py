"""Task actions from a notification button, a reply button (chip), the focus card, the session screen or
the task list, plus What now?.

They run without the LLM. Each one also writes a short event into the chat, so the assistant knows
what happened (and a tap counts as the user's activity, like a typed reply).

Focus sessions (F5): ▶ Start opens a session (a timer; one check-in at the end, sent by the scheduler).
At most one is open: starting another closes it first. Done, Another round and Stop record its outcome;
silence after the end check-in records `paused` (scheduler.plan_sessions).

Gentler pressure (Phase 3): Not now parks a task for a week; Move overdue to next week (the re-entry
message) and Drop them / Keep (the Sunday cleanup) act on several tasks with one tap.
"""

from datetime import datetime, timedelta
from typing import Any

from . import sessions, tasks, thread
from .config import get_settings
from .logic import (DEFAULT_MULTIPLIER, FOCUS_HOLD_MIN, PARK_DAYS, START_MIN_NOTIFICATION, actual_minutes, chip_label,
                    chip_lang, chips_for, chips_json, focus_day, later_time, meeting_occurrences, moved_to_next_week,
                    next_occurrence, pick_next, session_close_outcome, session_end_at, session_length, session_text,
                    what_now_text)

ACTIONS = ("done", "snooze", "cancel", "start", "later", "another", "stop", "not_now")
BULK_ACTIONS = ("move_overdue", "drop", "keep")   # several tasks, one tap (bulk)


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
    later: decided by code (logic.later_time), so the user never picks a time;
    not_now: parks the task for 7 days (no check-ins, out of Today's 3).
    """
    before = tasks.get(task_id)
    if before is None:
        return None
    s = get_settings()
    if before["kind"] == "fence" and action not in ("cancel",):
        return before  # a hard stop can only be cancelled (or changed in chat)
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
    elif action == "not_now":
        return park(task_id)
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
    tasks.reset_avoidance(task_id)  # started: it isn't avoided (or parked) any more
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


# ---------------------------------------------------------------- gentler pressure (F7, F8)

def _titles(rows: list[dict[str, Any]], limit: int = 5) -> str:
    shown = " · ".join(t["title"] for t in rows[:limit])
    return shown + (f" (+{len(rows) - limit})" if len(rows) > limit else "")


def park(task_id: int, days: int = PARK_DAYS, *, silent: bool = False) -> dict[str, Any] | None:
    """
    Not now: the task is parked for 7 days: no check-ins, out of Today's 3, nothing said. Its open
    session ends. silent=True: parked by the scheduler after two unanswered reason messages (no chat
    event: the user did nothing).
    """
    before = tasks.get(task_id)
    if before is None or before["status"] != "open":
        return before
    close_task_sessions(task_id, "stopped")
    s = get_settings()
    until = tasks.now_utc() + timedelta(days=days)
    task = tasks.park(task_id, until)
    if task is not None and not silent:
        thread.add_event(f"[Not now: {before['title']} — parked until {until.astimezone(s.tz):%a %d/%m}]", [task_id])
    return task


def move_overdue() -> list[dict[str, Any]]:
    """
    Move overdue to next week (the re-entry message): every overdue task gets the same weekday and time
    next week; a recurring one moves to its next occurrence. No postponed events: this is an amnesty, it
    mustn't count against the tasks. Returns the moved tasks.
    """
    s = get_settings()
    now = tasks.now_utc()
    moved = []
    for t in tasks.list_tasks("overdue"):
        if t["kind"] != "task":
            continue
        due = t["due_at"].astimezone(s.tz)
        if t["recurrence"]:
            new = next_occurrence(due, t["recurrence"], s.tz)
            while new <= now:
                new = next_occurrence(new, t["recurrence"], s.tz)
        else:
            new = moved_to_next_week(due, now)
        row = tasks.update(t["id"], due_at=new)
        if row:
            moved.append(row)
    if moved:
        n = len(moved)
        text = f"[Moved {n} overdue task{'s' if n != 1 else ''} to next week: {_titles(moved)}]"
    else:
        text = "[Nothing overdue to move]"
    thread.add_event(text, [t["id"] for t in moved])
    return moved


def drop(task_ids: list[int]) -> list[dict[str, Any]]:
    """Drop them (the Sunday cleanup): cancels exactly these tasks (those still open). Returns them."""
    dropped = []
    for t in tasks.get_many(list(dict.fromkeys(task_ids))):
        if t["status"] != "open":
            continue
        close_task_sessions(t["id"], "stopped")
        if tasks.cancel(t["id"]):
            dropped.append(t)
    thread.add_event(f"[Dropped: {_titles(dropped)}]" if dropped else "[Nothing to drop]", [t["id"] for t in dropped])
    return dropped


def keep(task_ids: list[int]) -> list[dict[str, Any]]:
    """Keep (the Sunday cleanup): nothing changes; the tap is noted so the assistant knows (silence means the same)."""
    kept = [t for t in tasks.get_many(list(dict.fromkeys(task_ids))) if t["status"] == "open"]
    thread.add_event(f"[Kept: {_titles(kept)}]" if kept else "[Kept]", [t["id"] for t in kept])
    return kept


def bulk(action: str, task_ids: list[int] | tuple = ()) -> list[dict[str, Any]]:
    """The chips that act on several tasks. Returns the tasks they touched."""
    if action == "move_overdue":
        return move_overdue()
    if action == "drop":
        return drop(list(task_ids))
    if action == "keep":
        return keep(list(task_ids))
    raise ValueError(f"Unknown action: {action}")


# ---------------------------------------------------------------- What now? (F4)

def next_fixed_event(now: datetime, hours: int = 12) -> tuple[datetime, dict[str, Any]] | None:
    """The next meeting start or fence (hard stop): What now? never picks something longer."""
    tz = get_settings().tz
    end = now + timedelta(hours=hours)
    fixed = tasks.meetings_between(now, end, ("meeting", "fence"))
    occ = [(w, m) for w, m in meeting_occurrences(fixed, now, end, tz) if w > now]
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
