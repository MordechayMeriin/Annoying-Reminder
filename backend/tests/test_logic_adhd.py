"""Pure Phase 1 logic: reply buttons, Later, stages, playbook choice, Today's 3 and the check-in filter."""

from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.logic import (CHIP_LABELS, CHIPS_BY_KIND, chat_signals, chip_id_for_text, chip_lang, chips_for, chips_json,
                       focus_day, later_time, may_check_in, pick_focus, select_playbooks, task_stage,
                       user_active_recently)

TZ = ZoneInfo("Asia/Jerusalem")
TODAY = date(2026, 11, 9)


def at(h, mi=0, d=9):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


def task(i, **kw):
    t = {"id": i, "kind": "task", "status": "open", "priority": 1, "due_at": None, "parent_id": None,
         "blocked_until": None, "remind_requested": False, "focus_date": None, "next_action": None,
         "created_at": at(8)}
    t.update(kw)
    return t


# ---------------------------------------------------------------- chips

@pytest.mark.parametrize("kind, ids", [
    ("checkin", ["start5", "done", "later", "stuck"]),
    ("morning", ["start_first", "change"]),
    ("session_end", ["done", "another", "stop"]),
    ("avoided", ["too_big", "unclear", "boring", "not_now"]),
    ("reentry", ["pick_one", "move_overdue"]),
    ("sunday", ["drop", "keep"]),
    ("meeting", []),
    ("midday", ["start_next", "what_now"]),
    ("what_now", ["start", "low_energy", "something_else"]),
    ("chat_task", ["start5", "done"]),
    ("evening", []),
])
def test_chips_per_kind(kind, ids):
    assert [c.id for c in chips_for(kind, "clarified", "en")] == ids


def test_every_proactive_kind_with_chips_has_two_to_four():
    for kind, chips in CHIPS_BY_KIND.items():
        assert len(chips) == 0 or 2 <= len(chips) <= 4, kind


def test_done_task_gets_no_chips():
    assert chips_for("checkin", "done", "en") == [] and chips_for("chat_task", "cancelled", "he") == []


def test_chip_labels_follow_the_language():
    en = chips_json(chips_for("checkin", None, "en"), "en", 7)
    he = chips_json(chips_for("checkin", None, "he"), "he", 7)
    assert [c["label"] for c in en] == ["▶ Start 5 min", "✓ Done", "Later", "I'm stuck"]
    assert he[0]["label"] == "▶ להתחיל 5 דק׳" and he[3]["label"] == "נתקעתי"
    assert en[0] == {"id": "start5", "label": "▶ Start 5 min", "action": "start", "minutes": 5, "task_id": 7}
    assert en[3] == {"id": "stuck", "label": "I'm stuck"}  # a text chip: sends its label
    assert all(len(v) == 2 and all(v) for v in CHIP_LABELS.values())


def test_action_chips_without_a_task_are_dropped():
    assert [c["id"] for c in chips_json(chips_for("midday", None, "en"), "en", None)] == ["what_now"]


def test_chip_language_rule():
    assert chip_lang("שלום מה קורה") == "he"
    assert chip_lang("hi there", "לשלם ארנונה") == "en"          # the latest message wins
    assert chip_lang(None, "לשלם ארנונה") == "he"                # failing that, the title
    assert chip_lang("", "Taxes") == "en"


def test_chip_text_is_recognised_in_both_languages():
    assert chip_id_for_text("I'm stuck") == "stuck" and chip_id_for_text(" נתקעתי ") == "stuck"
    assert chip_id_for_text("I'm stuck on the form") is None


# ---------------------------------------------------------------- Later, stages, activity

def test_later_is_two_hours_before_six_pm_else_next_morning():
    assert later_time(at(10), time(7, 30)) == at(12)
    assert later_time(at(17, 59), time(7, 30)) == at(19, 59)
    assert later_time(at(18), time(7, 30)) == at(7, 30, d=10)
    assert later_time(at(23, 30), time(7, 30)) == at(7, 30, d=10)


def test_focus_day_follows_the_day_reset():
    assert focus_day(at(3, 59), time(4)) == date(2026, 11, 8)
    assert focus_day(at(4), time(4)) == TODAY


def test_task_stages():
    assert task_stage(task(1), {}, TODAY) == "captured"
    assert task_stage(task(1, next_action="Open the form"), {}, TODAY) == "clarified"
    assert task_stage(task(1, next_action="x", focus_date=TODAY), {}, TODAY) == "planned"
    assert task_stage(task(1, focus_date=date(2026, 11, 8)), {}, TODAY) == "captured"  # yesterday's focus
    assert task_stage(task(1, focus_date=TODAY), {"postponed": 2, "ignored": 1}, TODAY) == "avoided"
    assert task_stage(task(1, status="done"), {"postponed": 5}, TODAY) == "done"


def test_user_active_recently():
    assert user_active_recently(at(9, 30), at(10))
    assert not user_active_recently(at(9), at(10))
    assert not user_active_recently(None, at(10))


# ---------------------------------------------------------------- playbooks

def test_select_playbooks():
    assert select_playbooks("morning") == ["planning"]
    assert select_playbooks("morning", signals={"sunday"}) == ["planning", "sunday"]
    assert select_playbooks("reentry") == ["reentry"]
    assert select_playbooks(None, ["planned", "avoided"]) == ["avoiding"]
    assert select_playbooks(None, [], {"stuck"}) == ["stuck"]
    assert select_playbooks(None, [], {"avoid_reason"}) == ["avoiding"]
    assert select_playbooks(None, ["avoided"], {"stuck", "overwhelmed", "completed"}) == ["avoiding", "stuck"]
    assert select_playbooks(None, [], {"completed"}) == ["celebrate"]
    assert select_playbooks(None, ["clarified"]) == []  # no signal: core only


def test_chat_signals():
    assert chat_signals("I'm stuck", [], False, at(10), 0) == {"stuck"}
    assert chat_signals("hello", [], True, at(10), 0) == {"stuck"}
    assert chat_signals("גדול מדי", [], False, at(10), 0) == {"avoid_reason"}
    assert chat_signals("hm", [], False, at(15), 3) == {"overwhelmed"}
    assert chat_signals("hm", [], False, at(14, 59), 3) == set()
    assert chat_signals("hm", [], False, at(16), 2) == set()  # one is done already
    done = [{"kind": "event", "content": "[Marked done: Taxes]"}]
    assert chat_signals("yay", done, False, at(10), 0) == {"completed"}


# ---------------------------------------------------------------- Today's 3 and who gets check-ins

def test_pick_focus_order():
    tasks = [
        task(1, priority=3),                                    # important, undated
        task(2, due_at=at(9, d=8)),                             # overdue
        task(3, due_at=at(18)),                                 # due today
        task(4, kind="meeting", due_at=at(11)),                 # never a focus task
        task(5, due_at=at(9, d=7), blocked_until=at(12)),       # blocked
        task(6, due_at=at(9, d=1), parent_id=2),                # a step of another task
        task(7, priority=1),
    ]
    assert pick_focus(tasks, at(7, 30)) == [2, 3, 1]
    assert pick_focus([task(7), task(8, priority=2)], at(7, 30)) == [8, 7]
    assert pick_focus([], at(7, 30)) == []


def test_may_check_in():
    now = at(10)
    assert may_check_in(task(1, kind="meeting"), now, TODAY)
    assert may_check_in(task(1, kind="fence"), now, TODAY)
    assert may_check_in(task(1, remind_requested=True), now, TODAY)
    assert may_check_in(task(1, focus_date=TODAY), now, TODAY)
    assert may_check_in(task(1, due_at=now + timedelta(hours=47)), now, TODAY)
    assert may_check_in(task(1, due_at=now - timedelta(days=3)), now, TODAY)  # overdue
    assert not may_check_in(task(1, due_at=now + timedelta(hours=49)), now, TODAY)
    assert not may_check_in(task(1), now, TODAY)                               # undated, not focus
    assert not may_check_in(task(1, focus_date=date(2026, 11, 8)), now, TODAY)
