"""Chat progress: the agent reports received / thinking / tool steps, and POST /chat streams them."""

import asyncio
import json
from datetime import datetime
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore, ScriptedLLM

from app import agent, user_settings
from app.llm import LLMUnavailable

TZ = ZoneInfo("Asia/Jerusalem")


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(datetime(2026, 11, 9, 10, 0, tzinfo=TZ))
    s.install(monkeypatch.setattr)
    return s


def use_llm(monkeypatch, *steps) -> ScriptedLLM:
    fake = ScriptedLLM(list(steps))
    fake.install(monkeypatch.setattr)
    return fake


def test_progress_events_in_order(store, monkeypatch):
    t = store.add_task("Call the bank")
    use_llm(monkeypatch,
            {"tool": "add_task", "args": {"title": "Dentist", "kind": "meeting"}},
            {"tool": "complete_task", "args": {"id": t["id"]}},
            "Saved the dentist and marked the bank call done.")
    events = []
    r = asyncio.run(agent.handle_user_message("dentist thursday; called the bank", events.append))

    assert [e["type"] for e in events] == ["received", "thinking", "tool", "thinking", "tool", "thinking"]
    assert events[0] == {"type": "received", "userMessageId": r.user_message_id}
    assert events[2] == {"type": "tool", "name": "add_task", "title": "Dentist", "kind": "meeting"}
    assert events[4] == {"type": "tool", "name": "complete_task", "taskTitle": "Call the bank"}


def test_a_failing_progress_callback_never_breaks_the_turn(store, monkeypatch):
    use_llm(monkeypatch, {"tool": "add_task", "args": {"title": "Gym"}}, "Saved.")

    def broken(event):
        raise RuntimeError("UI bug")

    r = asyncio.run(agent.handle_user_message("gym at 6", broken))
    assert r.reply == "Saved." and r.changed


def test_unknown_task_id_still_gives_a_status(store, monkeypatch):
    assert agent.tool_status("update_task", {"id": 999}) == {"type": "tool", "name": "update_task"}
    assert agent.tool_status("list_tasks", {"scope": "today"}) == {"type": "tool", "name": "list_tasks"}


# ---------------------------------------------------------------- POST /chat (Server-Sent Events)

@pytest.fixture
def client(store, monkeypatch):
    from fastapi.testclient import TestClient

    monkeypatch.setattr(user_settings, "load", lambda: None)  # main loads settings from the DB at import
    from app import main
    monkeypatch.setattr(main.notify, "cancel_alarms", lambda: None)
    main._chat_times.clear()
    main.app.dependency_overrides[main.require_owner] = lambda: "owner"
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


def sse_events(body: str) -> list[dict]:
    return [json.loads(line[5:]) for line in body.splitlines() if line.startswith("data:")]


def test_chat_streams_progress_then_the_reply(client, monkeypatch):
    use_llm(monkeypatch, {"tool": "add_task", "args": {"title": "Gym"}}, "Saved: gym.")
    r = client.post("/chat", json={"message": "gym at 6"})

    assert r.status_code == 200 and r.headers["content-type"].startswith("text/event-stream")
    events = sse_events(r.text)
    assert [e["type"] for e in events] == ["received", "thinking", "tool", "thinking", "done"]
    done = events[-1]
    assert done["reply"] == "Saved: gym." and done["tasksChanged"] is True
    assert done["userMessageId"] == events[0]["userMessageId"]


def test_chat_reports_llm_errors_in_the_stream(client, store, monkeypatch):
    use_llm(monkeypatch, LLMUnavailable("429"))
    events = sse_events(client.post("/chat", json={"message": "hello"}).text)

    assert [e["type"] for e in events] == ["received", "thinking", "error"]
    assert events[-1]["status"] == 503
    assert any(m["role"] == "user" and m["content"] == "hello" for m in store.messages)  # message kept
