"""POST /llm and GET /prompts on the Lambda (Android Phase D): the phone's way to the model, and the prompt files."""

import pytest
from test_lambda import call, owner_token, table  # noqa: F401  (the fixture)

from app import llm, llm_proxy
from app.llm import LLMUnavailable

ASK = {"messages": [{"role": "user", "content": "hi"}], "temperature": 0.5}


@pytest.fixture
def lam():
    from app import lambda_app
    llm_proxy._times.clear()
    return lambda_app


@pytest.fixture
def model(monkeypatch):
    """A stand-in for OpenRouter: records what it was asked, answers like chat_completion."""
    calls = []

    async def fake(messages, tools=None, temperature=0.3, max_tokens=None, timeout=None):
        calls.append({"messages": messages, "tools": tools, "temperature": temperature, "timeout": timeout})
        llm.record_model("google/gemini-2.5-flash")
        return {"role": "assistant", "content": "Hello!", "tool_calls": None, "refusal": None}

    monkeypatch.setattr(llm_proxy, "chat_completion", fake)
    return calls


def test_needs_the_owners_login(lam, table, model):
    assert call(lam, "POST", "/llm", ASK)[0] == 401
    assert call(lam, "GET", "/prompts")[0] == 401
    assert model == []


def test_one_call_returns_the_message_and_the_model(lam, table, model):
    tools = [{"type": "function", "function": {"name": "add_task", "parameters": {"type": "object", "properties": {}}}}]
    status, body = call(lam, "POST", "/llm", {**ASK, "tools": tools, "timeout_s": 10}, owner_token())
    assert status == 200, body
    assert body == {"message": {"role": "assistant", "content": "Hello!"}, "model": "google/gemini-2.5-flash"}
    assert model[0]["tools"] == tools and model[0]["temperature"] == 0.5 and model[0]["timeout"] == 10
    assert llm_proxy.used_today() == 1


def test_the_phone_cant_ask_for_a_longer_timeout_than_the_servers(lam, table, model):
    call(lam, "POST", "/llm", {**ASK, "timeout_s": 100}, owner_token())
    assert model[0]["timeout"] == 25


def test_tool_calls_are_passed_through(lam, table, monkeypatch):
    calls = [{"id": "c1", "type": "function", "function": {"name": "list_tasks", "arguments": "{\"scope\": \"open\"}"}}]

    async def fake(messages, **kw):
        return {"role": "assistant", "content": None, "tool_calls": calls}

    monkeypatch.setattr(llm_proxy, "chat_completion", fake)
    status, body = call(lam, "POST", "/llm", ASK, owner_token())
    assert status == 200 and body["message"] == {"role": "assistant", "content": "", "tool_calls": calls}
    assert body["model"] is None


def test_the_daily_cap_is_shared_and_says_limit(lam, table, model, monkeypatch):
    monkeypatch.setenv("LLM_CALLS_PER_DAY", "2")
    token = owner_token()
    assert [call(lam, "POST", "/llm", ASK, token)[0] for _ in range(2)] == [200, 200]
    status, body = call(lam, "POST", "/llm", ASK, token)
    assert status == 429 and body["code"] == "limit" and "Reminders still work" in body["detail"]
    assert len(model) == 2 and llm_proxy.used_today() == 2


def test_the_rate_limit(lam, table, model, monkeypatch):
    monkeypatch.setenv("LLM_PER_MINUTE", "3")
    token = owner_token()
    codes = [call(lam, "POST", "/llm", ASK, token)[0] for _ in range(4)]
    assert codes == [200, 200, 200, 429]


def test_no_answer_is_a_503_the_phone_falls_back_on(lam, table, monkeypatch):
    async def down(*a, **kw):
        raise LLMUnavailable("OpenRouter HTTP 502")

    monkeypatch.setattr(llm_proxy, "chat_completion", down)
    status, body = call(lam, "POST", "/llm", ASK, owner_token())
    assert status == 503 and body["code"] == "unavailable"


def test_not_set_up_yet_says_so(lam, table, model, monkeypatch):
    monkeypatch.setenv("OPENROUTER_API_KEY", llm_proxy.NOT_SET)
    status, body = call(lam, "POST", "/llm", ASK, owner_token())
    assert status == 503 and body["code"] == "not_configured" and "aws_lambda_setup.py" in body["detail"]
    assert model == []


def test_bad_requests_are_refused(lam, table, model):
    token = owner_token()
    assert call(lam, "POST", "/llm", {"messages": []}, token)[0] == 422
    assert call(lam, "POST", "/llm", {**ASK, "temperature": 5}, token)[0] == 422
    assert model == []


def test_prompts_lists_every_file_with_a_version(lam, table):
    status, body = call(lam, "GET", "/prompts", token=owner_token())
    assert status == 200
    names = set(body["prompts"])
    assert {"system", "core", "checkin", "daily_morning", "daily_checkin", "daily_evening", "daily_reentry"} <= names
    assert "playbooks/stuck" in names and "{core}" in body["prompts"]["system"]
    assert len(body["version"]) == 16
    assert call(lam, "GET", "/prompts", token=owner_token())[1]["version"] == body["version"]
