"""Pure scheduling logic (no I/O) — easy to unit-test."""

import calendar
from datetime import date, datetime, time, timedelta
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
) -> tuple[datetime | None, bool]:
    """
    Decide what happens after a reminder was just sent (nag_count already incremented).
    Returns (next remind_at or None to stop nagging, escalate_to_pushover).
    Higher priority nags more often.
    """
    if nag_count >= max_nags:
        return None, priority >= 3
    interval = interval_min
    if priority == 2:
        interval = max(10, interval_min * 2 // 3)
    elif priority >= 3:
        interval = max(5, interval_min // 2)
    return now + timedelta(minutes=interval), False


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


def parse_local_datetime(value: str | None, tz: ZoneInfo) -> datetime | None:
    """Parse an ISO string from the LLM/UI. Naive values are treated as local time."""
    if value is None or str(value).strip() == "":
        return None
    dt = datetime.fromisoformat(str(value).strip().replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=tz)
    return dt
