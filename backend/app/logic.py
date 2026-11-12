"""Pure scheduling logic (no I/O) — easy to unit-test."""

import calendar
import math
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
NEAR_DEADLINE_H = 48            # a deadline this close: check-ins outside Today's 3, and louder levels

_ALARM_MIN_PRIORITY = {"critical": 3, "important": 2, "all": 1}


@dataclass(frozen=True)
class AlertPolicy:
    urgent_after: int = 1          # ignored check-ins before they turn urgent
    alarm_for: str = "important"   # critical | important | all | off  (by task priority)
    alarm_after: int = 2           # ignored check-ins before the next one is an alarm
    max_nags: int = 4              # check-ins per task before nagging stops


def escalates(task: dict[str, Any], now: datetime) -> bool:
    """
    May a contact about this task be louder than normal, and come more than once a day? Only meetings,
    fences, reminders the user asked for, and tasks with a deadline within 48 hours (coming up, or
    missed that recently). Everything else stays at normal level, one check-in a day at most: pressure
    on a task that's being avoided feeds the avoidance.
    """
    if task["kind"] in ("meeting", "fence") or task.get("remind_requested"):
        return True
    due = task.get("due_at")
    window = timedelta(hours=NEAR_DEADLINE_H)
    return due is not None and now - window < due < now + window


def alert_level(task: dict[str, Any], ignored: int, p: AlertPolicy, now: datetime) -> int:
    """
    How loud a contact about one task should be.
    ignored: check-ins about it in a row that got no reply.
      NORMAL — every task that doesn't escalate (see escalates), however often it was ignored
      URGENT — meetings and fences, always (never an alarm)
    and for tasks that escalate (asked-for reminders, a deadline within 48 hours):
      ALARM  — a task of an alarm-worthy priority, ignored `alarm_after` times; if nagging would stop
               before that, the last check-in is the alarm (only when all the earlier ones were ignored)
      URGENT — reminders the user asked for, important/critical tasks, or ignored `urgent_after` times
      NORMAL — everything else (first check-ins about ordinary tasks)
    """
    if task["kind"] in ("meeting", "fence"):
        return URGENT
    if not escalates(task, now):
        return NORMAL
    min_prio = _ALARM_MIN_PRIORITY.get(p.alarm_for)
    if (min_prio is not None and task["priority"] >= min_prio
            and ignored >= min(p.alarm_after, max(p.max_nags - 1, 0))):
        return ALARM
    if task.get("remind_requested") or task["priority"] >= 2 or ignored >= p.urgent_after:
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
    # A focus session is running (or just ended): other check-ins wait until this time (from sessions,
    # not messages; see focus_hold_until).
    focus_hold_until: datetime | None = None


@dataclass
class ContactPlan:
    send: list[dict[str, Any]] = field(default_factory=list)   # tasks for ONE combined message
    defer: dict[int, datetime] = field(default_factory=dict)   # task id → new remind_at


def contact_decision(task: dict[str, Any], state: ContactState, now_local: datetime,
                     g: Guardrails) -> datetime | None:
    """
    Decide whether a due task may be contacted about in this tick.
    Returns None to contact now, or the (aware) datetime to postpone its remind_at to.

    task has: id, kind ('task' | 'meeting' | 'fence' | 'session'), blocked_until (aware datetime | None),
              remind_requested (bool: the user asked for this reminder time).
              kind 'session' = the check-in at the end of a focus session (scheduler.plan_sessions).
    Rules:
      - Shabbat / holidays (g.rest_until) and quiet hours are never overruled, meetings included
      - blocked tasks wait until blocked_until
      - focus hold: while a session runs (until it ends + 10 min) only meetings, fences and the
        session's own end check-in come through
      - meetings and fences skip the gap and the caps (they still count toward the daily cap)
      - session end check-ins skip the gap and the per-task cap (user-requested), not the daily cap
      - min gap between proactive messages
      - max proactive messages per day
      - max check-ins per task per day, not counting reminders the user asked for; one a day for
        tasks that don't escalate (see escalates)
    When several rules apply, the latest postponement wins (saves re-deferring every tick).
    """
    later: list[datetime] = []
    if g.rest_until is not None and (rest_end := g.rest_until(now_local)) is not None:
        later.append(rest_end)
    if in_quiet_hours(now_local, g.quiet_start, g.quiet_end):
        later.append(quiet_end_after(now_local, g.quiet_end))
    if task.get("blocked_until") and task["blocked_until"] > now_local:
        later.append(task["blocked_until"])
    if (task["kind"] not in ("meeting", "fence", "session") and state.focus_hold_until
            and state.focus_hold_until > now_local):
        later.append(state.focus_hold_until)
    if task["kind"] not in ("meeting", "fence"):
        if state.last_proactive_at and task["kind"] != "session":
            gap_end = state.last_proactive_at + timedelta(minutes=g.min_gap_min)
            if gap_end > now_local:
                later.append(gap_end)
        if state.sent_today >= g.max_per_day:
            later.append(next_morning(now_local, g))
        per_task = g.max_per_task_per_day if escalates(task, now_local) else min(1, g.max_per_task_per_day)
        if not task.get("remind_requested") and state.sent_today_by_task.get(task["id"], 0) >= per_task:
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
    # Meetings and fences first (soonest start), then most important, then longest waiting.
    far = datetime.max.replace(tzinfo=now_local.tzinfo)
    fixed = ("meeting", "fence")
    plan.send.sort(key=lambda t: (
        t["kind"] not in fixed,
        (t.get("due_at") or far) if t["kind"] in fixed else far,
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


# ================================================================ ADHD assistant (Phase 1)
#
# "Code decides WHEN, WHICH playbook and WHICH buttons. The LLM writes the words."
# Decisions below are constants, not settings, to keep ⚙ from growing (see the brief).

FOCUS_MAX = 3                   # Today's 3
LATER_CUTOFF = time(18)         # "Later": +2 h before 18:00, otherwise the next morning
LATER_HOURS = 2
AVOIDED_MIN_EVENTS = 3          # postponed or ignored events in 7 days → the task is avoided
ACTIVE_SKIPS_CHECKIN_MIN = 60   # a mid-day check-in is skipped when the user was active this recently
START_MIN_NOTIFICATION = 5      # ▶ Start from a notification / chip
START_MIN_APP = 25              # ▶ Start from the focus card
OVERWHELMED_FROM = time(15)


# ---------------------------------------------------------------- reply buttons ("chips")

@dataclass(frozen=True)
class Chip:
    id: str
    action: str | None = None   # action chips run without the LLM (actions.apply); None = a text chip
    minutes: int | None = None  # ▶ Start: session length

    def to_json(self, lang: str, task_id: int | None = None, task_ids: Iterable[int] = ()) -> dict[str, Any]:
        out: dict[str, Any] = {"id": self.id, "label": chip_label(self.id, lang)}
        if self.action:
            out["action"] = self.action
            if self.minutes:
                out["minutes"] = self.minutes
            if task_id is not None and self.action in _NEEDS_TASK:
                out["task_id"] = task_id
            if self.action in _NEEDS_TASKS:
                out["task_ids"] = list(task_ids)
        return out


# Every chip in both languages. Text chips send their label as a user message.
CHIP_LABELS: dict[str, tuple[str, str]] = {
    "start5": ("▶ Start 5 min", "▶ להתחיל 5 דק׳"),
    "done": ("✓ Done", "✓ בוצע"),
    "later": ("Later", "אחר כך"),
    "stuck": ("I'm stuck", "נתקעתי"),
    "start_first": ("▶ Start the first one", "▶ להתחיל בראשונה"),
    "change": ("Change it", "לשנות"),
    "another": ("▶ Another round", "▶ עוד סבב"),
    "stop": ("Stop", "לעצור"),
    "too_big": ("Too big", "גדול מדי"),
    "unclear": ("Unclear", "לא ברור"),
    "boring": ("Boring", "משעמם"),
    "not_now": ("Not now", "לא עכשיו"),
    "pick_one": ("Pick one for me", "תבחר לי משהו"),
    "move_overdue": ("Move overdue to next week", "להעביר את מה שבאיחור לשבוע הבא"),
    "drop": ("Drop them", "לוותר עליהן"),
    "keep": ("Keep", "להשאיר"),
    "start_next": ("▶ Start next", "▶ להתחיל את הבאה"),
    "what_now": ("What now?", "מה עכשיו?"),
    "start": ("▶ Start", "▶ להתחיל"),
    "low_energy": ("Low energy", "אין לי כוח"),
    "something_else": ("Something else", "משהו אחר"),
}

_START5 = Chip("start5", "start", START_MIN_NOTIFICATION)
_DONE = Chip("done", "done")

# Message kind → its chips. "What now?", Low energy and Something else run the code picker (F4,
# logic.pick_next) with no LLM: action "what_now", no task needed.
_WHAT_NOW = Chip("what_now", "what_now")
CHIPS_BY_KIND: dict[str, tuple[Chip, ...]] = {
    "checkin": (_START5, _DONE, Chip("later", "later"), Chip("stuck")),
    "morning": (Chip("start_first", "start", START_MIN_NOTIFICATION), Chip("change")),
    "session_end": (_DONE, Chip("another", "another"), Chip("stop", "stop")),
    "avoided": (Chip("too_big"), Chip("unclear"), Chip("boring"), Chip("not_now", "not_now")),
    "reentry": (Chip("pick_one"), Chip("move_overdue", "move_overdue")),
    "sunday": (Chip("drop", "drop"), Chip("keep", "keep")),
    "meeting": (),
    "midday": (Chip("start_next", "start", START_MIN_NOTIFICATION), _WHAT_NOW),
    "what_now": (Chip("start", "start", START_MIN_NOTIFICATION), Chip("low_energy", "what_now"),
                 Chip("something_else", "what_now")),
    "chat_task": (_START5, _DONE),
    "evening": (),
}

# Chips that need a task to act on: without one they're left out.
_NEEDS_TASK = {"start", "done", "later", "another", "stop", "not_now"}
# Chips that act on a list of tasks (Sunday cleanup): bound to exactly those ids (chips_json task_ids).
_NEEDS_TASKS = {"drop", "keep"}

_HEBREW = re.compile(r"[֐-׿]")


def chip_lang(latest_user_text: str | None, fallback_text: str | None = None) -> str:
    """'he' when the user's latest chat message (or, failing that, e.g. the task title) has Hebrew letters."""
    text = latest_user_text if latest_user_text and latest_user_text.strip() else fallback_text
    return "he" if text and _HEBREW.search(text) else "en"


def chip_label(chip_id: str, lang: str) -> str:
    en, he = CHIP_LABELS[chip_id]
    return he if lang == "he" else en


def chips_for(kind: str, stage: str | None, lang: str) -> list[Chip]:
    """
    The reply buttons for a message, from its kind and the stage of the task it's about. No LLM.
    A done (or cancelled) task gets none: there's nothing left to start.
    """
    if stage in ("done", "cancelled"):
        return []
    return list(CHIPS_BY_KIND.get(kind, ()))


def chips_json(chips: Iterable[Chip], lang: str, task_id: int | None,
               task_ids: Iterable[int] = ()) -> list[dict[str, Any]]:
    """
    Chips as stored on the message: action chips are bound to their task (dropped if there is none).
    task_ids: the tasks Drop them / Keep act on (dropped if there are none).
    """
    ids = list(task_ids)
    return [c.to_json(lang, task_id, ids) for c in chips
            if (task_id is not None or c.action not in _NEEDS_TASK) and (ids or c.action not in _NEEDS_TASKS)]


def chip_id_for_text(text: str) -> str | None:
    """Which text chip a user message is (a tap on it sends its label), in either language."""
    t = (text or "").strip()
    for chip_id, labels in CHIP_LABELS.items():
        if t in labels:
            return chip_id
    return None


def later_time(now_local: datetime, morning: time) -> datetime:
    """'Later' is decided by code: +2 hours before 18:00, otherwise the next morning plan time."""
    if now_local.time() < LATER_CUTOFF:
        return now_local + timedelta(hours=LATER_HOURS)
    return next_time_at(now_local, morning)


# ---------------------------------------------------------------- stages

def focus_day(now_local: datetime, reset: time) -> date:
    """The day Today's 3 belongs to: the chat day (it starts at the day-reset time, not at midnight)."""
    return day_start(now_local, reset).date()


def task_stage(task: dict[str, Any], counts_7d: dict[str, int] | None, today: date) -> str:
    """
    captured → clarified (has a next action) → planned (in Today's 3) → done; avoided after
    3+ postponed / ignored events in 7 days. (A running session is shown by the session itself, not
    by a stage: see session_state.)
    """
    if task.get("status") in ("done", "cancelled"):
        return task["status"]
    counts = counts_7d or {}
    if counts.get("postponed", 0) + counts.get("ignored", 0) >= AVOIDED_MIN_EVENTS:
        return "avoided"
    if task.get("focus_date") == today:
        return "planned"
    if task.get("next_action"):
        return "clarified"
    return "captured"


# ---------------------------------------------------------------- playbooks

PLAYBOOKS = ("planning", "stuck", "avoiding", "overwhelmed", "reentry", "celebrate", "sunday")


def select_playbooks(job: str | None = None, stages: Iterable[str] = (),
                     signals: Iterable[str] = ()) -> list[str]:
    """
    At most two situational playbooks for one LLM call; none = the core prompt only.
      job:     morning → planning (Sunday morning, signal "sunday": + sunday); reentry → reentry
      stages:  of the tasks the message is (probably) about; avoided → avoiding
      signals: stuck (a stuck event today / the I'm stuck chip), avoid_reason (Too big / Unclear /
               Boring chip), overwhelmed, completed (a task was just marked done), reentry (the Pick one
               for me chip under the re-entry message)
    """
    sig = set(signals)
    order: list[str] = []
    if job == "reentry":
        order.append("reentry")
    elif job == "morning":
        order.append("planning")
        if "sunday" in sig:
            order.append("sunday")
    if "reentry" in sig and "reentry" not in order:
        order.append("reentry")
    if "avoid_reason" in sig or "avoided" in set(stages):
        order.append("avoiding")
    if "stuck" in sig:
        order.append("stuck")
    if "overwhelmed" in sig:
        order.append("overwhelmed")
    if "completed" in sig:
        order.append("celebrate")
    return list(dict.fromkeys(order))[:2]


AVOID_CHIPS = ("too_big", "unclear", "boring")   # the reason chips (text chips; code logs the reason)
AVOID_REASONS = (*AVOID_CHIPS, "scary", "people", "no_energy")   # the last three: noticed by the agent


def chat_signals(text: str, since_last_reply: Iterable[dict[str, Any]], stuck_today: bool,
                 now_local: datetime, focus_open: int) -> set[str]:
    """
    Playbook signals for a chat turn, from plain facts:
      text            the user's message (a chip tap sends the chip's label)
      since_last_reply messages after the assistant's last one (events from taps)
      stuck_today     a likely task got a stuck event today
      focus_open      Today's 3 still open (3 = none done yet)
    """
    out: set[str] = set()
    chip = chip_id_for_text(text)
    if stuck_today or chip == "stuck":
        out.add("stuck")
    if chip in AVOID_CHIPS:
        out.add("avoid_reason")
    if chip == "pick_one":
        out.add("reentry")
    if now_local.time() >= OVERWHELMED_FROM and focus_open >= FOCUS_MAX:
        out.add("overwhelmed")
    if any(m.get("kind") == "event" and m.get("content", "").startswith("[Marked done") for m in since_last_reply):
        out.add("completed")
    return out


# ---------------------------------------------------------------- Today's 3 and who gets check-ins

def pick_focus(tasks: Iterable[dict[str, Any]], now_local: datetime, limit: int = FOCUS_MAX) -> list[int]:
    """
    The code's pick for Today's 3 (when the LLM can't): overdue, then due today, then by priority,
    then oldest. Only open top-level tasks that aren't blocked; never meetings or fences.
    now_local: local time (it decides where "today" ends).
    """
    now = now_local
    midnight = now_local.replace(hour=0, minute=0, second=0, microsecond=0)
    d = (midnight + timedelta(days=1)).date()
    end_of_day = datetime(d.year, d.month, d.day, tzinfo=now_local.tzinfo)
    far = datetime.max.replace(tzinfo=now_local.tzinfo)

    def bucket(t: dict[str, Any]) -> int:
        due = t.get("due_at")
        if due is not None and due < now:
            return 0
        if due is not None and due < end_of_day:
            return 1
        return 2

    rows = [t for t in tasks
            if t.get("status", "open") == "open" and t.get("kind", "task") == "task" and not t.get("parent_id")
            and not (t.get("blocked_until") and t["blocked_until"] > now) and not is_parked(t, now)]
    rows.sort(key=lambda t: (bucket(t), -t.get("priority", 1), t.get("due_at") or far,
                             t.get("created_at") or far, t["id"]))
    return [t["id"] for t in rows[:limit]]


def is_parked(task: dict[str, Any], now: datetime) -> bool:
    """Not now: parked tasks get no check-ins and aren't offered (Today's 3, What now?) until the park ends."""
    return task.get("parked_until") is not None and task["parked_until"] > now


def may_check_in(task: dict[str, Any], now: datetime, today: date, lapsed: bool = False) -> bool:
    """
    Check-ins during the day only for: meetings and fences, reminders the user asked for, Today's 3,
    tasks due within 48 hours (overdue included), and an avoided task waiting for its second (last)
    "what's in the way?" message. Undated tasks outside Today's 3 stay quiet and compete for tomorrow's
    focus instead. Parked tasks: nothing. lapsed (no chat or tap for 48 hours): only meetings, fences
    and reminders the user asked for, until they're back. (tasks.due_for_contact: the same rule in SQL.)
    """
    if task["kind"] in ("meeting", "fence"):
        return True
    if is_parked(task, now):
        return False
    if task.get("remind_requested"):
        return True
    if lapsed:
        return False
    if task.get("focus_date") == today or task.get("avoid_asks"):
        return True
    due = task.get("due_at")
    return due is not None and due < now + timedelta(hours=NEAR_DEADLINE_H)


def user_active_recently(last_activity: datetime | None, now: datetime,
                         minutes: int = ACTIVE_SKIPS_CHECKIN_MIN) -> bool:
    return last_activity is not None and now - last_activity < timedelta(minutes=minutes)


# ================================================================ ADHD assistant (Phase 2): start and come back
#
# What now? (F4), focus sessions (F5), breadcrumbs (F6). Constants, not settings (see the brief).

SESSION_CHOICES = (5, 15, 25, 45)  # one tap switches between them on the session screen
SESSION_MAX_MIN = 120              # +5 can go past 45, up to this
SESSION_SILENCE_MIN = 15           # no answer this long after the end check-in → paused, nothing more sent
FOCUS_HOLD_MIN = 10                # other check-ins wait until the session ends + this
BREADCRUMB_HOURS = 3               # "Where did you stop?" is offered this long after a stopped / paused session
DEFAULT_MULTIPLIER = 1.5           # estimates × this until Phase 4 learns the real one
DEFAULT_ESTIMATE_MIN = 15          # a task without an estimate: its next action is 15 minutes or less
LOW_ENERGY_MAX_MIN = 15            # Low energy: tasks estimated this short
WHAT_NOW_CHIPS = ("what_now", "low_energy", "something_else")  # the chips that ask the code picker


# ---------------------------------------------------------------- sessions

def session_length(minutes: int | None, default: int = START_MIN_NOTIFICATION) -> int:
    """A session length: a multiple of 5 (ticks run every 5 minutes), 5 to SESSION_MAX_MIN."""
    m = default if not minutes else int(minutes)
    m = -(-m // 5) * 5  # round up to a multiple of 5
    return min(max(m, 5), SESSION_MAX_MIN)


def session_end_at(session: dict[str, Any]) -> datetime:
    """When the session's timer runs out."""
    return session["started_at"] + timedelta(minutes=session["planned_min"])


def session_state(session: dict[str, Any] | None, now: datetime) -> str | None:
    """running (timer on) → ended (time's up, waiting for an answer) → closed (an outcome is recorded)."""
    if session is None:
        return None
    if session.get("ended_at") is not None:
        return "closed"
    return "running" if now < session_end_at(session) else "ended"


def session_silent(session: dict[str, Any], now: datetime) -> bool:
    """
    An open session nobody answered: SESSION_SILENCE_MIN after its end check-in went out, or after its
    end if the check-in couldn't be sent at all (quiet hours, Shabbat, the daily cap). → paused.
    """
    if session.get("ended_at") is not None:
        return False
    since = session.get("end_sent_at") or session_end_at(session)
    return now >= since + timedelta(minutes=SESSION_SILENCE_MIN)


def session_end_due(session: dict[str, Any], now: datetime) -> bool:
    """Time for the end check-in: the timer ran out, it hasn't been sent and the session isn't silent yet."""
    return (session_state(session, now) == "ended" and session.get("end_sent_at") is None
            and not session_silent(session, now))


def session_close_outcome(session: dict[str, Any], now: datetime) -> tuple[str, datetime]:
    """
    (outcome, ended_at) for an open session that's being replaced (a new session started): stopped now
    if its timer was still running, otherwise paused at its planned end (silence means the planned time).
    """
    end = session_end_at(session)
    return ("stopped", now) if now < end else ("paused", end)


def actual_minutes(session: dict[str, Any], now: datetime) -> int:
    """Minutes the session actually ran (until the Done / Stop tap; the planned time when silent)."""
    end = session.get("ended_at") or now
    return max(0, round((end - session["started_at"]).total_seconds() / 60))


def focus_hold_until(latest: dict[str, Any] | None) -> datetime | None:
    """
    While a session runs, other check-ins wait until it ends + FOCUS_HOLD_MIN (meetings and fences
    still come through). Ended early (Done, Stop): the hold counts from that moment.
    latest: the most recent session.
    """
    if latest is None:
        return None
    end = latest.get("ended_at") or session_end_at(latest)
    return end + timedelta(minutes=FOCUS_HOLD_MIN)


def session_last_active(latest: dict[str, Any] | None, now: datetime) -> datetime | None:
    """When a session last ran (now, if one is open): a session in the last hour skips the mid-day check-in."""
    if latest is None:
        return None
    if latest.get("ended_at") is None:
        return min(now, session_end_at(latest))
    return latest["ended_at"]


def wants_breadcrumb(latest: dict[str, Any] | None, now: datetime) -> bool:
    """
    Offer the optional "Where did you stop?" field: the latest session was stopped or went unanswered,
    recently, and the field hasn't been answered or dismissed. Ignoring it changes nothing.
    """
    return (latest is not None and latest.get("outcome") in ("stopped", "paused")
            and latest.get("ended_at") is not None and latest.get("note_closed_at") is None
            and now - latest["ended_at"] < timedelta(hours=BREADCRUMB_HOURS))


# ---------------------------------------------------------------- What now?

@dataclass(frozen=True)
class Pick:
    """
    What now?'s answer. reason:
      fits         the task fits before the next meeting (or there's none)
      short_start  nothing fits (or low energy and nothing is small): a 5-minute start of the first
      small        low energy: the smallest task of 15 minutes or less
      bonus        Today's 3 are all done: one more, only if the user feels like it
      all_done     Today's 3 are all done and nothing else is open (task is None)
    """
    task: dict[str, Any] | None
    minutes: int          # the session length for ▶ Start
    reason: str
    estimate: int | None = None   # the task's estimate × the multiplier


def _est(task: dict[str, Any], multiplier: float) -> int:
    return math.ceil((task.get("estimate_min") or DEFAULT_ESTIMATE_MIN) * multiplier)


def _session_for(est: int, available: int | None) -> int:
    """The shortest session choice that covers the estimate (45 at most), within the time available."""
    m = next((c for c in SESSION_CHOICES if c >= est), SESSION_CHOICES[-1])
    if available is not None:
        fitting = [c for c in SESSION_CHOICES if c <= available]
        m = min(m, fitting[-1]) if fitting else SESSION_CHOICES[0]
    return m


def pick_next(tasks: Iterable[dict[str, Any]], now: datetime, next_fixed_event: datetime | None,
              multiplier: float = DEFAULT_MULTIPLIER, low_energy: bool = False, *, today: date,
              done_ids: Iterable[int] = (), exclude: Iterable[int] = ()) -> Pick | None:
    """
    What now? decided by code in one step, no LLM. None = nothing is open at all.
      Candidates: Today's 3 not done yet (in order), then the other open tasks (due soonest, then
      highest priority, then oldest). When all of Today's 3 are done: the others, as a bonus.
      exclude: tasks already offered (Something else); once every candidate was offered, start over.
      Normal: the first candidate, if its estimate × multiplier fits before next_fixed_event; else the
        shortest one that fits from the same group (Today's 3, or the others); else a 5-minute start
        of the first.
      Low energy: the smallest candidate estimated at 15 minutes or less (that fits); else 5 minutes of
        the first.
    tasks: open tasks (meetings, steps and blocked tasks are skipped here). done_ids: Today's 3 done today.
    """
    done = set(done_ids)
    far = datetime.max.replace(tzinfo=now.tzinfo)
    rows = [t for t in tasks
            if t.get("status", "open") == "open" and t.get("kind", "task") == "task" and not t.get("parent_id")
            and t["id"] not in done and not (t.get("blocked_until") and t["blocked_until"] > now)
            and not is_parked(t, now)]
    focus = sorted((t for t in rows if t.get("focus_date") == today),
                   key=lambda t: (t.get("focus_rank") or 99, t["id"]))
    others = sorted((t for t in rows if t.get("focus_date") != today),
                    key=lambda t: (t.get("due_at") or far, -t.get("priority", 1), t.get("created_at") or far, t["id"]))
    order = focus + others
    if not order:
        return Pick(None, 0, "all_done") if done else None
    skip = set(exclude)
    cands = [t for t in order if t["id"] not in skip] or order
    focus_ids = {t["id"] for t in focus}
    bonus = not focus and bool(done)

    available = None
    if next_fixed_event is not None:
        available = max(0, int((next_fixed_event - now).total_seconds() // 60))

    def fits(t: dict[str, Any]) -> bool:
        return available is None or _est(t, multiplier) <= available

    def chosen(t: dict[str, Any], reason: str) -> Pick:
        est = _est(t, multiplier)
        return Pick(t, _session_for(est, available), reason, est)

    first = cands[0]
    if low_energy:
        def raw(t: dict[str, Any]) -> int:
            return t.get("estimate_min") or DEFAULT_ESTIMATE_MIN

        small = [t for t in cands if raw(t) <= LOW_ENERGY_MAX_MIN and fits(t)]
        if small:  # the smallest; a known estimate beats a guessed one
            return chosen(min(small, key=lambda t: (raw(t), t.get("estimate_min") is None)), "small")
        return Pick(first, SESSION_CHOICES[0], "short_start", _est(first, multiplier))
    if fits(first):
        return chosen(first, "bonus" if bonus else "fits")
    group = [t for t in cands if (t["id"] in focus_ids) == (first["id"] in focus_ids) and fits(t)]
    if group:
        return chosen(min(group, key=lambda t: _est(t, multiplier)), "bonus" if bonus else "fits")
    return Pick(first, SESSION_CHOICES[0], "short_start", _est(first, multiplier))


# ---------------------------------------------------------------- template texts (no LLM)

_TEXTS: dict[str, tuple[str, str]] = {
    "fits": ("Do this: {step} ({min} min).", "עכשיו: {step} ({min} דק׳)."),
    "small": ("Something small: {step} ({min} min).", "משהו קטן: {step} ({min} דק׳)."),
    "short_start_before": ("Before {at}: just 5 minutes of this: {step}.", "עד {at}: רק 5 דקות של זה: {step}."),
    "short_start": ("Just 5 minutes of this: {step}.", "רק 5 דקות של זה: {step}."),
    "bonus": ("All of Today's 3 are done. Bonus, only if you feel like it: {step} ({min} min).",
              "כל שלוש המשימות של היום בוצעו. בונוס, רק אם בא לך: {step} ({min} דק׳)."),
    "all_done": ("All of Today's 3 are done. Nothing else is open.",
                 "כל שלוש המשימות של היום בוצעו. אין עוד משהו פתוח."),
    "nothing": ("Nothing open right now. Tell me what's on your mind and I'll save it.",
                "אין כרגע משהו פתוח. ספר לי מה על הראש ואשמור את זה."),
    "session_end": ("⏱ Time's up: {step}. Done, another round, or stop?",
                    "⏱ הזמן נגמר: {step}. בוצע, עוד סבב, או לעצור?"),
    # Phase 3
    "avoid_ask": ("{title} keeps slipping, and that's fine. What's in the way?",
                  "{title} נדחית שוב ושוב, וזה בסדר. מה מפריע?"),
    "reentry": ("Good to see you. Want me to pick one thing for today?",
                "טוב לראות אותך. שאבחר לך דבר אחד להיום?"),
    "fence": ("⛔ {at} — {title}. Time to stop.", "⛔ {at} — {title}. הגיע הזמן לעצור."),
    "wins_week": ("✅ Last week you finished {n} things.", "✅ בשבוע שעבר סיימת {n} דברים."),
    "wins_week_1": ("✅ Last week you finished one thing.", "✅ בשבוע שעבר סיימת דבר אחד."),
    "stale": ("🧹 Open for 3+ weeks with no progress: {titles}. Drop them or keep?",
              "🧹 פתוחות יותר מ-3 שבועות בלי התקדמות: {titles}. לוותר עליהן או להשאיר?"),
}


def _t(key: str, lang: str, **values: Any) -> str:
    en, he = _TEXTS[key]
    return (he if lang == "he" else en).format(**values)


def step_of(task: dict[str, Any]) -> str:
    """The task as one thing to do: its next action, with the title when they differ."""
    step = task.get("next_action") or task["title"]
    return step if step == task["title"] else f"{step} — {task['title']}"


def what_now_text(pick: Pick | None, lang: str, next_fixed_local: datetime | None = None) -> str:
    """What now?'s reply. next_fixed_local: the next meeting's local start (names the deadline of a short start)."""
    if pick is None:
        return _t("nothing", lang)
    if pick.task is None:
        return _t(pick.reason, lang)
    values = {"step": step_of(pick.task), "min": pick.minutes}
    if pick.reason == "short_start" and next_fixed_local is not None:
        return _t("short_start_before", lang, at=f"{next_fixed_local:%H:%M}", **values)
    return _t(pick.reason, lang, **values)


def text(key: str, lang: str, **values: Any) -> str:
    """A fixed text in the user's language (the Phase 3 templates: avoid_ask, reentry, fence, wins_week, stale)."""
    return _t(key, lang, **values)


def session_text(kind: str, task: dict[str, Any], minutes: int, lang: str) -> str:
    """Session messages are templates (no LLM). kind: 'session_end'."""
    return _t(kind, lang, step=task.get("next_action") or task["title"], min=minutes)


# ================================================================ ADHD assistant (Phase 3): gentler pressure
#
# Avoidance (F7): no nagging, one "what's in the way?" message, then quiet. De-escalation: see
# escalates / alert_level. Re-entry after a lapse. Sunday cleanup (F8). Fences (F9). Constants, not settings.

AVOID_RETRY_DAYS = 3        # silence after the reason message: one more try this much later ...
AVOID_MAX_ASKS = 2          # ... and after that try (and 3 more silent days) the task is parked
PARK_DAYS = 7               # Not now (or silence): no check-ins for this long
LAPSE_HOURS = 48            # no chat or tap for this long (Shabbat and holidays not counted): a lapse
REENTRY_REPEAT_DAYS = 7     # one re-entry message per lapse; a lapse that goes on gets one a week
STALE_DAYS = 21             # Sunday cleanup: open this long with no progress
STALE_COOLDOWN_DAYS = 28    # ... and not offered again for this long
STALE_MAX = 3
WEEK_WINS_DAYS = 7
SUNDAY_MERGE_MIN = 60       # the week's meetings join the morning plan when their times are this close
FENCE_STALE_MIN = 30        # a fence held back longer than this (quiet hours, Shabbat) is skipped, not sent late


def avoid_move(task: dict[str, Any], counts_7d: dict[str, int] | None, now: datetime) -> str | None:
    """
    What to do instead of an ordinary check-in about a task (F7). None = it isn't avoided: check in as usual.
      ask   send the one reason message (Too big · Unclear · Boring · Not now)
      wait  it was sent less than 3 days ago: nothing now
      park  two messages went unanswered: park the task for 7 days, say nothing
    Avoided = 3+ postponed / ignored events in 7 days (counted since the last park, reason or start: see
    tasks.reset_avoidance). A task whose deadline is coming up within 48 hours keeps its ordinary check-ins.
    """
    if task["kind"] != "task":
        return None
    counts = counts_7d or {}
    if counts.get("postponed", 0) + counts.get("ignored", 0) < AVOIDED_MIN_EVENTS:
        return None
    due = task.get("due_at")
    if due is not None and now < due <= now + timedelta(hours=NEAR_DEADLINE_H):
        return None
    asks, asked = task.get("avoid_asks") or 0, task.get("avoid_asked_at")
    if asks == 0 or asked is None:
        return "ask"
    if now < asked + timedelta(days=AVOID_RETRY_DAYS):
        return "wait"
    return "ask" if asks < AVOID_MAX_ASKS else "park"


def lapsed(last_activity: datetime | None, now: datetime,
           rest_periods: Iterable[tuple[datetime, datetime]] = (), hours: float = LAPSE_HOURS) -> bool:
    """
    A lapse: no chat message and no tap for 48 hours, not counting Shabbat and holidays
    (rest_periods: their (start, end)). Someone who never wrote at all (a new install) hasn't lapsed.
    """
    if last_activity is None:
        return False
    away = now - last_activity
    for start, end in rest_periods:
        overlap = min(end, now) - max(start, last_activity)
        if overlap > timedelta(0):
            away -= overlap
    return away >= timedelta(hours=hours)


def reentry_due(last_activity: datetime | None, last_reentry: datetime | None, now: datetime) -> bool:
    """During a lapse: send the re-entry message? Once per lapse, and again only after a week of more silence."""
    if last_reentry is None or (last_activity is not None and last_reentry < last_activity):
        return True
    return now - last_reentry >= timedelta(days=REENTRY_REPEAT_DAYS)


def week_meetings_merged(morning: time, week_meetings: time | None) -> bool:
    """The week's meetings go inside the first morning plan of the week when the two are within an hour."""
    if week_meetings is None:
        return False
    return abs((week_meetings.hour * 60 + week_meetings.minute) - (morning.hour * 60 + morning.minute)) <= SUNDAY_MERGE_MIN


def fence_is_stale(task: dict[str, Any], now: datetime) -> bool:
    """A hard stop that couldn't be sent near its time (quiet hours, Shabbat) is skipped: late, it's only noise."""
    return task.get("due_at") is not None and now - task["due_at"] > timedelta(minutes=FENCE_STALE_MIN)


def moved_to_next_week(due: datetime, now: datetime) -> datetime:
    """Move overdue to next week: the same weekday and time, the first one at least a week after the old date that's still ahead."""
    new = due + timedelta(days=7)
    while new <= now:
        new += timedelta(days=7)
    return new
