"""Phase 3 pure logic: avoidance, de-escalation, the lapse, fences, the Sunday merge and its chips."""

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from app.logic import (ContactState, Guardrails, avoid_move, chat_signals, chip_lang, chips_for, chips_json,
                       contact_decision, escalates, fence_is_stale, is_parked, lapsed, may_check_in,
                       moved_to_next_week, pick_focus, pick_next, plan_contacts, reentry_due, select_playbooks, text,
                       week_meetings_merged)

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)   # Sun 8 Nov 2026 … Sat 14 Nov 2026


NOW = local(9, 10)
G = Guardrails(quiet_start=time(23), quiet_end=time(7))


def task(**kw):
    return {"id": 1, "kind": "task", "priority": 1, "status": "open", "remind_requested": False, "due_at": None,
            "blocked_until": None, "avoid_asks": 0, "avoid_asked_at": None, "parked_until": None, **kw}


# ---------------------------------------------------------------- avoidance (F7)

def test_avoid_move_ask_wait_ask_park():
    avoided = {"postponed": 2, "ignored": 1}
    assert avoid_move(task(), {"postponed": 1, "ignored": 1}, NOW) is None          # not avoided: check in as usual
    assert avoid_move(task(), avoided, NOW) == "ask"
    asked = task(avoid_asks=1, avoid_asked_at=NOW)
    assert avoid_move(asked, avoided, NOW + timedelta(days=2)) == "wait"
    assert avoid_move(asked, avoided, NOW + timedelta(days=3)) == "ask"             # one more try
    twice = task(avoid_asks=2, avoid_asked_at=NOW)
    assert avoid_move(twice, avoided, NOW + timedelta(days=1)) == "wait"
    assert avoid_move(twice, avoided, NOW + timedelta(days=3)) == "park"            # silence twice: parked


def test_avoid_move_leaves_meetings_fences_and_upcoming_deadlines_alone():
    avoided = {"ignored": 5}
    assert avoid_move(task(kind="meeting"), avoided, NOW) is None
    assert avoid_move(task(kind="fence"), avoided, NOW) is None
    assert avoid_move(task(due_at=NOW + timedelta(hours=20)), avoided, NOW) is None  # due tomorrow: keep reminding
    assert avoid_move(task(due_at=NOW - timedelta(days=3)), avoided, NOW) == "ask"   # long overdue: avoided
    assert avoid_move(task(due_at=NOW + timedelta(days=5)), avoided, NOW) == "ask"


def test_avoided_chips_and_texts_in_both_languages():
    chips = chips_json(chips_for("avoided", "avoided", "en"), "en", 7)
    assert [(c["id"], c.get("action")) for c in chips] == [("too_big", None), ("unclear", None), ("boring", None),
                                                           ("not_now", "not_now")]
    assert chips[-1]["task_id"] == 7
    assert chips_json(chips_for("avoided", None, "he"), "he", 7)[0]["label"] == "גדול מדי"
    assert text("avoid_ask", "en", title="Taxes") == "Taxes keeps slipping, and that's fine. What's in the way?"
    assert "מה מפריע" in text("avoid_ask", chip_lang("מיסים"), title="מיסים")


# ---------------------------------------------------------------- de-escalation

def test_escalates_only_fixed_events_asked_for_reminders_and_near_deadlines():
    assert not escalates(task(priority=3), NOW)
    assert escalates(task(remind_requested=True), NOW)
    assert escalates(task(kind="meeting"), NOW) and escalates(task(kind="fence"), NOW)
    assert escalates(task(due_at=NOW + timedelta(hours=47)), NOW)
    assert escalates(task(due_at=NOW - timedelta(hours=47)), NOW)
    assert not escalates(task(due_at=NOW + timedelta(hours=49)), NOW)
    assert not escalates(task(due_at=NOW - timedelta(days=5)), NOW)


def test_one_check_in_a_day_for_tasks_that_dont_escalate():
    once = ContactState(sent_today=1, sent_today_by_task={1: 1})
    assert contact_decision(task(), once, NOW, G) == local(10, 7)                      # tomorrow, after quiet hours
    assert contact_decision(task(due_at=NOW + timedelta(hours=5)), once, NOW, G) is None   # a deadline today: up to 4
    assert contact_decision(task(remind_requested=True), once, NOW, G) is None             # asked for: no cap
    assert contact_decision(task(), ContactState(), NOW, G) is None


# ---------------------------------------------------------------- fences (F9)

def test_fences_skip_the_gap_the_caps_and_the_focus_hold_but_not_quiet_hours():
    busy = ContactState(last_proactive_at=NOW - timedelta(minutes=2), sent_today=20,
                        focus_hold_until=NOW + timedelta(minutes=30))
    fence = task(kind="fence", due_at=NOW)
    assert contact_decision(fence, busy, NOW, G) is None
    assert contact_decision(task(), busy, NOW, G) is not None
    night = local(9, 23, 30)
    assert contact_decision(fence, ContactState(), night, G) == local(10, 7)
    rest = Guardrails(quiet_start=time(23), quiet_end=time(7), rest_until=lambda now: local(14, 19))
    assert contact_decision(fence, ContactState(), NOW, rest) == local(14, 19)          # Shabbat is never overruled


def test_fences_lead_the_message_with_meetings_and_go_stale():
    f = task(id=2, kind="fence", due_at=NOW)
    plan = plan_contacts([task(id=1, remind_at=NOW), f], ContactState(), NOW, G)
    assert [t["id"] for t in plan.send] == [2, 1]
    assert not fence_is_stale(f, NOW + timedelta(minutes=30))
    assert fence_is_stale(f, NOW + timedelta(minutes=31))


def test_what_now_never_picks_past_a_fence():
    t = task(id=1, estimate_min=40, next_action="Write")
    pick = pick_next([t], NOW, NOW + timedelta(minutes=20), today=NOW.date())
    assert (pick.reason, pick.minutes) == ("short_start", 5)


# ---------------------------------------------------------------- parking

def test_parked_tasks_get_no_check_ins_and_arent_offered():
    parked = task(parked_until=NOW + timedelta(days=3), focus_date=NOW.date(), due_at=NOW)
    assert is_parked(parked, NOW) and not is_parked(parked, NOW + timedelta(days=4))
    assert not may_check_in(parked, NOW, NOW.date())
    assert not may_check_in({**parked, "remind_requested": True}, NOW, NOW.date())
    assert pick_focus([parked], NOW) == []
    assert pick_next([parked], NOW, None, today=NOW.date()) is None
    assert may_check_in({**parked, "kind": "fence"}, NOW, NOW.date())


def test_an_asked_avoided_task_stays_due_for_its_last_try():
    assert may_check_in(task(avoid_asks=1), NOW, NOW.date())
    assert not may_check_in(task(), NOW, NOW.date())


# ---------------------------------------------------------------- the lapse and re-entry

def test_lapse_is_48_hours_without_activity():
    assert not lapsed(None, NOW)                                  # a new install hasn't lapsed
    assert not lapsed(NOW - timedelta(hours=47), NOW)
    assert lapsed(NOW - timedelta(hours=48), NOW)


def test_shabbat_and_holidays_dont_count_toward_the_lapse():
    shabbat = (local(13, 15), local(14, 19))                      # Fri 15:00 – Sat 19:00: 28 hours
    last = local(13, 9)                                           # Friday morning
    assert not lapsed(last, local(15, 9), [shabbat])              # Sunday 09:00: 48 h on the clock, 20 h that count
    assert not lapsed(last, local(16, 12), [shabbat])             # 75 h − 28 h = 47 h
    assert lapsed(last, local(16, 13), [shabbat])
    assert lapsed(last, local(15, 9), [(local(6, 15), local(7, 19))])   # an earlier Shabbat changes nothing


def test_lapse_stops_task_check_ins_only():
    today = NOW.date()
    assert not may_check_in(task(focus_date=today), NOW, today, lapsed=True)
    assert not may_check_in(task(due_at=NOW), NOW, today, lapsed=True)
    assert may_check_in(task(remind_requested=True), NOW, today, lapsed=True)     # they asked for this one
    assert may_check_in(task(kind="meeting"), NOW, today, lapsed=True)
    assert may_check_in(task(kind="fence"), NOW, today, lapsed=True)


def test_one_reentry_message_per_lapse_then_weekly():
    active = NOW - timedelta(days=3)
    assert reentry_due(active, None, NOW)
    assert reentry_due(active, active - timedelta(days=30), NOW)            # from an earlier lapse
    assert not reentry_due(active, NOW - timedelta(days=1), NOW)            # already sent in this one
    assert reentry_due(active - timedelta(days=9), NOW - timedelta(days=7), NOW)


def test_reentry_chips_and_playbooks():
    chips = chips_json(chips_for("reentry", None, "en"), "en", None)
    assert [(c["id"], c.get("action")) for c in chips] == [("pick_one", None), ("move_overdue", "move_overdue")]
    assert select_playbooks("reentry") == ["reentry"]
    assert "reentry" in chat_signals("Pick one for me", [], False, NOW, 0)
    assert select_playbooks(None, [], {"reentry"}) == ["reentry"]
    assert text("reentry", "en") == "Good to see you. Want me to pick one thing for today?"


def test_move_overdue_keeps_the_weekday_and_time():
    assert moved_to_next_week(local(8, 9), NOW) == local(15, 9)             # yesterday → next Sunday
    assert moved_to_next_week(local(8, 9) - timedelta(days=21), NOW) == local(15, 9)   # weeks old: still next week


# ---------------------------------------------------------------- Sunday (F8)

def test_week_meetings_join_the_morning_plan_within_an_hour():
    assert week_meetings_merged(time(7, 30), time(8))
    assert week_meetings_merged(time(8), time(7))
    assert not week_meetings_merged(time(7, 30), time(8, 31))
    assert not week_meetings_merged(time(7, 30), None)                       # the digest is switched off


def test_sunday_chips_act_on_exactly_the_listed_tasks():
    chips = chips_json(chips_for("sunday", None, "en"), "en", None, [4, 9])
    assert [(c["id"], c["action"], c["task_ids"]) for c in chips] == [("drop", "drop", [4, 9]), ("keep", "keep", [4, 9])]
    assert chips_json(chips_for("sunday", None, "en"), "en", None) == []     # nothing stale: no buttons
    assert select_playbooks("morning", signals={"sunday"}) == ["planning", "sunday"]
    assert text("stale", "en", titles="A · B").startswith("🧹 Open for 3+ weeks with no progress: A · B.")
    assert text("wins_week", "en", n=9) == "✅ Last week you finished 9 things."
