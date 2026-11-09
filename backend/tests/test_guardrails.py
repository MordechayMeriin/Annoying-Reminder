"""Guardrails for proactive contact, follow-up clamping and reply → task linking."""

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.logic import (ContactState, Guardrails, clamp_followup, contact_decision, day_start,
                       link_task_ids, next_morning, plan_contacts, select_candidate_tasks)

TZ = ZoneInfo("Asia/Jerusalem")
G = Guardrails(quiet_start=time(23), quiet_end=time(7), day_reset=time(4), min_gap_min=20,
               max_per_day=20, max_per_task_per_day=4, followup_min_min=10, followup_max_days=7)


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


def task(id_, kind="task", priority=1, **kw):
    return {"id": id_, "kind": kind, "priority": priority, "blocked_until": None,
            "due_at": None, "remind_at": None, **kw}


# ---------------------------------------------------------------- contact_decision (spec rules)

def test_free_to_contact():
    assert contact_decision(task(1), ContactState(), local(9, 10), G) is None


def test_quiet_hours_postpone_until_morning():
    assert contact_decision(task(1), ContactState(), local(9, 23, 30), G) == local(10, 7)
    assert contact_decision(task(1, kind="meeting", priority=2), ContactState(), local(10, 6), G) == local(10, 7)


def test_quiet_hours_are_never_overruled():
    assert contact_decision(task(1, priority=3), ContactState(), local(9, 23, 30), G) == local(10, 7)
    assert contact_decision(task(2, kind="meeting", priority=3), ContactState(), local(10, 6, 50), G) == local(10, 7)


def test_min_gap_postpones_tasks_but_not_meetings():
    st = ContactState(last_proactive_at=local(9, 10, 0), sent_today=1)
    assert contact_decision(task(1), st, local(9, 10, 5), G) == local(9, 10, 20)
    assert contact_decision(task(2, kind="meeting"), st, local(9, 10, 5), G) is None
    assert contact_decision(task(1), st, local(9, 10, 20), G) is None  # gap fully elapsed


def test_daily_cap_postpones_to_next_morning():
    st = ContactState(last_proactive_at=local(9, 8), sent_today=20)
    assert contact_decision(task(1), st, local(9, 15), G) == local(10, 7)


def test_meetings_skip_daily_cap():
    st = ContactState(last_proactive_at=local(9, 16, 40), sent_today=20)
    assert contact_decision(task(1, kind="meeting"), st, local(9, 16, 45), G) is None


def test_per_task_cap_only_affects_that_task():
    st = ContactState(last_proactive_at=local(9, 8), sent_today=5, sent_today_by_task={1: 4})
    assert contact_decision(task(1), st, local(9, 15), G) == local(10, 7)
    assert contact_decision(task(2), st, local(9, 15), G) is None


def test_requested_reminder_ignores_per_task_cap_but_not_daily_cap():
    st = ContactState(last_proactive_at=local(9, 8), sent_today=5, sent_today_by_task={1: 4})
    assert contact_decision(task(1, remind_requested=True), st, local(9, 15), G) is None
    full = ContactState(last_proactive_at=local(9, 8), sent_today=20, sent_today_by_task={1: 4})
    assert contact_decision(task(1, remind_requested=True), full, local(9, 15), G) == local(10, 7)


def test_blocked_waits_until_blocked_until():
    t = task(1, blocked_until=local(9, 18))
    assert contact_decision(t, ContactState(), local(9, 15), G) == local(9, 18)
    assert contact_decision(t, ContactState(), local(9, 18, 5), G) is None


def test_latest_postponement_wins():
    # blocked until 06:00, but quiet hours last until 07:00 → straight to 07:00
    t = task(1, priority=3, blocked_until=local(10, 6))
    assert contact_decision(t, ContactState(), local(9, 23, 30), G) == local(10, 7)
    # gap ends 10:20, but blocked until 12:00 → 12:00
    st = ContactState(last_proactive_at=local(9, 10), sent_today=1)
    assert contact_decision(task(2, blocked_until=local(9, 12)), st, local(9, 10, 5), G) == local(9, 12)


def test_next_morning_waits_for_counter_reset():
    assert next_morning(local(9, 15), G) == local(10, 7)
    # 06:00 is after today's 04:00 reset: counters only reset tomorrow, then wait for quiet end
    assert next_morning(local(9, 6), G) == local(10, 7)
    late_reset = Guardrails(quiet_start=time(23), quiet_end=time(7), day_reset=time(9))
    assert next_morning(local(9, 15), late_reset) == local(10, 9)


# ---------------------------------------------------------------- grouping

def test_due_tasks_grouped_into_one_message_meetings_first():
    due = [
        task(1, priority=1, remind_at=local(9, 9)),
        task(2, priority=3, remind_at=local(9, 10)),
        task(3, kind="meeting", due_at=local(9, 11)),
    ]
    plan = plan_contacts(due, ContactState(), local(9, 10, 50), G)
    assert [t["id"] for t in plan.send] == [3, 2, 1]
    assert plan.defer == {}


def test_grouping_with_gap_keeps_meeting_and_defers_rest():
    st = ContactState(last_proactive_at=local(9, 10, 40), sent_today=3)
    due = [task(1), task(3, kind="meeting", due_at=local(9, 11))]
    plan = plan_contacts(due, st, local(9, 10, 45), G)
    assert [t["id"] for t in plan.send] == [3]
    assert plan.defer == {1: local(9, 11, 0)}


# ---------------------------------------------------------------- follow-ups and day boundary

def test_clamp_followup():
    now = local(9, 10)
    assert clamp_followup(now, now + timedelta(minutes=2), G) == now + timedelta(minutes=10)
    assert clamp_followup(now, now + timedelta(days=30), G) == now + timedelta(days=7)
    assert clamp_followup(now, now + timedelta(hours=2), G) == now + timedelta(hours=2)


def test_day_start_resets_at_4am_not_midnight():
    assert day_start(local(9, 0, 30), time(4)) == local(8, 4)
    assert day_start(local(9, 4, 0), time(4)) == local(9, 4)
    assert day_start(local(9, 22), time(4)) == local(9, 4)


# ---------------------------------------------------------------- reply → task

OPEN = [{"id": 7, "title": "Call the plumber"}, {"id": 8, "title": "לשלם ארנונה"}, {"id": 9, "title": "Gym"}]


def test_candidates_recent_checkin_first():
    last = {"created_at": local(9, 9), "task_ids": [9]}
    got = select_candidate_tasks("ugh, the plumber again", local(9, 10), last, [], OPEN, 3)
    assert got == [9, 7]


def test_candidates_old_checkin_ignored_hebrew_match():
    last = {"created_at": local(9, 5), "task_ids": [9]}
    got = select_candidate_tasks("שילמתי ארנונה", local(9, 10), last, [], OPEN, 3)
    assert got == [8]


def test_candidates_limit_and_dedupe():
    last = {"created_at": local(9, 9), "task_ids": [9, 7]}
    got = select_candidate_tasks("plumber", local(9, 10), last, [7, 8], OPEN, 3, limit=3)
    assert got == [9, 7, 8]


def test_link_follows_actions_then_unambiguous_checkin():
    assert link_task_ids([5, 5, 6], [9]) == [5, 6]
    assert link_task_ids([], [9]) == [9]
    assert link_task_ids([], [9, 7]) == []
    assert link_task_ids([], []) == []
