"""Shabbat and holidays as don't-disturb time (Rosh HaAyin, candles 20 min, havdalah at nightfall, ±60 min)."""

import asyncio
from dataclasses import replace
from datetime import date, datetime
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore, ScriptedLLM

from app import scheduler
from app.config import get_settings
from app.jewish_calendar import current_period, next_period, periods_between, rest_config
from app.logic import ContactState, Guardrails, contact_decision

TZ = ZoneInfo("Asia/Jerusalem")


def at(y, mo, d, h, mi=0):
    return datetime(y, mo, d, h, mi, tzinfo=TZ)


CFG = rest_config(get_settings())  # the defaults: Rosh HaAyin


# ---------------------------------------------------------------- the calendar

def test_regular_shabbat_with_margins():
    p = current_period(at(2026, 11, 14, 12), CFG)
    # candle lighting Fri 16:23 - 60 min, havdalah Sat 17:20 + 60 min
    assert (p.start, p.end, p.name) == (at(2026, 11, 13, 15, 23), at(2026, 11, 14, 18, 20), "שבת")


@pytest.mark.parametrize("when, quiet", [
    (at(2026, 11, 13, 15, 22), False),   # just before the margin
    (at(2026, 11, 13, 15, 23), True),
    (at(2026, 11, 14, 18, 19), True),
    (at(2026, 11, 14, 18, 20), False),
    (at(2026, 11, 11, 12), False),       # a Wednesday
])
def test_boundaries(when, quiet):
    assert (current_period(when, CFG) is not None) == quiet


def test_rosh_hashana_after_shabbat_is_one_period():
    p = current_period(at(2026, 9, 13, 12), CFG)
    assert p.start.date() == date(2026, 9, 11) and p.end.date() == date(2026, 9, 13)
    assert current_period(at(2026, 9, 12, 21), CFG) == p   # Saturday night, between the days
    assert "ראש השנה" in p.name and p.name.startswith("שבת")


def test_yom_kippur_on_a_weekday():
    p = current_period(at(2026, 9, 21, 12), CFG)
    assert p.start.date() == date(2026, 9, 20) and p.end.date() == date(2026, 9, 21)
    assert p.name == "יום הכפורים"


def test_one_day_holidays_in_israel_two_outside():
    israel = periods_between(date(2027, 4, 20), date(2027, 4, 22), CFG)
    assert [p.end.date() for p in israel] == [date(2027, 4, 22)]
    abroad = periods_between(date(2027, 4, 20), date(2027, 4, 22), replace(CFG, diaspora=True))
    assert [p.end.date() for p in abroad] == [date(2027, 4, 24)]  # Thu-Fri yom tov, then Shabbat


def test_chol_hamoed_is_not_quiet():
    assert current_period(at(2027, 4, 25, 12), CFG) is None


def test_next_period_from_a_weekday():
    p = next_period(at(2026, 11, 9, 12), CFG)
    assert p.start == at(2026, 11, 13, 15, 23)


def test_mode_off():
    assert rest_config(replace(get_settings(), shabbat_mode=False)) is None


# ---------------------------------------------------------------- guardrail

def test_guardrail_holds_even_meetings_until_after_shabbat():
    g = Guardrails(quiet_start=get_settings().quiet_start, quiet_end=get_settings().quiet_end,
                   rest_until=lambda now: (p.end if (p := current_period(now, CFG)) else None))
    meeting = {"id": 1, "kind": "meeting", "priority": 3}
    assert contact_decision(meeting, ContactState(), at(2026, 11, 14, 10), g) == at(2026, 11, 14, 18, 20)
    assert contact_decision(meeting, ContactState(), at(2026, 11, 15, 10), g) is None


# ---------------------------------------------------------------- end to end

@pytest.fixture
def store(monkeypatch):
    s = FakeStore(at(2026, 11, 14, 11))  # Shabbat morning
    s.install(monkeypatch.setattr)
    scheduler._delivering_since = None
    ScriptedLLM(["How's it going?"] * 5).install(monkeypatch.setattr)
    return s


def tick(store):
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))
    return ids


def test_nothing_is_sent_on_shabbat_and_everything_comes_after(store):
    a = store.add_task("Call the bank", remind_at=at(2026, 11, 14, 10), priority=3)
    b = store.add_task("Team sync", kind="meeting", due_at=at(2026, 11, 14, 19), remind_at=at(2026, 11, 14, 11))

    assert tick(store) == [] and store.pushes == []
    assert store.tasks[a["id"]]["remind_at"] == at(2026, 11, 14, 18, 20)
    assert store.tasks[b["id"]]["remind_at"] == at(2026, 11, 14, 18, 20)

    store.now = at(2026, 11, 14, 18, 20)
    assert set(tick(store)) == {a["id"], b["id"]}
    assert len(store.pushes) == 1  # one combined message after Shabbat


class _Clock(datetime):
    fixed: datetime

    @classmethod
    def now(cls, tz=None):
        return cls.fixed.astimezone(tz)


def jobs_at(monkeypatch, when):
    _Clock.fixed = when
    monkeypatch.setattr(scheduler, "datetime", _Clock)
    monkeypatch.setattr(scheduler, "claim_job", lambda job, d: True)
    return scheduler.due_daily_jobs()


def test_daily_jobs_skip_shabbat(monkeypatch):
    assert jobs_at(monkeypatch, at(2026, 11, 14, 7, 35)) == []     # Saturday morning plan
    assert jobs_at(monkeypatch, at(2026, 11, 13, 21, 35)) == []    # Friday evening review
    assert "morning" in jobs_at(monkeypatch, at(2026, 11, 15, 7, 35))


def test_daily_job_runs_right_after_shabbat_if_still_in_its_window(monkeypatch):
    # Summer: Shabbat ends Sat 3 Jul 2027 at 20:32 + 60 → 21:32; "tomorrow's meetings" (20:00) has a 2 h window.
    assert jobs_at(monkeypatch, at(2027, 7, 3, 21, 30)) == []
    assert "meetings_eve" in jobs_at(monkeypatch, at(2027, 7, 3, 21, 35))
