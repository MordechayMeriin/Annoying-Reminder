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


# ================================================================ ADHD assistant (Phase 1)
#
# "Code decides WHEN, WHICH playbook and WHICH buttons. The LLM writes the words."
# Decisions below are constants, not settings, to keep ⚙ from growing (see the brief).

FOCUS_MAX = 3                   # Today's 3
NEAR_DEADLINE_H = 48            # tasks due this soon are still checked in on outside Today's 3
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

    def to_json(self, lang: str, task_id: int | None = None) -> dict[str, Any]:
        out: dict[str, Any] = {"id": self.id, "label": chip_label(self.id, lang)}
        if self.action:
            out["action"] = self.action
            if self.minutes:
                out["minutes"] = self.minutes
            if task_id is not None:
                out["task_id"] = task_id
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

# Message kind → its chips. "What now?" is a text chip until the code picker (F4, Phase 2) exists.
CHIPS_BY_KIND: dict[str, tuple[Chip, ...]] = {
    "checkin": (_START5, _DONE, Chip("later", "later"), Chip("stuck")),
    "morning": (Chip("start_first", "start", START_MIN_NOTIFICATION), Chip("change")),
    "session_end": (_DONE, Chip("another", "another"), Chip("stop", "stop")),
    "avoided": (Chip("too_big"), Chip("unclear"), Chip("boring"), Chip("not_now", "not_now")),
    "reentry": (Chip("pick_one"), Chip("move_overdue", "move_overdue")),
    "sunday": (Chip("drop", "drop"), Chip("keep", "keep")),
    "meeting": (),
    "midday": (Chip("start_next", "start", START_MIN_NOTIFICATION), Chip("what_now")),
    "what_now": (Chip("start", "start", START_MIN_NOTIFICATION), Chip("low_energy"), Chip("something_else")),
    "chat_task": (_START5, _DONE),
    "evening": (),
}

# Chips that need a task to act on: without one they're left out.
_NEEDS_TASK = {"start", "done", "later", "another", "stop", "not_now"}

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


def chips_json(chips: Iterable[Chip], lang: str, task_id: int | None) -> list[dict[str, Any]]:
    """Chips as stored on the message: action chips are bound to their task (dropped if there is none)."""
    return [c.to_json(lang, task_id) for c in chips if task_id is not None or c.action not in _NEEDS_TASK]


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
    3+ postponed / ignored events in 7 days. (in_session and paused come with sessions, Phase 2.)
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
               Boring chip), overwhelmed, completed (a task was just marked done)
    """
    sig = set(signals)
    order: list[str] = []
    if job == "reentry":
        order.append("reentry")
    elif job == "morning":
        order.append("planning")
        if "sunday" in sig:
            order.append("sunday")
    if "avoid_reason" in sig or "avoided" in set(stages):
        order.append("avoiding")
    if "stuck" in sig:
        order.append("stuck")
    if "overwhelmed" in sig:
        order.append("overwhelmed")
    if "completed" in sig:
        order.append("celebrate")
    return list(dict.fromkeys(order))[:2]


_AVOID_CHIPS = {"too_big", "unclear", "boring"}


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
    if chip in _AVOID_CHIPS:
        out.add("avoid_reason")
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
            and not (t.get("blocked_until") and t["blocked_until"] > now)]
    rows.sort(key=lambda t: (bucket(t), -t.get("priority", 1), t.get("due_at") or far,
                             t.get("created_at") or far, t["id"]))
    return [t["id"] for t in rows[:limit]]


def may_check_in(task: dict[str, Any], now: datetime, today: date) -> bool:
    """
    Check-ins during the day only for: meetings and fences, reminders the user asked for, Today's 3,
    and tasks due within 48 hours (overdue included). Undated tasks outside Today's 3 stay quiet and
    compete for tomorrow's focus instead. (tasks.due_for_contact applies the same rule in SQL.)
    """
    if task["kind"] in ("meeting", "fence") or task.get("remind_requested"):
        return True
    if task.get("focus_date") == today:
        return True
    due = task.get("due_at")
    return due is not None and due < now + timedelta(hours=NEAR_DEADLINE_H)


def user_active_recently(last_activity: datetime | None, now: datetime,
                         minutes: int = ACTIVE_SKIPS_CHECKIN_MIN) -> bool:
    return last_activity is not None and now - last_activity < timedelta(minutes=minutes)
