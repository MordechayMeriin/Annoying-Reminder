"""Which model wrote a message: recorded on LLM messages, None for templates, shown only when turned on."""

import asyncio
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
import pytest
from fakes import FakeStore, ScriptedLLM

from app import agent, config, httpclient, llm, preferences, scheduler, user_settings
from app.llm import LLMUnavailable

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


@pytest.fixture(autouse=True)
def reset_overrides():
    config.apply_overrides({})
    yield
    config.apply_overrides({})


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(9, 10))
    s.install(monkeypatch.setattr)
    scheduler._delivering_since = None
    return s


def use_llm(monkeypatch, *steps, model="google/gemini-2.5-flash") -> ScriptedLLM:
    fake = ScriptedLLM(list(steps), model=model)
    fake.install(monkeypatch.setattr)
    return fake


def test_track_models_sees_calls_inside_wait_for():
    async def call():
        llm.record_model("a/one")
        await asyncio.sleep(0)
        llm.record_model("b/two")

    async def run():
        with llm.track_models() as models:
            await asyncio.wait_for(call(), timeout=1)  # runs in a copy of the context
        llm.record_model("c/outside")                # nothing is tracking: ignored
        return models

    assert asyncio.run(run()) == ["a/one", "b/two"]
    assert llm.last_model(["a/one", "b/two"]) == "b/two"
    assert llm.last_model(["a/one"], used=False) is None and llm.last_model([]) is None


def test_chat_completion_records_the_model_openrouter_reports(monkeypatch):
    class Client:
        async def post(self, *a, **kw):
            return httpx.Response(200, json={"model": "openai/gpt-4o-mini",
                                             "choices": [{"message": {"role": "assistant", "content": "hi"}}]})

    monkeypatch.setattr(httpclient, "client", lambda: Client())

    async def run():
        with llm.track_models() as models:
            await llm.chat_completion([{"role": "user", "content": "hi"}])
        return models

    assert asyncio.run(run()) == ["openai/gpt-4o-mini"]


def test_chat_reply_carries_the_model(store, monkeypatch):
    use_llm(monkeypatch, "Hi! What's on your mind?", model="anthropic/claude-haiku")
    r = asyncio.run(agent.handle_user_message("hi"))
    assert r.reply_message["model"] == "anthropic/claude-haiku"
    assert store.messages[-1]["model"] == "anthropic/claude-haiku"
    assert store.messages[0]["model"] is None  # the user's own message


def test_check_in_by_llm_carries_the_model_and_template_does_not(store, monkeypatch):
    store.add_task("Send the invoice", remind_at=local(9, 10), focus=True)
    use_llm(monkeypatch, "How's the invoice going?")
    asyncio.run(scheduler.deliver(scheduler.plan_tick()))
    assert store.messages[-1]["model"] == "google/gemini-2.5-flash"

    store.advance(24 * 60)
    store.add_task("Pay the bill", remind_at=store.now, due_at=store.now)
    use_llm(monkeypatch, LLMUnavailable("502"))
    scheduler._delivering_since = None
    asyncio.run(scheduler.deliver(scheduler.plan_tick()))
    assert "Pay the bill" in store.messages[-1]["content"] and store.messages[-1]["model"] is None


def test_messages_show_the_model_only_when_turned_on(monkeypatch):
    monkeypatch.setattr(user_settings, "load", lambda: None)  # main loads these from the DB at import
    monkeypatch.setattr(preferences, "load", lambda: None)
    from app import main
    row = {"id": 1, "role": "assistant", "content": "x", "model": "a/b", "created_at": local(9, 10)}
    assert main._public_messages([row])[0]["model"] is None   # off by default
    config.apply_overrides({"show_model": "true"})
    assert main._public_messages([row])[0]["model"] == "a/b"
