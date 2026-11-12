"""Extra meeting reminders: the week's meetings at the start of the week, tomorrow's the evening before."""

import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore

from app import scheduler
from app.logic import meeting_occurrences

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)  # Sun 8 Nov 2026 … Sat 14 Nov 2026


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(8, 8))  # Sunday 08:00
    s.install(monkeypatch.setattr)
    return s


def run(job):
    asyncio.run(scheduler.run_daily_job(job))


# ---------------------------------------------------------------- pure logic

def test_occurrences_in_window_sorted():
    a = {"id": 1, "due_at": local(10, 9), "recurrence": None}
    b = {"id": 2, "due_at": local(9, 14), "recurrence": None}
    late = {"id": 3, "due_at": local(15, 9), "recurrence": None}
    got = meeting_occurrences([a, b, late], local(8, 8), local(15, 0), TZ)
    assert [(w, m["id"]) for w, m in got] == [(local(9, 14), 2), (local(10, 9), 1)]


def test_recurring_meeting_is_expanded_and_rolled_forward():
    stale_weekly = {"id": 1, "due_at": local(1, 10), "recurrence": "weekly"}   # last Sunday, never rolled
    daily = {"id": 2, "due_at": local(12, 9), "recurrence": "daily"}
    got = meeting_occurrences([stale_weekly, daily], local(8, 8), local(15, 0), TZ)
    assert [(w, m["id"]) for w, m in got] == [
        (local(8, 10), 1), (local(12, 9), 2), (local(13, 9), 2), (local(14, 9), 2)]


# ---------------------------------------------------------------- start of the week

def test_week_digest_lists_the_coming_week(store):
    m1 = store.add_task("Dentist", kind="meeting", due_at=local(10, 9, 30))
    m2 = store.add_task("Team sync", kind="meeting", due_at=local(8, 11))
    store.add_task("Already over", kind="meeting", due_at=local(8, 7))
    store.add_task("Next week", kind="meeting", due_at=local(15, 10))
    store.add_task("Pay bill", due_at=local(9, 12))  # not a meeting

    run("week_meetings")

    assert len(store.messages) == 1 and len(store.pushes) == 1
    msg, push = store.messages[0], store.pushes[0]
    assert msg["content"] == ("📅 Your meetings this week:\n"
                              "Today 11:00 — Team sync\n"
                              "Tue 10/11 09:30 — Dentist")
    assert msg["kind"] == "meeting" and msg["task_ids"] == [m2["id"], m1["id"]]
    assert push["title"] == "📅 This week's meetings" and push["message_id"] == msg["id"]
    assert push["task_id"] is None  # no ✓ / ⏰ on a digest


def test_week_digest_says_nothing_without_meetings(store):
    store.add_task("Pay bill", due_at=local(9, 12))
    run("week_meetings")
    assert store.messages == [] and store.pushes == []


# ---------------------------------------------------------------- the evening before

def test_evening_digest_lists_tomorrows_meetings(store):
    store.now = local(9, 20).astimezone(store.now.tzinfo)  # Monday 20:00
    store.add_task("Dentist", kind="meeting", due_at=local(10, 9, 30))
    store.add_task("Lunch with Dani", kind="meeting", due_at=local(10, 13))
    store.add_task("Wednesday thing", kind="meeting", due_at=local(11, 9))
    store.add_task("Tonight", kind="meeting", due_at=local(9, 21))

    run("meetings_eve")

    assert store.messages[0]["content"] == ("📅 Tomorrow (Tue 10/11):\n"
                                            "09:30 — Dentist\n"
                                            "13:00 — Lunch with Dani")
    assert store.pushes[0]["title"] == "📅 Tomorrow's meetings"


def test_evening_digest_skips_done_and_cancelled(store):
    store.now = local(9, 20).astimezone(store.now.tzinfo)
    store.add_task("Cancelled", kind="meeting", due_at=local(10, 9), status="cancelled")
    run("meetings_eve")
    assert store.messages == []


def test_digest_counts_as_a_proactive_message(store):
    store.add_task("Team sync", kind="meeting", due_at=local(8, 11))
    run("week_meetings")
    state = scheduler.thread.contact_state(local(8, 4))
    assert state.sent_today == 1 and state.last_proactive_at == store.now


# ---------------------------------------------------------------- scheduling

class _Clock(datetime):
    fixed: datetime

    @classmethod
    def now(cls, tz=None):
        return cls.fixed.astimezone(tz)


def due_jobs(monkeypatch, at):
    claimed = set()
    _Clock.fixed = at
    monkeypatch.setattr(scheduler, "datetime", _Clock)
    monkeypatch.setattr(scheduler, "claim_job",
                        lambda job, d: (job, d) not in claimed and not claimed.add((job, d)))
    return scheduler.due_daily_jobs


def far_from_morning(monkeypatch):
    """The week's meetings at 11:00: more than an hour from the 07:30 morning plan, so a message of their own."""
    from dataclasses import replace
    from datetime import time
    s = replace(scheduler.get_settings(), week_meetings_time=time(11))
    monkeypatch.setattr(scheduler, "get_settings", lambda: s)


def test_week_job_only_on_the_first_day_of_the_week(monkeypatch):
    far_from_morning(monkeypatch)
    assert "week_meetings" in due_jobs(monkeypatch, local(8, 11, 5))()      # Sunday
    assert "week_meetings" not in due_jobs(monkeypatch, local(9, 11, 5))()  # Monday


def test_week_job_joins_the_morning_plan_when_within_an_hour(monkeypatch):
    jobs = due_jobs(monkeypatch, local(8, 8, 5))()                          # Sunday, defaults 07:30 / 08:00
    assert "morning" in jobs and "week_meetings" not in jobs


def test_evening_job_every_day_once(monkeypatch):
    jobs = due_jobs(monkeypatch, local(10, 20, 5))
    assert "meetings_eve" in jobs()
    assert "meetings_eve" not in jobs()  # already claimed today
    assert "meetings_eve" not in due_jobs(monkeypatch, local(10, 19, 55))()


def test_digests_can_be_turned_off(monkeypatch):
    from dataclasses import replace
    s = replace(scheduler.get_settings(), week_meetings_time=None, meetings_eve_time=None)
    monkeypatch.setattr(scheduler, "get_settings", lambda: s)
    assert not {"week_meetings", "meetings_eve"} & set(due_jobs(monkeypatch, local(8, 8, 5))())
    assert not {"week_meetings", "meetings_eve"} & set(due_jobs(monkeypatch, local(8, 20, 5))())
