"""Pushover: how loud each message is, routing between channels, alarms and cancelling them."""

import asyncio
from dataclasses import replace
from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx
import pytest
from fakes import FakeStore, ScriptedLLM

from app import config, notify, scheduler
from app.logic import ALARM, NORMAL, URGENT, AlertPolicy, alarm_window_s, alert_level

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


@pytest.fixture(autouse=True)
def clean_overrides():
    config.apply_overrides({})
    yield
    config.apply_overrides({})


def task(**kw):
    return {"kind": "task", "priority": 1, "remind_requested": False, **kw}


# ---------------------------------------------------------------- levels (pure)

def test_ordinary_task_gets_louder_when_ignored_but_never_alarms_by_default():
    p = AlertPolicy()  # urgent after 1, alarms for important+ after 2, max 4 check-ins
    assert [alert_level(task(), n, p) for n in range(4)] == [NORMAL, URGENT, URGENT, URGENT]


def test_important_task_alarms_after_two_ignored():
    p = AlertPolicy()
    assert [alert_level(task(priority=2), n, p) for n in range(4)] == [URGENT, URGENT, ALARM, ALARM]


def test_alarm_for_setting():
    assert alert_level(task(priority=2), 3, AlertPolicy(alarm_for="critical")) == URGENT
    assert alert_level(task(priority=3), 3, AlertPolicy(alarm_for="critical")) == ALARM
    assert alert_level(task(), 2, AlertPolicy(alarm_for="all")) == ALARM
    assert alert_level(task(priority=3), 9, AlertPolicy(alarm_for="off")) == URGENT


def test_last_check_in_is_the_alarm_when_nagging_stops_sooner():
    p = AlertPolicy(alarm_after=10, max_nags=4)
    assert alert_level(task(priority=3), 2, p) == URGENT
    assert alert_level(task(priority=3), 3, p) == ALARM  # 4th and last check-in, all ignored


def test_meetings_and_requested_reminders_are_urgent_never_alarms():
    p = AlertPolicy(alarm_for="all", alarm_after=0)
    assert alert_level(task(kind="meeting", priority=3), 5, p) == URGENT
    assert alert_level(task(remind_requested=True), 0, AlertPolicy()) == URGENT


def test_alarm_window_stops_before_quiet_hours_and_shabbat():
    q = time(23)
    assert alarm_window_s(local(9, 10), q, None) == 3600
    assert alarm_window_s(local(9, 22, 50), q, None) == 600
    assert alarm_window_s(local(13, 15), q, local(13, 15, 20)) == 1200  # Friday, quiet before Shabbat
    assert alarm_window_s(local(9, 10), q, local(9, 9)) == 3600         # a past start doesn't count


def test_choice_settings_validate():
    assert config.validate({"pushover_mode": "Only"}) == ({"pushover_mode": "only"}, {})
    assert config.validate({"pushover_sound_urgent": "siren"}) == ({"pushover_sound_urgent": "siren"}, {})
    assert "pushover_mode" in config.validate({"pushover_mode": "loud"})[1]
    assert "pushover_sound" in config.validate({"pushover_sound": "kazoo"})[1]


# ---------------------------------------------------------------- routing

class Calls:
    def __init__(self, monkeypatch, mode, web_sent=1, pushover_ok=True):
        self.web, self.po = [], []
        monkeypatch.setattr(notify, "pushover_mode", lambda: mode)
        monkeypatch.setattr(notify, "send_push", lambda *a, **kw: self.web.append(kw) or web_sent)
        monkeypatch.setattr(notify, "send_pushover", lambda *a, **kw: self.po.append(kw) or pushover_ok)


@pytest.mark.parametrize("mode, level, web_sent, pushover_ok, expect_web, expect_po", [
    ("always", NORMAL, 1, True, 1, 1),
    ("only", NORMAL, 1, True, 0, 1),
    ("only", NORMAL, 1, False, 1, 1),     # Pushover failed → browser push
    ("backup", NORMAL, 1, True, 1, 0),
    ("backup", NORMAL, 0, True, 1, 1),    # no browser got it → Pushover
    ("backup", ALARM, 1, True, 1, 1),     # alarms always go to Pushover
    ("off", ALARM, 1, True, 1, 0),
])
def test_modes(monkeypatch, mode, level, web_sent, pushover_ok, expect_web, expect_po):
    c = Calls(monkeypatch, mode, web_sent, pushover_ok)
    notify.notify("T", "body", level=level, message_id=7)
    assert (len(c.web), len(c.po)) == (expect_web, expect_po)
    if c.po:
        assert c.po[0]["url"] == "https://web.example/?m=7" and c.po[0]["level"] == level


def test_urgent_messages_stay_on_screen_in_the_browser(monkeypatch):
    c = Calls(monkeypatch, "off")
    notify.notify("T", "b", level=URGENT)
    notify.notify("T", "b", level=NORMAL)
    assert [w["priority"] for w in c.web] == [2, 1]


# ---------------------------------------------------------------- the Pushover API

@pytest.fixture
def api(monkeypatch):
    """Pushover configured, httpx.post recorded."""
    calls = []
    s = replace(config.get_settings(), pushover_token="tok", pushover_user="usr")
    monkeypatch.setattr(notify, "get_settings", lambda: s)

    def post(url, data=None, timeout=None):
        calls.append({"url": url, "data": data})
        return httpx.Response(200, json={"status": 1}, request=httpx.Request("POST", url))

    monkeypatch.setattr(notify.httpx, "post", post)
    monkeypatch.setattr(notify, "_alarm_until", None)
    return calls


def test_payloads_by_level(api):
    notify.send_pushover("Hi", "normal", level=NORMAL)
    notify.send_pushover("Hi", "urgent", level=URGENT, url="https://web.example/?m=3")
    notify.send_pushover("Hi", "alarm", level=ALARM, expire_s=600)
    normal, urgent, alarm = (c["data"] for c in api)
    assert normal["priority"] == 0 and "sound" not in normal       # Pushover app default
    assert urgent["priority"] == 1 and urgent["sound"] == "persistent"
    assert urgent["url"] == "https://web.example/?m=3"
    assert alarm["priority"] == 2 and alarm["retry"] == 120 and alarm["expire"] == 600
    assert alarm["tags"] == notify.ALARM_TAG


def test_cancel_only_calls_the_api_while_an_alarm_may_be_running(api):
    notify.cancel_alarms()
    assert api == []                                   # nothing running → no call
    notify.send_pushover("Hi", "alarm", level=ALARM, expire_s=600)
    notify.cancel_alarms()
    assert api[-1]["url"].endswith(f"/receipts/cancel_by_tag/{notify.ALARM_TAG}.json")
    notify.cancel_alarms()
    assert len(api) == 2                               # already cancelled


def test_after_a_restart_the_first_interaction_cancels_once(api, monkeypatch):
    monkeypatch.setattr(notify, "_alarm_until", datetime.max.replace(tzinfo=timezone.utc))
    notify.cancel_alarms()
    notify.cancel_alarms()
    assert len(api) == 1


# ---------------------------------------------------------------- end to end (/tick)

@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(9, 10))
    s.install(monkeypatch.setattr)
    ScriptedLLM([]).install(monkeypatch.setattr)  # empty replies → template text
    scheduler._delivering_since = None
    return s


def tick():
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))
    return ids


def test_ignored_important_task_escalates_to_an_alarm(store):
    t = store.add_task("File the tax report", priority=2, remind_at=local(9, 10))
    for _ in range(3):
        assert tick() == [t["id"]]
        store.advance(20)
    assert [p["level"] for p in store.pushovers] == [URGENT, URGENT, ALARM]
    assert store.pushovers[-1]["expire_s"] == 3600
    assert store.tasks[t["id"]]["escalated"]


def test_a_reply_resets_the_ladder(store):
    t = store.add_task("Call the plumber", remind_at=local(9, 10))
    tick()
    store.advance(30)
    tick()
    store.advance(1)
    store.m_add("user", "on it")
    store.advance(29)
    tick()
    assert [p["level"] for p in store.pushovers] == [NORMAL, URGENT, NORMAL]
    assert store.tasks[t["id"]]["nag_count"] == 3


def test_alarm_is_downgraded_right_before_quiet_hours(store):
    store.now = local(9, 22, 59).astimezone(timezone.utc)
    store.add_task("Critical thing", priority=3, nag_count=2, last_notified_at=store.now - timedelta(minutes=15),
                   remind_at=store.now)
    tick()
    assert store.pushovers[-1]["level"] == URGENT  # 1 minute left: an alarm couldn't repeat even once


def test_alarm_stops_at_quiet_hours(store):
    store.now = local(9, 22, 30).astimezone(timezone.utc)
    store.add_task("Critical thing", priority=3, nag_count=2, last_notified_at=store.now - timedelta(minutes=15),
                   remind_at=store.now)
    tick()
    assert store.pushovers[-1]["level"] == ALARM and store.pushovers[-1]["expire_s"] == 1800


def test_daily_messages_are_normal(store, monkeypatch):
    monkeypatch.setattr(scheduler.coach, "compose_daily", lambda job, now: _async("Plan for today"))
    asyncio.run(scheduler.run_daily_job("morning"))
    assert store.pushovers[-1]["level"] == NORMAL


async def _async(value):
    return value
