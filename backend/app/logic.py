"""Pure scheduling logic (no I/O) — easy to unit-test."""

import calendar
import re
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from typing import Any, Callable, Iterable
from zoneinfo import ZoneInfo


def in_quiet_hours(now_local: datetime, start: time, end: time) -> bool:
    """Quiet window may cross midnight (e.g. 23:00 → 07:00)."""
    t = now_local.time()
    if start <= end:
        return start <= t < end
    return t >= start or t < end


def quiet_end_after(now_local: datetime, end: time) -> datetime:
    """Next occurrence of the quiet-hours end time, in local time."""
    candidate = now_local.replace(hour=end.hour, minute=end.minute, second=0, microsecond=0)
    if candidate <= now_local:
        candidate += timedelta(days=1)
    return candidate


def job_is_due(now_local: datetime, scheduled: time, window_hours: float = 3.0) -> bool:
    """A daily job fires at/after its time, but not if we're far past it (e.g. service was down)."""
    start = now_local.replace(hour=scheduled.hour, minute=scheduled.minute, second=0, microsecond=0)
    return start <= now_local < start + timedelta(hours=window_hours)


def next_reminder_after_nag(
    now: datetime, nag_count: int, priority: int, interval_min: int, max_nags: int
) -> datetime | None:
    """
    When to check in next after a reminder was just sent (nag_count already incremented).
    None = stop nagging. Higher priority nags more often.
    """
    if nag_count >= max_nags:
        return None
    interval = interval_min
    if priority == 2:
        interval = max(10, interval_min * 2 // 3)
    elif priority >= 3:
        interval = max(5, interval_min // 2)
    return now + timedelta(minutes=interval)


# ================================================================ how loud (Pushover priority)
#
# NORMAL  → Pushover priority 0, the normal sound
# URGENT  → priority 1: the urgent sound, highlighted, ignores the Pushover app's own quiet hours
# ALARM   → priority 2: repeats every few minutes until acknowledged (or cancelled by the app)
# The guardrails (quiet hours, Shabbat, gaps, caps) decide WHETHER to send; this only decides how loud.

NORMAL, URGENT, ALARM = 0, 1, 2

_ALARM_MIN_PRIORITY = {"critical": 3, "important": 2, "all": 1}


@dataclass(frozen=True)
class AlertPolicy:
    urgent_after: int = 1          # ignored check-ins before they turn urgent
    alarm_for: str = "important"   # critical | important | all | off  (by task priority)
    alarm_after: int = 2           # ignored check-ins before the next one is an alarm
    max_nags: int = 4              # check-ins per task before nagging stops


def alert_level(task: dict[str, Any], ignored: int, p: AlertPolicy) -> int:
    """
    How loud a contact about one task should be.
    ignored: check-ins about it in a row that got no reply.
      ALARM  — a task of an alarm-worthy priority, ignored `alarm_after` times; if nagging would stop
               before that, the last check-in is the alarm (only when all the earlier ones were ignored)
      URGENT — meetings, reminders the user asked for, important/critical tasks, or ignored
               `urgent_after` times
      NORMAL — everything else (first check-ins about ordinary tasks)
    """
    min_prio = _ALARM_MIN_PRIORITY.get(p.alarm_for)
    if (task["kind"] != "meeting" and min_prio is not None and task["priority"] >= min_prio
            and ignored >= min(p.alarm_after, max(p.max_nags - 1, 0))):
        return ALARM
    if (task["kind"] == "meeting" or task.get("remind_requested") or task["priority"] >= 2
            or ignored >= p.urgent_after):
        return URGENT
    return NORMAL


def next_time_at(now_local: datetime, t: time) -> datetime:
    """Next occurrence of a wall-clock time, strictly after now."""
    candidate = now_local.replace(hour=t.hour, minute=t.minute, second=0, microsecond=0)
    if candidate <= now_local:
        candidate += timedelta(days=1)
    return candidate


def alarm_window_s(now_local: datetime, quiet_start: time, next_rest_start: datetime | None,
                   max_s: int = 3600) -> int:
    """Seconds an alarm may keep repeating: up to max_s, but never into quiet hours or Shabbat."""
    end = now_local + timedelta(seconds=max_s)
    end = min(end, next_time_at(now_local, quiet_start))
    if next_rest_start is not None and next_rest_start > now_local:
        end = min(end, next_rest_start)
    return max(0, int((end - now_local).total_seconds()))


def _add_month(d: date) -> date:
    year = d.year + (d.month // 12)
    month = d.month % 12 + 1
    day = min(d.day, calendar.monthrange(year, month)[1])
    return date(year, month, day)


def next_occurrence(current: datetime, recurrence: str, tz: ZoneInfo) -> datetime:
    """Next due time for a recurring task, keeping the same local wall-clock time."""
    local = current.astimezone(tz)
    d = local.date()
    if recurrence == "daily":
        d += timedelta(days=1)
    elif recurrence == "weekdays":
        # Israeli work week: Sunday–Thursday (Python: Mon=0 … Sun=6)
        d += timedelta(days=1)
        while d.weekday() in (4, 5):  # skip Friday, Saturday
            d += timedelta(days=1)
    elif recurrence == "weekly":
        d += timedelta(days=7)
    elif recurrence == "monthly":
        d = _add_month(d)
    else:
        raise ValueError(f"Unknown recurrence: {recurrence}")
    return datetime.combine(d, local.timetz().replace(tzinfo=None), tzinfo=tz)


def meeting_occurrences(meetings: Iterable[dict[str, Any]], start: datetime, end: datetime,
                        tz: ZoneInfo) -> list[tuple[datetime, dict[str, Any]]]:
    """
    (start time, meeting) for every meeting starting in [start, end), soonest first.
    Recurring meetings are expanded inside the window, and rolled forward if their stored
    due_at is already in the past.
    """
    out: list[tuple[datetime, dict[str, Any]]] = []
    for m in meetings:
        when = m.get("due_at")
        if when is None:
            continue
        recurrence = m.get("recurrence")
        if not recurrence:
            if start <= when < end:
                out.append((when, m))
            continue
        for _ in range(400):  # hard stop; a daily meeting over a week needs ~7
            if when >= end:
                break
            if when >= start:
                out.append((when, m))
            when = next_occurrence(when, recurrence, tz)
    out.sort(key=lambda x: (x[0], x[1].get("id", 0)))
    return out


def parse_local_datetime(value: str | None, tz: ZoneInfo) -> datetime | None:
    """Parse an ISO string from the LLM/UI. Naive values are treated as local time."""
    if value is None or str(value).strip() == "":
        return None
    dt = datetime.fromisoformat(str(value).strip().replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=tz)
    return dt


# ================================================================ conversation day

def day_start(now_local: datetime, reset: time) -> datetime:
    """Start of the current chat "day" (the LLM only sees messages after this)."""
    start = now_local.replace(hour=reset.hour, minute=reset.minute, second=0, microsecond=0)
    if start > now_local:
        start -= timedelta(days=1)
    return start


# ================================================================ proactive-contact guardrails
#
# "Code decides WHEN, the LLM decides WHAT." Everything here runs before any LLM call.

@dataclass(frozen=True)
class Guardrails:
    quiet_start: time
    quiet_end: time
    day_reset: time = time(4)
    min_gap_min: int = 20
    max_per_day: int = 20
    max_per_task_per_day: int = 4
    followup_min_min: int = 10
    followup_max_days: int = 7
    # Shabbat / holidays: local time → when the current rest period ends, or None (not one now)
    rest_until: Callable[[datetime], datetime | None] | None = None


@dataclass(frozen=True)
class ContactState:
    """
    What has already been sent since the last day reset (derived from messages, never stored).
      sent_today         — every proactive message, meetings and requested reminders included
      sent_today_by_task — only unrequested check-ins (kind 'nudge') per task
    """
    last_proactive_at: datetime | None = None
    sent_today: int = 0
    sent_today_by_task: dict[int, int] = field(default_factory=dict)


@dataclass
class ContactPlan:
    send: list[dict[str, Any]] = field(default_factory=list)   # tasks for ONE combined message
    defer: dict[int, datetime] = field(default_factory=dict)   # task id → new remind_at


def contact_decision(task: dict[str, Any], state: ContactState, now_local: datetime,
                     g: Guardrails) -> datetime | None:
    """
    Decide whether a due task may be contacted about in this tick.
    Returns None to contact now, or the (aware) datetime to postpone its remind_at to.

    task has: id, kind ('task' | 'meeting'), blocked_until (aware datetime | None),
              remind_requested (bool: the user asked for this reminder time).
    Rules:
      - Shabbat / holidays (g.rest_until) and quiet hours are never overruled, meetings included
      - blocked tasks wait until blocked_until
      - meetings skip the gap and the caps (they still count toward the daily cap)
      - min gap between proactive messages
      - max proactive messages per day
      - max check-ins per task per day, not counting reminders the user asked for
    When several rules apply, the latest postponement wins (saves re-deferring every tick).
    """
    later: list[datetime] = []
    if g.rest_until is not None and (rest_end := g.rest_until(now_local)) is not None:
        later.append(rest_end)
    if in_quiet_hours(now_local, g.quiet_start, g.quiet_end):
        later.append(quiet_end_after(now_local, g.quiet_end))
    if task.get("blocked_until") and task["blocked_until"] > now_local:
        later.append(task["blocked_until"])
    if task["kind"] != "meeting":
        if state.last_proactive_at:
            gap_end = state.last_proactive_at + timedelta(minutes=g.min_gap_min)
            if gap_end > now_local:
                later.append(gap_end)
        if state.sent_today >= g.max_per_day:
            later.append(next_morning(now_local, g))
        if (not task.get("remind_requested")
                and state.sent_today_by_task.get(task["id"], 0) >= g.max_per_task_per_day):
            later.append(next_morning(now_local, g))
    return max(later) if later else None


def next_morning(now_local: datetime, g: Guardrails) -> datetime:
    """First moment the daily counters have reset AND quiet hours are over."""
    t = day_start(now_local, g.day_reset) + timedelta(days=1)
    if in_quiet_hours(t, g.quiet_start, g.quiet_end):
        t = quiet_end_after(t, g.quiet_end)
    return t


def plan_contacts(due: Iterable[dict[str, Any]], state: ContactState, now_local: datetime,
                  g: Guardrails) -> ContactPlan:
    """Split due tasks into one combined message (send) and postponements (defer)."""
    plan = ContactPlan()
    for task in due:
        later = contact_decision(task, state, now_local, g)
        if later is None:
            plan.send.append(task)
        else:
            plan.defer[task["id"]] = later
    # Meetings first (soonest start), then most important, then longest waiting.
    far = datetime.max.replace(tzinfo=now_local.tzinfo)
    plan.send.sort(key=lambda t: (
        t["kind"] != "meeting",
        (t.get("due_at") or far) if t["kind"] == "meeting" else far,
        -t["priority"],
        t.get("remind_at") or far,
    ))
    return plan


def clamp_followup(now: datetime, requested: datetime, g: Guardrails) -> datetime:
    """Keep an LLM-chosen follow-up within [now + min, now + max days]."""
    lo = now + timedelta(minutes=g.followup_min_min)
    hi = now + timedelta(days=g.followup_max_days)
    return min(max(requested, lo), hi)


# ================================================================ replies → tasks

_WORD = re.compile(r"\w{3,}", re.UNICODE)


def _words(text: str) -> set[str]:
    return {w.lower() for w in _WORD.findall(text or "")}


def select_candidate_tasks(
    reply: str,
    now: datetime,
    last_proactive: dict[str, Any] | None,
    recent_task_ids: list[int],
    open_tasks: list[dict[str, Any]],
    link_hours: float,
    limit: int = 3,
) -> list[int]:
    """
    Up to `limit` task ids the reply is *probably* about, best first. Only a hint for the LLM.
      1. tasks of the last proactive message, if it was sent within link_hours
      2. tasks touched in the last few turns
      3. open tasks whose title shares a word (3+ letters) with the reply
    """
    out: list[int] = []

    def add(ids: Iterable[int]) -> None:
        for i in ids:
            if i not in out:
                out.append(i)

    if last_proactive and now - last_proactive["created_at"] <= timedelta(hours=link_hours):
        add(last_proactive.get("task_ids") or [])
    add(recent_task_ids)
    words = _words(reply)
    if words:
        add(t["id"] for t in open_tasks if words & _words(t["title"]))
    return out[:limit]


def link_task_ids(acted_on: list[int], checkin_candidates: list[int]) -> list[int]:
    """
    Which tasks a user turn is linked to, decided after the agent ran:
    the tasks the agent acted on; otherwise the check-in's task if it was unambiguous; else none.
    """
    if acted_on:
        return list(dict.fromkeys(acted_on))
    if len(checkin_candidates) == 1:
        return list(checkin_candidates)
    return []


# ================================================================ text

def clip(text: str, limit: int) -> str:
    """
    At most `limit` characters. Longer text is cut at the last space or line break (unless that would
    drop more than a third of it, e.g. one very long word) and ends with "…".
    """
    if len(text) <= limit:
        return text
    cut = text[: limit - 1]
    if not text[limit - 1].isspace():  # the cut falls inside a word: drop that word
        space = max(cut.rfind(" "), cut.rfind("\n"))
        if space >= (limit * 2) // 3:
            cut = cut[:space]
    return cut.rstrip() + "…"
