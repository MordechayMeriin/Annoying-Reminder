"""
Phase 1 end to end (in-memory store, scripted LLM): one-tap replies, the silent first step,
Today's 3 accepted by silence, the focus-only check-in filter, taps as activity, and playbooks.
"""

import asyncio
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore, ScriptedLLM

from app import actions, agent, coach, scheduler
from app.llm import LLMUnavailable
from app.logic import URGENT

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(9, 10))
    s.install(monkeypatch.setattr)
    scheduler._delivering_since = None
    return s


def tick() -> list[int]:
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))
    return ids


def use_llm(monkeypatch, *steps) -> ScriptedLLM:
    fake = ScriptedLLM(list(steps))
    fake.install(monkeypatch.setattr)
    return fake


def system_prompt(llm: ScriptedLLM, i: int = 0) -> str:
    return llm.requests[i]["messages"][0]["content"]


# ---------------------------------------------------------------- F1: chips and notification buttons

def test_check_in_has_chips_and_start_done_on_the_notification(store, monkeypatch):
    t = store.add_task("Taxes", next_action="Find last year's PDF in email", remind_at=local(9, 10), focus=True)
    llm = use_llm(monkeypatch, "Find last year's PDF in email?")
    tick()
    msg, push = store.messages[-1], store.pushes[-1]
    assert [c["id"] for c in msg["chips"]] == ["start5", "done", "later", "stuck"]
    assert all(c.get("task_id") == t["id"] for c in msg["chips"] if c.get("action"))
    assert [a["action"] for a in push["actions"]] == ["start", "done"]
    assert push["title"] == "Taxes: Find last year's PDF in email"
    assert "▶ Start 5 min · ✓ Done · Later · I'm stuck" in system_prompt(llm)   # the question matches the buttons
    assert "Next action: Find last year's PDF in email" in llm.requests[0]["messages"][1]["content"]


def test_hebrew_chips_when_the_user_writes_hebrew(store, monkeypatch):
    store.m_add("user", "תזכיר לי בבקשה")
    store.advance(30)
    store.add_task("ארנונה", remind_at=store.now, focus=True)
    use_llm(monkeypatch, "מה המצב?")
    tick()
    assert store.messages[-1]["chips"][0]["label"] == "▶ להתחיל 5 דק׳"
    assert store.pushes[-1]["actions"][1]["title"] == "✓ בוצע"


def test_meeting_reminders_have_no_chips_or_buttons(store, monkeypatch):
    store.add_task("Team sync", kind="meeting", due_at=local(9, 10, 15), remind_at=local(9, 10))
    use_llm(monkeypatch, "Anything to prepare?")
    tick()
    assert store.messages[-1]["chips"] == [] and store.pushes[-1]["actions"] == []


def test_done_from_the_notification_makes_no_llm_call(store, monkeypatch):
    t = store.add_task("Water the plants", remind_at=local(9, 10), focus=True)
    llm = use_llm(monkeypatch, "Plants?")
    tick()
    calls = len(llm.requests)
    actions.apply(t["id"], "done")
    assert len(llm.requests) == calls
    assert store.tasks[t["id"]]["status"] == "done"
    assert store.messages[-1]["content"] == "[Marked done: Water the plants]"


def test_start_opens_a_session_with_a_check_in_when_the_time_is_up(store):
    # Phase 2: ▶ Start opens a focus session; its end check-in replaces the task's own (test_phase2_flow.py)
    t = store.add_task("Taxes", next_action="Open the form", remind_at=store.now + timedelta(minutes=3))
    actions.apply(t["id"], "start", 5)
    assert store.sessions[-1]["task_id"] == t["id"] and store.sessions[-1]["planned_min"] == 5
    assert store.tasks[t["id"]]["remind_at"] is None   # inside the session: the end check-in covers it
    assert store.events[-1]["kind"] == "session_started"
    assert store.messages[-1]["content"] == "[▶ 5 min: Open the form (Taxes)]"


def test_later_is_decided_by_code(store):
    t = store.add_task("Call the bank")
    actions.apply(t["id"], "later")
    assert store.tasks[t["id"]]["remind_at"] == local(9, 12)        # 10:00 → +2 h
    assert store.events[-1]["kind"] == "postponed"
    store.now = local(9, 19).astimezone(store.now.tzinfo)
    actions.apply(t["id"], "later")
    assert store.tasks[t["id"]]["remind_at"] == local(10, 7, 30)    # after 18:00 → the next morning


def test_a_user_who_only_taps_is_never_counted_as_ignoring(store, monkeypatch):
    t = store.add_task("Call the plumber", remind_at=local(9, 10), priority=1, focus=True)
    use_llm(monkeypatch, *(["Plumber?"] * 5))
    tick()
    store.advance(10)
    assert scheduler.ignored_streak(store.tasks[t["id"]]) == 1
    actions.apply(t["id"], "later")   # a tap, no typing
    assert scheduler.ignored_streak(store.tasks[t["id"]]) == 0
    store.now = local(9, 12, 10).astimezone(store.now.tzinfo)
    assert tick() == [t["id"]]
    assert "ignored" not in [e["kind"] for e in store.events]
    # urgent only because it's a reminder the user asked for (Later), not for being ignored
    assert store.pushovers[-1]["level"] == URGENT and store.messages[-1]["kind"] == "reminder"


# ---------------------------------------------------------------- F2: the first step, silently

def test_add_task_saves_the_next_action_and_reply_gets_chips(store, monkeypatch):
    use_llm(monkeypatch,
            {"tool": "add_task", "args": {"title": "Taxes", "next_action": "Find last year's PDF in email",
                                          "estimate_min": 120}},
            "Saved: Taxes. First step: find last year's PDF in email.")
    r = asyncio.run(agent.handle_user_message("I need to do my taxes"))
    t = next(iter(store.tasks.values()))
    assert (t["next_action"], t["estimate_min"]) == ("Find last year's PDF in email", 120)
    assert [c["id"] for c in r.reply_message["chips"]] == ["start5", "done"]
    assert r.reply_message["chips"][0]["task_id"] == t["id"]


def test_task_without_a_next_action_is_still_saved_and_the_model_is_told(store, monkeypatch):
    llm = use_llm(monkeypatch, {"tool": "add_task", "args": {"title": "Taxes"}},
                  {"tool": "update_task", "args": {"id": 1, "next_action": "Open the tax site"}}, "Saved: Taxes.")
    asyncio.run(agent.handle_user_message("taxes"))
    assert store.tasks[1]["next_action"] == "Open the tax site"
    assert "Saved without a next_action" in llm.requests[1]["messages"][-1]["content"]


def test_no_chips_after_the_task_is_done(store, monkeypatch):
    t = store.add_task("Call the bank")
    use_llm(monkeypatch, {"tool": "complete_task", "args": {"id": t["id"]}}, "Nice.")
    r = asyncio.run(agent.handle_user_message("called the bank"))
    assert r.task_ids == [t["id"]] and r.reply_message["chips"] == []


def test_template_leads_with_the_next_action_when_the_llm_is_down(store, monkeypatch):
    store.add_task("Taxes", next_action="Find last year's PDF", remind_at=local(9, 10), due_at=local(9, 9),
                   nag_count=2)
    use_llm(monkeypatch, LLMUnavailable("down"))
    tick()
    text = store.messages[-1]["content"]
    assert text.startswith("▶ Find last year's PDF — Taxes (overdue since")
    assert "still not done" not in text and "#" not in text
    assert store.pushes[-1]["body"].startswith("▶ Find last year's PDF")


# ---------------------------------------------------------------- F3: Today's 3 and the focus filter

def test_undated_tasks_outside_todays_3_get_no_check_ins(store, monkeypatch):
    quiet = store.add_task("Someday: sort photos", remind_at=local(9, 9))
    soon = store.add_task("Pay rent", due_at=local(10, 9), remind_at=local(9, 9, 1))
    asked = store.add_task("Call mom", remind_at=local(9, 9, 2), remind_requested=True)
    focus = store.add_task("Taxes", remind_at=local(9, 9, 3), focus=True)
    use_llm(monkeypatch, "Here's where things stand.")
    assert set(tick()) == {soon["id"], asked["id"], focus["id"]}
    assert quiet["id"] not in store.messages[-1]["task_ids"]
    for _ in range(10):
        store.advance(30)
        tick()
    assert all(quiet["id"] not in m["task_ids"] for m in store.messages)


def test_morning_with_the_llm_sets_focus(store, monkeypatch):
    store.now = local(9, 7, 30).astimezone(store.now.tzinfo)
    a = store.add_task("Taxes", next_action="Find last year's PDF", priority=2)
    b = store.add_task("Book flights", next_action="Open the airline site")
    c = store.add_task("Dentist", due_at=local(9, 18), next_action="Call the clinic")
    store.add_task("Team sync", kind="meeting", due_at=local(9, 11))
    llm = use_llm(monkeypatch, {"tool": "set_focus", "args": {"task_ids": [a["id"], c["id"]]}},
                  "Today's 3:\n1. Taxes — first step: find last year's PDF\n2. Dentist\n📅 11:00 Team sync")
    asyncio.run(scheduler.run_daily_job("morning"))

    msg = store.messages[-1]
    assert msg["kind"] == "morning" and msg["content"].startswith("Today's 3:")
    assert [store.tasks[i]["focus_date"] for i in (a["id"], b["id"], c["id"])] == [store.focus_day(), None,
                                                                                 store.focus_day()]
    assert msg["task_ids"] == [a["id"], c["id"]]
    assert [ch["id"] for ch in msg["chips"]] == ["start_first", "change"]
    assert msg["chips"][0]["task_id"] == a["id"]
    prompt = system_prompt(llm)
    assert "Playbook: planning" in prompt and "set_focus" in prompt
    assert {t["function"]["name"] for t in llm.requests[0]["tools"]} == {"set_focus", "list_tasks"}
    assert "11:00 Team sync" in llm.requests[0]["messages"][1]["content"]


def test_morning_with_the_llm_down_uses_the_code_picker(store, monkeypatch):
    store.now = local(9, 7, 30).astimezone(store.now.tzinfo)
    old = store.add_task("Renew passport", due_at=local(5, 9), next_action="Find the old passport")
    store.add_task("Someday", priority=1)
    today = store.add_task("Pay rent", due_at=local(9, 17))
    important = store.add_task("Write proposal", priority=3)
    store.add_task("Standup", kind="meeting", due_at=local(9, 9, 30))
    use_llm(monkeypatch, LLMUnavailable("down"))
    asyncio.run(scheduler.run_daily_job("morning"))

    focus = sorted((t for t in store.tasks.values() if t["focus_date"]), key=lambda t: t["focus_rank"])
    assert [t["id"] for t in focus] == [old["id"], today["id"], important["id"]]
    text = store.messages[-1]["content"]
    assert text.splitlines()[:4] == ["☀️ Today's 3:", "1. Renew passport — first step: Find the old passport",
                                     "2. Pay rent", "3. Write proposal"]
    assert "09:30 Standup" in text
    assert store.messages[-1]["chips"][0]["task_id"] == old["id"]


def test_morning_without_set_focus_keeps_the_code_pick(store, monkeypatch):
    store.now = local(9, 7, 30).astimezone(store.now.tzinfo)
    t = store.add_task("Taxes")
    use_llm(monkeypatch, "Today: taxes.")
    asyncio.run(scheduler.run_daily_job("morning"))
    assert store.tasks[t["id"]]["focus_date"] == store.focus_day()
    assert store.messages[-1]["content"] == "Today: taxes."


def test_focus_check_ins_follow_the_morning_pick(store, monkeypatch):
    """Silence accepts the plan: a focus task with a pending check-in gets it; the others stay quiet."""
    store.now = local(9, 7, 30).astimezone(store.now.tzinfo)
    a = store.add_task("Taxes", remind_at=local(9, 10))
    b = store.add_task("Sort photos", remind_at=local(9, 10))
    use_llm(monkeypatch, {"tool": "set_focus", "args": {"task_ids": [a["id"]]}}, "Today: taxes.", "Taxes?")
    asyncio.run(scheduler.run_daily_job("morning"))
    store.now = local(9, 10).astimezone(store.now.tzinfo)
    assert tick() == [a["id"]]
    assert store.tasks[b["id"]]["remind_at"] == local(9, 10)  # untouched, just not contacted


def test_change_it_in_chat_swaps_focus(store, monkeypatch):
    a = store.add_task("Taxes", focus=True, focus_rank=1)
    b = store.add_task("Dentist")
    use_llm(monkeypatch, {"tool": "set_focus", "args": {"task_ids": f"{b['id']}"}}, "Swapped: Dentist first.")
    asyncio.run(agent.handle_user_message("swap it for the dentist"))
    assert store.tasks[a["id"]]["focus_date"] is None and store.tasks[b["id"]]["focus_date"] == store.focus_day()


def test_set_focus_rejects_meetings(store, monkeypatch):
    m = store.add_task("Team sync", kind="meeting", due_at=local(9, 11))
    llm = use_llm(monkeypatch, {"tool": "set_focus", "args": {"task_ids": [m["id"]]}}, "OK")
    asyncio.run(agent.handle_user_message("focus on the sync"))
    assert "meetings can't be focus tasks" in llm.requests[1]["messages"][-1]["content"]
    assert store.tasks[m["id"]]["focus_date"] is None


def test_midday_check_in_is_skipped_when_the_user_was_active(store, monkeypatch):
    store.add_task("Taxes", focus=True, due_at=local(9, 18))
    store.now = local(9, 13).astimezone(store.now.tzinfo)
    store.m_add("user", "working on it")
    store.advance(30)
    llm = use_llm(monkeypatch, "How's taxes?")
    asyncio.run(scheduler.run_daily_job("checkin"))
    assert llm.requests == [] and store.messages[-1]["role"] == "user"
    store.advance(31)
    asyncio.run(scheduler.run_daily_job("checkin"))
    assert store.messages[-1]["kind"] == "checkin"
    assert [c["id"] for c in store.messages[-1]["chips"]] == ["start_next", "what_now"]


# ---------------------------------------------------------------- playbooks

def test_stuck_chip_brings_the_stuck_playbook(store, monkeypatch):
    store.add_task("Taxes", focus=True)
    llm = use_llm(monkeypatch, "What's the first page asking for?")
    asyncio.run(agent.handle_user_message("I'm stuck"))
    prompt = system_prompt(llm)
    assert "Playbook: stuck" in prompt and "Playbook: planning" not in prompt
    assert "How to interact with the user" in prompt  # the core is always there


def test_plain_chat_gets_the_core_only(store, monkeypatch):
    llm = use_llm(monkeypatch, "Hi!")
    asyncio.run(agent.handle_user_message("hello"))
    assert "Playbook:" not in system_prompt(llm)


def test_avoided_task_check_in_uses_the_avoiding_playbook(store, monkeypatch):
    t = store.add_task("Call the accountant", remind_at=local(9, 10), focus=True)
    for _ in range(3):
        store.t_add_event(t["id"], "postponed", "later")
    llm = use_llm(monkeypatch, "Too big, unclear, or just boring?")
    tick()
    assert "Playbook: avoiding" in system_prompt(llm)


def test_coach_template_lines_never_count_failures():
    store = FakeStore(local(9, 10))
    t = store.add_task("Gym", nag_count=3)
    assert coach.template_line(t, store.now) == "Gym — still open. One small step?"


# ---------------------------------------------------------------- API

@pytest.fixture
def client(store, monkeypatch):
    from fastapi.testclient import TestClient

    from app import main, user_settings

    monkeypatch.setattr(user_settings, "load", lambda: None)
    monkeypatch.setattr(main.notify, "cancel_alarms", lambda: None)
    main.app.dependency_overrides[main.require_owner] = lambda: "owner"
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


def test_focus_card_endpoint(client, store):
    a = store.add_task("Taxes", next_action="Find the PDF", focus=True, focus_rank=1)
    b = store.add_task("Dentist", focus=True, focus_rank=2)
    store.add_task("Not today")
    actions.apply(b["id"], "done")
    data = client.get("/focus").json()
    assert [(t["title"], t["next_action"], t["done"]) for t in data["tasks"]] == [
        ("Taxes", "Find the PDF", False), ("Dentist", None, True)]
    assert data["doneToday"] == 1 and data["date"] == "2026-11-09" and a["id"] == data["tasks"][0]["id"]


def test_messages_carry_chips_and_chip_actions_work(client, store):
    t = store.add_task("Taxes")
    store.m_add("assistant", "Taxes?", "nudge", [t["id"]], [{"id": "later", "label": "Later", "action": "later",
                                                             "task_id": t["id"]}])
    assert client.get("/messages").json()[-1]["chips"][0]["action"] == "later"
    assert client.post(f"/tasks/{t['id']}/action", json={"action": "later"}).status_code == 200
    r = client.post(f"/tasks/{t['id']}/action", json={"action": "start", "minutes": 25})
    assert r.status_code == 200 and r.json()["session"]["planned_min"] == 25
    assert client.post(f"/tasks/{t['id']}/action", json={"action": "explode"}).status_code == 422


def test_notification_start_with_a_signed_token(client, store):
    from app.config import get_settings
    from app.tokens import make_action_token

    t = store.add_task("Taxes")
    token = make_action_token(t["id"], get_settings().action_secret)
    r = client.post("/actions", json={"token": token, "action": "start", "minutes": 5})
    assert r.status_code == 200 and store.events[-1]["kind"] == "session_started"
    assert client.post("/actions", json={"token": "bad", "action": "done"}).status_code == 403


# ---------------------------------------------------------------- backfill script

def test_backfill_fills_only_open_tasks_without_a_next_action(store, monkeypatch):
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
    import backfill_next_actions as bf

    a = store.add_task("Taxes")
    store.add_task("Dentist", next_action="Call the clinic")
    store.add_task("Sync", kind="meeting")
    store.add_task("Step", parent_id=a["id"])
    llm = use_llm(monkeypatch, 'Sure: {"next_action": "Find last year\'s PDF", "estimate_min": "90"}')
    monkeypatch.setattr(bf, "chat_completion", llm)
    asyncio.run(bf.run(dry_run=False))
    assert len(llm.requests) == 1
    assert (store.tasks[a["id"]]["next_action"], store.tasks[a["id"]]["estimate_min"]) == ("Find last year's PDF", 90)
    assert bf.parse("no json") is None and bf.parse('{"next_action": ""}') is None
