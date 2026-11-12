"""Pure Phase 2 logic: What now? (pick_next), session timing, the focus hold, breadcrumbs, template texts."""

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.logic import (ContactState, Guardrails, actual_minutes, chips_for, chips_json, contact_decision,
                       focus_hold_until, pick_next, session_close_outcome, session_end_due, session_last_active,
                       session_length, session_silent, session_state, session_text, step_of, wants_breadcrumb,
                       what_now_text)

TZ = ZoneInfo("Asia/Jerusalem")
TODAY = date(2026, 11, 9)
G = Guardrails(quiet_start=datetime(2026, 1, 1, 23).time(), quiet_end=datetime(2026, 1, 1, 7).time())


def at(h, mi=0, d=9):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


def task(i, **kw):
    t = {"id": i, "title": f"Task {i}", "kind": "task", "status": "open", "priority": 1, "due_at": None,
         "parent_id": None, "blocked_until": None, "focus_date": None, "focus_rank": None, "next_action": None,
         "estimate_min": None, "created_at": at(8)}
    t.update(kw)
    return t


def focus(i, rank, **kw):
    return task(i, focus_date=TODAY, focus_rank=rank, **kw)


def pick(tasks, now=None, meeting=None, **kw):
    return pick_next(tasks, now or at(10), meeting, today=TODAY, **kw)


# ---------------------------------------------------------------- pick_next

def test_first_focus_task_when_it_fits():
    p = pick([focus(1, 1, estimate_min=10), focus(2, 2, estimate_min=5)], meeting=at(11))
    assert (p.task["id"], p.reason, p.estimate, p.minutes) == (1, "fits", 15, 15)


def test_never_longer_than_the_time_to_the_next_meeting():
    # 60 min × 1.5 = 90 doesn't fit in 40 minutes; the shortest focus task that fits does
    tasks = [focus(1, 1, estimate_min=60), focus(2, 2, estimate_min=20), focus(3, 3, estimate_min=10)]
    p = pick(tasks, meeting=at(10, 40))
    assert (p.task["id"], p.reason) == (3, "fits")
    assert p.minutes <= 40 and p.estimate <= 40


def test_session_length_is_capped_by_the_meeting():
    p = pick([focus(1, 1, estimate_min=20)], meeting=at(10, 30))   # est 30 → session 45 > 30 → 25
    assert (p.reason, p.minutes) == ("fits", 25)


def test_nothing_fits_gives_a_5_minute_start_of_the_first():
    p = pick([focus(1, 1, estimate_min=60), focus(2, 2, estimate_min=45)], meeting=at(10, 20))
    assert (p.task["id"], p.reason, p.minutes) == (1, "short_start", 5)


def test_no_meeting_means_everything_fits():
    p = pick([focus(1, 1, estimate_min=240)])
    assert (p.task["id"], p.reason, p.minutes) == (1, "fits", 45)


def test_unknown_estimate_counts_as_15_minutes():
    p = pick([focus(1, 1)], meeting=at(10, 30))
    assert (p.reason, p.estimate, p.minutes) == ("fits", 23, 25)


def test_low_energy_picks_the_smallest_short_task():
    tasks = [focus(1, 1, estimate_min=60), focus(2, 2, estimate_min=15), task(3, estimate_min=5),
             task(4)]   # unknown estimate: a guess loses to a known 15
    p = pick(tasks, low_energy=True)
    assert (p.task["id"], p.reason, p.minutes) == (3, "small", 15)
    p = pick([focus(1, 1, estimate_min=60), focus(2, 2, estimate_min=15), task(4)], low_energy=True)
    assert p.task["id"] == 2


def test_low_energy_with_nothing_small_offers_5_minutes_of_the_top_one():
    p = pick([focus(1, 1, estimate_min=60), task(2, estimate_min=30)], low_energy=True)
    assert (p.task["id"], p.reason, p.minutes) == (1, "short_start", 5)


def test_something_else_gives_the_next_candidate_then_starts_over():
    tasks = [focus(1, 1), focus(2, 2), task(3)]
    assert pick(tasks, exclude=[1]).task["id"] == 2
    assert pick(tasks, exclude=[1, 2]).task["id"] == 3
    assert pick(tasks, exclude=[1, 2, 3]).task["id"] == 1   # always returns something


def test_without_focus_tasks_due_soonest_then_priority_then_oldest():
    tasks = [task(1, priority=3, created_at=at(7)), task(2, due_at=at(18)), task(3, due_at=at(12)),
             task(4, created_at=at(6)), task(5, priority=3, created_at=at(8))]
    order = []
    for _ in tasks:
        order.append(pick(tasks, exclude=order).task["id"])
    assert order == [3, 2, 1, 5, 4]


def test_all_focus_done_offers_one_bonus_task():
    tasks = [focus(1, 1, status="done"), task(2, estimate_min=10)]
    p = pick([t for t in tasks if t["status"] == "open"], done_ids={1})
    assert (p.task["id"], p.reason) == (2, "bonus")


def test_all_focus_done_and_nothing_else_open():
    p = pick([], done_ids={1})
    assert p.task is None and p.reason == "all_done"
    assert pick([]) is None


def test_skips_meetings_steps_blocked_and_done_today():
    tasks = [task(1, kind="meeting"), task(2, parent_id=9), task(3, blocked_until=at(12)),
             focus(4, 1, recurrence="daily"), task(5)]
    assert pick(tasks, done_ids={4}).task["id"] == 5   # 4 was done today (a recurring task rolls on)


# ---------------------------------------------------------------- texts

def test_what_now_texts_in_both_languages():
    t = task(1, title="Taxes", next_action="Find last year's PDF")
    p = pick([dict(t, focus_date=TODAY, focus_rank=1, estimate_min=10)])
    assert what_now_text(p, "en") == "Do this: Find last year's PDF — Taxes (15 min)."
    assert what_now_text(p, "he") == "עכשיו: Find last year's PDF — Taxes (15 דק׳)."
    short = pick([dict(t, focus_date=TODAY, focus_rank=1, estimate_min=90)], meeting=at(10, 20))
    assert what_now_text(short, "en", at(10, 20)) == "Before 10:20: just 5 minutes of this: Find last year's PDF — Taxes."
    assert what_now_text(None, "en").startswith("Nothing open right now")
    assert what_now_text(pick([], done_ids={1}), "he").startswith("כל שלוש המשימות")


def test_step_of_and_session_text():
    assert step_of(task(1, title="Gym")) == "Gym"
    t = task(1, title="Taxes", next_action="Open the form")
    assert session_text("session_end", t, 25, "en") == "⏱ Time's up: Open the form. Done, another round, or stop?"
    assert session_text("session_end", t, 25, "he").startswith("⏱ הזמן נגמר: Open the form")


def test_what_now_chips_need_no_task_and_session_end_chips_do():
    what_now = chips_json(chips_for("what_now", None, "en"), "en", 7)
    assert [(c["id"], c.get("action"), c.get("task_id")) for c in what_now] == [
        ("start", "start", 7), ("low_energy", "what_now", None), ("something_else", "what_now", None)]
    assert [c["id"] for c in chips_json(chips_for("midday", None, "en"), "en", None)] == ["what_now"]
    assert [c["task_id"] for c in chips_json(chips_for("session_end", None, "en"), "en", 7)] == [7, 7, 7]


# ---------------------------------------------------------------- sessions

def session(start=at(10), planned=25, **kw):
    s = {"id": 1, "task_id": 1, "started_at": start, "planned_min": planned, "ended_at": None, "outcome": None,
         "end_sent_at": None, "note_closed_at": None}
    s.update(kw)
    return s


@pytest.mark.parametrize("given, length", [(None, 5), (3, 5), (5, 5), (7, 10), (25, 25), (44, 45), (500, 120)])
def test_session_length_is_a_multiple_of_5(given, length):
    assert session_length(given) == length


def test_session_states_and_the_end_check_in():
    s = session()
    assert session_state(s, at(10, 24)) == "running" and not session_end_due(s, at(10, 24))
    assert session_state(s, at(10, 25)) == "ended" and session_end_due(s, at(10, 25))
    assert not session_end_due(dict(s, end_sent_at=at(10, 25)), at(10, 30))
    assert session_state(dict(s, ended_at=at(10, 12)), at(10, 30)) == "closed"
    assert session_state(None, at(10)) is None


def test_silence_15_minutes_after_the_end_check_in_means_paused():
    s = session(end_sent_at=at(10, 28))
    assert not session_silent(s, at(10, 42))
    assert session_silent(s, at(10, 43))
    # the check-in never went out (quiet hours): 15 minutes after the end
    assert session_silent(session(), at(10, 40)) and not session_end_due(session(), at(10, 40))
    assert not session_silent(session(ended_at=at(10, 5)), at(12))


def test_closing_an_open_session_when_another_starts():
    assert session_close_outcome(session(), at(10, 10)) == ("stopped", at(10, 10))
    assert session_close_outcome(session(), at(10, 30)) == ("paused", at(10, 25))


def test_actual_minutes():
    assert actual_minutes(session(ended_at=at(10, 12)), at(11)) == 12
    assert actual_minutes(session(), at(10, 7)) == 7


def test_focus_hold_until_the_session_ends_plus_10():
    assert focus_hold_until(session()) == at(10, 35)
    assert focus_hold_until(session(ended_at=at(10, 5))) == at(10, 15)   # Done / Stop early
    assert focus_hold_until(None) is None


def test_session_last_active():
    assert session_last_active(session(), at(10, 10)) == at(10, 10)
    assert session_last_active(session(), at(11)) == at(10, 25)
    assert session_last_active(session(ended_at=at(10, 25)), at(12)) == at(10, 25)


def test_breadcrumb_offered_after_stop_or_silence_only_for_a_while():
    stopped = session(ended_at=at(10, 12), outcome="stopped")
    assert wants_breadcrumb(stopped, at(11))
    assert wants_breadcrumb(dict(stopped, outcome="paused"), at(11))
    assert not wants_breadcrumb(dict(stopped, outcome="done"), at(11))
    assert not wants_breadcrumb(dict(stopped, note_closed_at=at(10, 13)), at(11))
    assert not wants_breadcrumb(stopped, at(13, 12))
    assert not wants_breadcrumb(session(), at(10, 5))


# ---------------------------------------------------------------- the focus hold in contact_decision

def test_no_check_in_during_a_session_but_meetings_and_its_end_come_through():
    st = ContactState(focus_hold_until=at(10, 35))
    assert contact_decision(task(1), st, at(10, 10), G) == at(10, 35)
    assert contact_decision(task(1, remind_requested=True), st, at(10, 10), G) == at(10, 35)
    assert contact_decision(task(2, kind="meeting"), st, at(10, 10), G) is None
    assert contact_decision(task(3, kind="fence"), st, at(10, 10), G) is None
    end = {"id": 1, "kind": "session", "remind_requested": True, "blocked_until": None}
    assert contact_decision(end, st, at(10, 25), G) is None
    assert contact_decision(task(1), st, at(10, 36), G) is None


def test_session_end_skips_the_gap_and_task_cap_but_not_quiet_hours_or_the_daily_cap():
    end = {"id": 1, "kind": "session", "remind_requested": True, "blocked_until": None}
    busy = ContactState(last_proactive_at=at(10, 20), sent_today=3, sent_today_by_task={1: 9})
    assert contact_decision(end, busy, at(10, 25), G) is None
    assert contact_decision(end, ContactState(), at(23, 10), G) == at(7, d=10)
    assert contact_decision(end, ContactState(sent_today=20), at(10, 25), G) is not None


def test_gap_still_applies_to_ordinary_check_ins():
    st = ContactState(last_proactive_at=at(10, 0), sent_today=1)
    assert contact_decision(task(1), st, at(10, 10), G) == at(10, 20)


def test_hold_in_the_past_changes_nothing():
    assert contact_decision(task(1), ContactState(focus_hold_until=at(9)), at(10), G) is None


def test_timedelta_sanity():
    assert at(10) + timedelta(minutes=25) == at(10, 25)
