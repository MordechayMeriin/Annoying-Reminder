"""Regression tests for the fixes from the November 2026 code review."""

import asyncio
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx
import pytest
from fakes import FakeStore, ScriptedLLM
from fastapi import HTTPException

from app import agent, auth, config, httpclient, notify
from app.logic import ALARM, NORMAL, clip

TZ = ZoneInfo("Asia/Jerusalem")


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(datetime(2026, 11, 9, 10, 0, tzinfo=TZ))
    s.install(monkeypatch.setattr)
    return s


# ---------------------------------------------------------------- storage round trips per chat turn

def test_a_typical_chat_turn_makes_few_storage_calls(store, monkeypatch):
    """The review's scenario: a reply to a check-in about 3 tasks, then one update_task."""
    ts = [store.add_task(f"Task {i}", priority=2, due_at=store.now + timedelta(hours=i)) for i in range(1, 4)]
    store.m_add("assistant", "How are these going?", "nudge", [t["id"] for t in ts])
    store.advance(10)

    calls_at_first_llm = []

    class CountingLLM(ScriptedLLM):
        async def __call__(self, *a, **kw):
            if not calls_at_first_llm:
                calls_at_first_llm.append(len(store.calls))
            return await super().__call__(*a, **kw)

    CountingLLM([{"tool": "update_task", "args": {"id": ts[0]["id"], "priority": 3}}, "Raised it."]).install(
        monkeypatch.setattr)
    store.calls.clear()
    r = asyncio.run(agent.handle_user_message("the first one is urgent now"))

    assert r.reply == "Raised it."
    # Was 18 before the first model call (9 of them: 3 queries for each of 3 candidate tasks) and 20 in all.
    assert calls_at_first_llm[0] <= 9, store.calls
    assert len(store.calls) <= 13, store.calls


def test_progress_from_worker_threads_reaches_the_callback_in_order(store, monkeypatch):
    t = store.add_task("Call the bank")
    ScriptedLLM([{"tool": "complete_task", "args": {"id": t["id"]}}, "Done."]).install(monkeypatch.setattr)
    events = []
    asyncio.run(agent.handle_user_message("called the bank", events.append))
    assert [e["type"] for e in events] == ["received", "thinking", "tool", "thinking"]
    assert events[2]["taskTitle"] == "Call the bank"


def test_a_turn_that_runs_too_long_stops_with_a_message(store, monkeypatch):
    from app import config

    s = config.get_settings()
    monkeypatch.setattr(agent, "get_settings", lambda: config.replace(s, chat_turn_timeout_s=0.05))

    async def slow(*a, **kw):
        await asyncio.sleep(1)
        return {"role": "assistant", "content": "too late"}

    monkeypatch.setattr(agent, "run_tools", lambda *a, **kw: slow())
    r = asyncio.run(agent.handle_user_message("hello"))
    assert r.reply == agent.TIMEOUT_REPLY
    assert store.messages[-1]["content"] == agent.TIMEOUT_REPLY  # saved, so it shows after a refresh


# ---------------------------------------------------------------- clip

def test_clip_keeps_short_text():
    assert clip("hello", 10) == "hello"
    assert clip("x" * 10, 10) == "x" * 10


def test_clip_cuts_at_a_word_boundary():
    out = clip("the quick brown fox jumps over", 20)
    assert out == "the quick brown fox…" and len(out) <= 20


def test_clip_cuts_hebrew_at_a_space():
    out = clip("להתקשר לבנק מחר בבוקר לגבי המשכנתא", 20)
    assert out.endswith("…") and len(out) <= 20 and not out[:-1].endswith(" ")
    assert "להתקשר לבנק מחר".startswith(out[:-1]) or out[:-1] in "להתקשר לבנק מחר בבוקר"


def test_clip_cuts_one_long_word_hard():
    out = clip("a " + "x" * 50, 20)
    assert len(out) == 20 and out.endswith("…")


# ---------------------------------------------------------------- auth: Supabase outages

class FakeAuthClient:
    def __init__(self, status=None, exc=None, body=None):
        self.status, self.exc, self.body = status, exc, body or {}

    async def get(self, *a, **kw):
        if self.exc:
            raise self.exc
        return httpx.Response(self.status, json=self.body)


def owner_check(monkeypatch, client) -> HTTPException | str:
    monkeypatch.setattr(httpclient, "client", lambda: client)
    auth._cache.clear()
    try:
        return asyncio.run(auth.require_owner("Bearer tok"))
    except HTTPException as e:
        return e


@pytest.mark.parametrize("status", [500, 502, 503, 429])
def test_supabase_errors_are_503_not_a_logout(monkeypatch, status):
    e = owner_check(monkeypatch, FakeAuthClient(status))
    assert isinstance(e, HTTPException) and e.status_code == 503


def test_supabase_unreachable_is_503(monkeypatch):
    e = owner_check(monkeypatch, FakeAuthClient(exc=httpx.ConnectTimeout("slow")))
    assert isinstance(e, HTTPException) and e.status_code == 503


@pytest.mark.parametrize("status", [401, 403])
def test_an_invalid_token_is_401(monkeypatch, status):
    e = owner_check(monkeypatch, FakeAuthClient(status))
    assert isinstance(e, HTTPException) and e.status_code == 401


def test_the_owner_gets_in(monkeypatch):
    from app.config import get_settings

    owner = get_settings().owner_user_id
    assert owner_check(monkeypatch, FakeAuthClient(200, body={"id": owner})) == owner


# ---------------------------------------------------------------- browser push switch

class Routing:
    def __init__(self, monkeypatch, *, browser_on, pushover_set_up, mode="always", pushover_ok=True, web_sent=1):
        self.web, self.po = [], []
        monkeypatch.setattr(notify, "browser_push_on", lambda: browser_on)
        monkeypatch.setattr(notify, "pushover_configured", lambda: pushover_set_up)
        monkeypatch.setattr(notify, "pushover_mode", lambda: mode if pushover_set_up else "off")
        monkeypatch.setattr(notify, "send_push", lambda *a, **kw: self.web.append(kw) or web_sent)
        monkeypatch.setattr(notify, "send_pushover", lambda *a, **kw: self.po.append(kw) or pushover_ok)


@pytest.mark.parametrize("mode", ["always", "only", "backup", "off"])
@pytest.mark.parametrize("level", [NORMAL, ALARM])
def test_browser_off_sends_everything_to_pushover(monkeypatch, mode, level):
    c = Routing(monkeypatch, browser_on=False, pushover_set_up=True, mode=mode)
    notify.notify("T", "b", level=level, message_id=3)
    assert (len(c.web), len(c.po)) == (0, 1)


def test_browser_off_never_falls_back_to_the_browser_when_pushover_fails(monkeypatch):
    c = Routing(monkeypatch, browser_on=False, pushover_set_up=True, mode="only", pushover_ok=False)
    notify.notify("T", "b")
    assert (len(c.web), len(c.po)) == (0, 1)


def test_browser_off_without_pushover_still_uses_the_browser(monkeypatch):
    c = Routing(monkeypatch, browser_on=False, pushover_set_up=False)
    notify.notify("T", "b")
    assert len(c.web) == 1 and c.web[0]["force"] is True and not c.po


def test_send_push_does_nothing_when_switched_off(monkeypatch):
    s = config.replace(config.get_settings(), browser_push=False)
    monkeypatch.setattr(notify, "get_settings", lambda: s)
    monkeypatch.setattr(notify.db, "fetch_all", lambda *a, **kw: pytest.fail("no DB read when off"))
    assert notify.send_push("T", "b") == 0


def test_webpush_gets_a_timeout_and_high_urgency(monkeypatch):
    sent = []
    monkeypatch.setattr(notify.db, "fetch_all",
                        lambda *a, **kw: [{"id": 1, "endpoint": "https://push.example/1", "p256dh": "k", "auth": "a"},
                                          {"id": 2, "endpoint": "https://push.example/2", "p256dh": "k", "auth": "a"}])

    def fake_webpush(**kw):
        sent.append(kw)
        if len(sent) == 1:
            raise notify.requests.ConnectTimeout("slow")  # one dead endpoint doesn't stop the rest

    monkeypatch.setattr(notify, "webpush", fake_webpush)
    assert notify.send_push("T", "b") == 1
    assert all(kw["timeout"] == notify.PUSH_TIMEOUT_S and kw["headers"] == {"Urgency": "high"} for kw in sent)


def test_vapid_keys_are_optional(monkeypatch):
    for key in ("VAPID_PRIVATE_KEY", "VAPID_PUBLIC_KEY", "VAPID_SUBJECT"):
        monkeypatch.delenv(key)
    s = config.Settings()
    assert not s.browser_push_available


# ---------------------------------------------------------------- check-in job keys

def test_check_in_jobs_are_claimed_by_time_not_position(monkeypatch):
    from app import scheduler

    class Clock(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime(2026, 11, 10, 13, 5, tzinfo=TZ).astimezone(tz)  # a Tuesday

    claimed = []
    monkeypatch.setattr(scheduler, "datetime", Clock)
    monkeypatch.setattr(scheduler, "claim_job", lambda job, d: claimed.append(job) or True)
    s = config.replace(scheduler.get_settings(), checkin_times=[datetime(2000, 1, 1, 13, 0).time()])
    monkeypatch.setattr(scheduler, "get_settings", lambda: s)
    assert "checkin" in scheduler.due_daily_jobs()
    assert "checkin@13:00" in claimed


# ---------------------------------------------------------------- API: done payload, paging, overview, push config

@pytest.fixture
def client(store, monkeypatch):
    from fastapi.testclient import TestClient

    from app import main, user_settings

    monkeypatch.setattr(user_settings, "load", lambda: None)
    monkeypatch.setattr(main.notify, "cancel_alarms", lambda: None)
    main._chat_times.clear()
    main.app.dependency_overrides[main.require_owner] = lambda: "owner"
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


def test_done_carries_both_saved_messages(client, store, monkeypatch):
    import json

    t = store.add_task("Call the bank")
    ScriptedLLM([{"tool": "update_task", "args": {"id": t["id"], "priority": 2}}, "Bumped it."]).install(
        monkeypatch.setattr)
    body = client.post("/chat", json={"message": "bank is important"}).text
    done = [json.loads(line[5:]) for line in body.splitlines() if line.startswith("data:")][-1]

    assert done["type"] == "done"
    user, reply = done["messages"]
    assert (user["role"], user["content"]) == ("user", "bank is important")
    assert (reply["role"], reply["content"]) == ("assistant", "Bumped it.")
    assert reply["task_ids"] == [t["id"]] and reply["task_titles"] == {str(t["id"]): "Call the bank"}
    assert isinstance(reply["created_at"], str) and reply["created_at"].startswith("2026-11-09T10:00")


def test_messages_after_returns_only_newer(client, store):
    ids = [store.m_add("assistant", f"m{i}")["id"] for i in range(5)]
    rows = client.get(f"/messages?after={ids[2]}").json()
    assert [r["id"] for r in rows] == ids[3:]


def test_tasks_overview_in_one_request(client, store):
    store.add_task("Today thing", due_at=store.now + timedelta(hours=1))
    store.add_task("Later thing", due_at=store.now + timedelta(days=2))
    store.add_task("Someday")
    data = client.get("/tasks/overview").json()
    assert [t["title"] for t in data["today"]] == ["Today thing"]
    assert [t["title"] for t in data["upcoming"]] == ["Later thing"]
    assert len(data["open"]) == 3


def test_push_config_follows_the_switch(client, monkeypatch):
    from app import main

    base = config.get_settings()
    for browser_push, pushover, expect in [(True, False, True), (False, True, False), (False, False, True)]:
        s = config.replace(base, browser_push=browser_push, pushover_token="t" if pushover else "",
                           pushover_user="u" if pushover else "")
        monkeypatch.setattr(main, "get_settings", lambda s=s: s)
        monkeypatch.setattr(notify, "get_settings", lambda s=s: s)
        data = client.get("/config/push").json()
        assert data["enabled"] is expect and (data["vapidPublicKey"] is not None) is expect
