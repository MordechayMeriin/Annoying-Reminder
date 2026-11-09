"""Chat agent: replies continue the thread, the LLM decides the task, robust tool calling."""

import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore, ScriptedLLM

from app import agent
from app.agent import normalize_roles
from app.llm import LLMUnavailable

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(9, 10))
    s.install(monkeypatch.setattr)
    return s


def chat(text: str):
    return asyncio.run(agent.handle_user_message(text))


def use_llm(monkeypatch, *steps) -> ScriptedLLM:
    fake = ScriptedLLM(list(steps))
    fake.install(monkeypatch.setattr)
    return fake


def test_normalize_roles_alternates_and_starts_with_user():
    hist = [
        {"role": "assistant", "content": "plan", "kind": "morning"},
        {"role": "assistant", "content": "nudge", "kind": "nudge"},
        {"role": "user", "content": "[Marked done: X]", "kind": "event"},
        {"role": "user", "content": "thanks", "kind": "chat"},
    ]
    out = normalize_roles(hist)
    assert [m["role"] for m in out] == ["user", "assistant", "user"]
    assert out[1]["content"] == "plan\n\nnudge" and out[2]["content"] == "[Marked done: X]\n\nthanks"


def test_stuck_reply_after_check_in_gets_help_and_tools(store, monkeypatch):
    t = store.add_task("Tax return", description="Annual return, deadline end of month")
    store.m_add("assistant", "How's the tax return going?", "nudge", [t["id"]])
    store.advance(20)
    llm = use_llm(monkeypatch,
                  {"tool": "log_interaction", "args": {"task_id": t["id"], "kind": "stuck", "note": "the form"}},
                  {"tool": "add_subtask", "args": {"task_id": t["id"], "title": "Download form 1301"}},
                  {"tool": "schedule_followup", "args": {"task_id": t["id"], "minutes_from_now": 45,
                                                         "reason": "after first step", "requested_by": "assistant"}},
                  "Let's just download form 1301 first (10 min). I'll check back in 45 minutes.")
    r = chat("I'm stuck on the form")

    assert r.task_ids == [t["id"]]
    system = llm.requests[0]["messages"][0]["content"]
    assert "Tax return" in system and "How's the tax return going?" in system  # likely topic
    assert [e["kind"] for e in store.events] == ["stuck"]
    assert [s["title"] for s in store.t_subtasks(t["id"])] == ["Download form 1301"]
    assert store.tasks[t["id"]]["remind_requested"] is False
    user_msg = next(m for m in store.messages if m["id"] == r.user_message_id)
    assert user_msg["task_ids"] == [t["id"]]


def test_reply_without_tool_links_to_unambiguous_check_in(store, monkeypatch):
    t = store.add_task("Gym")
    store.m_add("assistant", "Gym today?", "nudge", [t["id"]])
    use_llm(monkeypatch, "Great, enjoy!")
    assert chat("yes, going at 6").task_ids == [t["id"]]


def test_reply_to_grouped_check_in_without_action_stays_unlinked(store, monkeypatch):
    a, b = store.add_task("Gym"), store.add_task("Groceries")
    store.m_add("assistant", "Gym and groceries?", "nudge", [a["id"], b["id"]])
    use_llm(monkeypatch, "Which one first?")
    assert chat("ok").task_ids == []


def test_llm_decides_a_different_task_than_the_check_in(store, monkeypatch):
    gym, plumber = store.add_task("Gym"), store.add_task("Call the plumber")
    store.m_add("assistant", "Gym today?", "nudge", [gym["id"]])
    llm = use_llm(monkeypatch, {"tool": "complete_task", "args": {"id": plumber["id"]}}, "Nice, plumber done.")
    r = chat("btw I called the plumber")
    assert r.task_ids == [plumber["id"]]
    assert "Call the plumber" in llm.requests[0]["messages"][0]["content"]  # word match made it a candidate


def test_check_in_from_before_the_daily_reset_is_still_the_topic(store, monkeypatch):
    store.now = local(10, 5, 30).astimezone(store.now.tzinfo)  # day reset was at 04:00
    t = store.add_task("Read chapter 3")
    store.messages.append({"id": 99, "role": "assistant", "content": "Chapter 3 tonight?", "kind": "nudge",
                           "task_id": t["id"], "task_ids": [t["id"]], "created_at": local(10, 3, 0)})
    llm = use_llm(monkeypatch, "Good morning! Tonight then?")
    chat("sorry fell asleep")
    req = llm.requests[0]["messages"]
    assert "Chapter 3 tonight?" in req[0]["content"]               # shown as the last check-in
    assert all("Chapter 3 tonight?" not in m["content"] for m in req[1:])  # not in today's history


def test_bad_arguments_get_one_retry(store, monkeypatch):
    llm = use_llm(monkeypatch,
                  {"tool": "add_task", "args": {"title": "Dentist", "due_at": "thursday at 10"}},
                  {"tool": "add_task", "args": {"title": "Dentist", "due_at": "2026-11-12T10:00"}},
                  "Saved: Dentist, Thu 10:00.")
    r = chat("dentist thursday 10")
    assert r.reply == "Saved: Dentist, Thu 10:00." and r.changed
    assert "ISO" in llm.requests[1]["messages"][-1]["content"]  # the error went back to the model
    assert [t["title"] for t in store.tasks.values()] == ["Dentist"]


def test_gives_up_after_second_bad_call(store, monkeypatch):
    use_llm(monkeypatch,
            {"tool": "complete_task", "args": {"id": 999}},
            {"tool": "complete_task", "args": {"id": 998}},
            "never reached")
    r = chat("done with that thing")
    assert r.reply == "Sorry, I couldn't process that. Try again?" and not r.changed


def test_tool_call_written_as_json_text(store, monkeypatch):
    use_llm(monkeypatch, '{"name": "add_task", "arguments": {"title": "לשלם ארנונה", "due_at": "2026-11-15T09:00"}}',
            "שמרתי: לשלם ארנונה, ראשון 09:00")
    r = chat("לשלם ארנונה עד ראשון")
    assert r.changed and [t["title"] for t in store.tasks.values()] == ["לשלם ארנונה"]
    assert r.reply.startswith("שמרתי")


def test_llm_unavailable_keeps_user_message(store, monkeypatch):
    use_llm(monkeypatch, LLMUnavailable("down"))
    with pytest.raises(LLMUnavailable):
        chat("hello?")
    assert [m["content"] for m in store.messages] == ["hello?"]


def test_requests_alternate_roles(store, monkeypatch):
    store.m_add("assistant", "Morning plan", "morning")
    store.m_add("assistant", "Check-in", "nudge")
    store.m_add_event("[Marked done: X]")
    llm = use_llm(monkeypatch, "ok")
    chat("hi")
    roles = [m["role"] for m in llm.requests[0]["messages"][1:]]
    assert roles == ["user", "assistant", "user"]
