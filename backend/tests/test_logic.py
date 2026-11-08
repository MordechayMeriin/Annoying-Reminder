"""Run: cd backend && python -m pytest -q"""

import time as _time
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from app.logic import (in_quiet_hours, job_is_due, next_occurrence, next_reminder_after_nag,
                       parse_local_datetime, quiet_end_after)
from app.tokens import make_action_token, verify_action_token

TZ = ZoneInfo("Asia/Jerusalem")


def local(y, mo, d, h, mi=0):
    return datetime(y, mo, d, h, mi, tzinfo=TZ)


def test_quiet_hours_across_midnight():
    s, e = time(23, 0), time(7, 0)
    assert in_quiet_hours(local(2026, 11, 8, 23, 30), s, e)
    assert in_quiet_hours(local(2026, 11, 9, 6, 59), s, e)
    assert not in_quiet_hours(local(2026, 11, 9, 7, 0), s, e)
    assert not in_quiet_hours(local(2026, 11, 9, 14, 0), s, e)


def test_quiet_end_after():
    assert quiet_end_after(local(2026, 11, 8, 23, 30), time(7)) == local(2026, 11, 9, 7)
    assert quiet_end_after(local(2026, 11, 9, 3, 0), time(7)) == local(2026, 11, 9, 7)


def test_job_window():
    assert job_is_due(local(2026, 11, 9, 7, 30), time(7, 30))
    assert job_is_due(local(2026, 11, 9, 9, 0), time(7, 30))
    assert not job_is_due(local(2026, 11, 9, 7, 25), time(7, 30))
    assert not job_is_due(local(2026, 11, 9, 11, 0), time(7, 30))


def test_nagging_and_escalation():
    now = datetime(2026, 11, 9, 10, tzinfo=timezone.utc)
    nxt, esc = next_reminder_after_nag(now, 1, 1, 30, 4)
    assert nxt == now + timedelta(minutes=30) and not esc
    nxt, _ = next_reminder_after_nag(now, 1, 3, 30, 4)
    assert nxt == now + timedelta(minutes=15)
    assert next_reminder_after_nag(now, 4, 1, 30, 4) == (None, False)
    assert next_reminder_after_nag(now, 4, 3, 30, 4) == (None, True)


def test_recurrence():
    thu = local(2026, 11, 12, 9)  # Thursday
    assert next_occurrence(thu, "weekdays", TZ) == local(2026, 11, 15, 9)  # → Sunday
    assert next_occurrence(thu, "daily", TZ) == local(2026, 11, 13, 9)
    assert next_occurrence(thu, "weekly", TZ) == local(2026, 11, 19, 9)
    assert next_occurrence(local(2026, 1, 31, 8), "monthly", TZ) == local(2026, 2, 28, 8)


def test_recurrence_keeps_wall_clock_across_dst():
    # Israel DST ends on the last Sunday of October (2026-10-25)
    before = local(2026, 10, 24, 9)
    after = next_occurrence(before, "daily", TZ)
    assert after.hour == 9 and after.utcoffset() == timedelta(hours=2)


def test_parse_local_datetime():
    dt = parse_local_datetime("2026-11-09T14:00", TZ)
    assert dt == local(2026, 11, 9, 14)
    assert parse_local_datetime("2026-11-09T12:00Z", TZ) == datetime(2026, 11, 9, 12, tzinfo=timezone.utc)
    assert parse_local_datetime("", TZ) is None


def test_action_tokens():
    tok = make_action_token(42, "secret")
    assert verify_action_token(tok, "secret") == 42
    assert verify_action_token(tok, "other") is None
    task, exp, sig = tok.split(".")
    assert verify_action_token(f"43.{exp}.{sig}", "secret") is None  # tampered id
    expired = make_action_token(42, "secret", ttl_seconds=-1)
    assert verify_action_token(expired, "secret") is None
    assert verify_action_token("garbage", "secret") is None
