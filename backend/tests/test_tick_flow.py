"""End-to-end /tick flow on an in-memory store with a scripted LLM: grouping, guardrails, fallback."""

import asyncio
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore, ScriptedLLM

from app import actions, scheduler
from app.llm import LLMLimitReached, LLMUnavailable

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(9, 10))
    s.install(monkeypatch.setattr)
    scheduler._delivering_since = None
    return s


def tick(store) -> list[int]:
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))
    return ids


def use_llm(monkeypatch, *steps) -> ScriptedLLM:
    fake = ScriptedLLM(list(steps))
    fake.install(monkeypatch.setattr)
    return fake


def test_due_task_gives_one_llm_message_and_one_push(store, monkeypatch):
    t = store.add_task("Send the invoice", remind_at=local(9, 10), focus=True, description="Client: Dana, amount 3,000")
    llm = use_llm(monkeypatch, "How's the invoice for Dana going? Want to do it now?")

    assert tick(store) == [t["id"]]
    assert len(store.messages) == 1 and len(store.pushes) == 1
    msg, push = store.messages[0], store.pushes[0]
    assert msg["content"] == "How's the invoice for Dana going? Want to do it now?"
    assert msg["kind"] == "nudge" and msg["task_ids"] == [t["id"]]
    assert push["message_id"] == msg["id"] and push["url"] == f"/?m={msg['id']}"
    assert push["task_id"] == t["id"]  # exactly one task → ✓ / ⏰ buttons
    assert "Client: Dana" in llm.requests[0]["messages"][1]["content"]  # description is the context
    after = store.tasks[t["id"]]
    assert after["nag_count"] == 1 and after["remind_at"] == store.now + timedelta(minutes=30)


def test_two_due_tasks_give_one_combined_message(store, monkeypatch):
    a = store.add_task("Call the bank", remind_at=local(9, 9, 50), focus=True)
    b = store.add_task("Book flights", remind_at=local(9, 9, 55), focus=True, priority=2)
    use_llm(monkeypatch, "Flights first, then a quick call to the bank — which one now?")

    tick(store)
    assert len(store.messages) == 1 and len(store.pushes) == 1
    assert store.messages[0]["task_ids"] == [b["id"], a["id"]]  # more important first
    assert store.pushes[0]["task_id"] is None                     # no buttons for a group
    assert store.pushes[0]["title"].endswith("(+1)")


@pytest.mark.parametrize("failure", [LLMUnavailable("429"), LLMLimitReached("cap"), ""])
def test_llm_failure_falls_back_to_template(store, monkeypatch, failure):
    t = store.add_task("Pay the electricity bill", remind_at=local(9, 10), due_at=local(9, 9))
    use_llm(monkeypatch, failure)

    tick(store)
    assert len(store.pushes) == 1
    assert "Pay the electricity bill — overdue since" in store.messages[0]["content"]
    assert store.tasks[t["id"]]["nag_count"] == 1


def test_llm_timeout_falls_back(store, monkeypatch):
    store.add_task("Renew passport", remind_at=local(9, 10), focus=True)

    async def slow(*a, **k):
        await asyncio.sleep(5)
    monkeypatch.setattr("app.llm_loop.chat_completion", slow)
    monkeypatch.setattr("app.coach.get_settings", _fast_settings)  # 0.05 s overall deadline

    tick(store)
    assert store.messages[0]["content"].startswith("Renew passport")
    assert len(store.pushes) == 1


def _fast_settings():
    import dataclasses
    from app.config import get_settings
    return dataclasses.replace(get_settings(), llm_timeout_s=0.05)


def test_coach_can_schedule_followup_clamped(store, monkeypatch):
    t = store.add_task("Write the report", remind_at=local(9, 10), focus=True)
    use_llm(monkeypatch,
            {"tool": "schedule_followup", "args": {"task_id": t["id"], "minutes_from_now": 2, "reason": "soon"}},
            "Start with the outline — 15 minutes?")
    tick(store)
    assert store.tasks[t["id"]]["remind_at"] == store.now + timedelta(minutes=10)  # clamped to the minimum
    assert store.messages[0]["content"] == "Start with the outline — 15 minutes?"


def test_text_form_tool_call_is_parsed_once(store, monkeypatch):
    t = store.add_task("Fix the bike", remind_at=local(9, 10), focus=True)
    use_llm(monkeypatch,
            f'```tool_code\nschedule_followup(task_id={t["id"]}, at_local_iso="2026-11-09T18:00", reason="evening")\n```',
            "Bike tonight after work?")
    tick(store)
    assert store.tasks[t["id"]]["remind_at"] == local(9, 18)
    assert store.messages[0]["content"] == "Bike tonight after work?"


def test_meeting_facts_come_from_code(store, monkeypatch):
    m = store.add_task("Team sync", kind="meeting", due_at=local(9, 10, 15), remind_at=local(9, 10))
    use_llm(monkeypatch, "Anything to prepare?")
    tick(store)
    text = store.messages[0]["content"]
    assert text.startswith("📅 Team sync — 10:15 (in 15 min)") and text.endswith("Anything to prepare?")
    assert store.messages[0]["kind"] == "meeting"
    assert store.tasks[m["id"]]["remind_at"] == local(9, 10, 15)  # second reminder at start time


def test_min_gap_holds_the_next_check_in(store, monkeypatch):
    store.add_task("A", remind_at=local(9, 10), focus=True)
    b = store.add_task("B", remind_at=local(9, 10, 10), focus=True)
    use_llm(monkeypatch, "About A?", "About B?")
    tick(store)
    store.advance(10)
    assert tick(store) == []
    assert store.tasks[b["id"]]["remind_at"] == local(9, 10, 20)
    store.advance(10)
    assert tick(store) == [b["id"]]
    assert len(store.pushes) == 2


def test_quiet_hours_hold_even_critical(store, monkeypatch):
    store.now = local(9, 23, 30).astimezone(store.now.tzinfo)
    t = store.add_task("Critical thing", priority=3, remind_at=local(9, 23, 20), focus=True)
    use_llm(monkeypatch, "x")
    assert tick(store) == []
    assert store.tasks[t["id"]]["remind_at"] == local(10, 7)
    assert store.pushes == []


def test_daily_cap_never_exceeded(store, monkeypatch):
    use_llm(monkeypatch, *(["msg"] * 40))
    for i in range(30):
        store.add_task(f"task {i}", remind_at=store.now, focus=True)
        tick(store)
        store.advance(25)
    assert 10 < len(store.pushes) <= 20


def test_ignored_check_in_is_logged(store, monkeypatch):
    t = store.add_task("Clean the garage", remind_at=local(9, 10), focus=True)
    use_llm(monkeypatch, "Garage?", "Still the garage?")
    tick(store)
    store.advance(30)
    tick(store)
    assert [e["kind"] for e in store.events if e["task_id"] == t["id"]] == ["ignored"]


def test_done_from_notification_appears_in_chat(store):
    t = store.add_task("Water the plants")
    actions.apply(t["id"], "done")
    actions.apply(store.add_task("Gym")["id"], "snooze", 120)
    assert [m["content"] for m in store.messages] == ["[Marked done: Water the plants]", "[Snoozed 2h: Gym]"]
    assert store.messages[0]["kind"] == "event" and store.messages[0]["task_ids"] == [t["id"]]


def test_snoozed_reminder_is_requested_and_skips_per_task_cap(store, monkeypatch):
    t = store.add_task("Stretch", remind_at=local(9, 10), focus=True)
    use_llm(monkeypatch, *(["msg"] * 10))
    for _ in range(4):           # four unrequested check-ins use up the per-task cap
        tick(store)
        store.advance(30)
    assert store.tasks[t["id"]]["remind_at"] is None  # MAX_NAGS reached: stops nagging
    actions.apply(t["id"], "snooze", 30)
    store.advance(30)
    assert tick(store) == [t["id"]]  # the user asked for it, so the cap doesn't apply
    assert store.messages[-1]["kind"] == "reminder"
