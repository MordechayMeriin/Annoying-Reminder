"""
Phase 3 end to end (in-memory store, scripted LLM): the avoided stage and its single message, parking,
de-escalation, re-entry after a lapse, the Sunday merge and cleanup, fences, and settings / preferences
from chat.
"""

import asyncio
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore, ScriptedLLM

from app import actions, agent, config, prompting, scheduler, user_settings
from app.llm import LLMUnavailable
from app.logic import NORMAL, URGENT

TZ = ZoneInfo("Asia/Jerusalem")
DOWN = LLMUnavailable("down")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)   # Sun 8 Nov 2026 … Sat 14 Nov 2026


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(9, 10))
    s.install(monkeypatch.setattr)
    scheduler._delivering_since = None
    config.apply_overrides({})
    yield s
    config.apply_overrides({})


def at(store, d, h, mi=0):
    store.now = local(d, h, mi).astimezone(store.now.tzinfo)


def tick() -> list[int]:
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))
    ends = scheduler.plan_sessions()
    if ends:
        asyncio.run(scheduler.deliver_session_ends(ends))
    return ids


def use_llm(monkeypatch, *steps) -> ScriptedLLM:
    fake = ScriptedLLM(list(steps))
    fake.install(monkeypatch.setattr)
    return fake


def chat(text):
    return asyncio.run(agent.handle_user_message(text))


def sent(store) -> list[dict]:
    return [m for m in store.messages if m["role"] == "assistant"]


def ignored(store, task, n=3):
    for _ in range(n):
        store.t_add_event(task["id"], "ignored")


@pytest.fixture
def client(store, monkeypatch):
    from fastapi.testclient import TestClient

    from app import main

    monkeypatch.setattr(user_settings, "load", lambda: None)
    monkeypatch.setattr(main.notify, "cancel_alarms", lambda: None)
    main.app.dependency_overrides[main.require_owner] = lambda: "owner"
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


class Clock(datetime):
    store: FakeStore

    @classmethod
    def now(cls, tz=None):
        return cls.store.now.astimezone(tz)


def run_days(store, monkeypatch, start: datetime, end: datetime, step_min: int = 30) -> None:
    """What cron does from start to end: a tick and the daily jobs every `step_min` minutes."""
    claimed = set()
    Clock.store = store
    monkeypatch.setattr(scheduler, "datetime", Clock)
    monkeypatch.setattr(scheduler, "claim_job", lambda job, d: (job, d) not in claimed and not claimed.add((job, d)))
    store.now = start.astimezone(store.now.tzinfo)
    while store.now <= end:
        tick()
        for job in scheduler.due_daily_jobs():
            asyncio.run(scheduler.run_daily_job(job))
        store.advance(step_min)


# ---------------------------------------------------------------- F7: the avoided stage

def test_an_avoided_task_gets_one_reason_message_not_a_check_in(store, monkeypatch):
    t = store.add_task("Taxes", next_action="Open the form", remind_at=local(9, 10), focus=True, priority=3)
    ignored(store, t)
    llm = use_llm(monkeypatch, "Taxes keep slipping, and that's okay. What's in the way?")
    assert tick() == [t["id"]]
    msg = store.messages[-1]
    assert msg["content"] == "Taxes keep slipping, and that's okay. What's in the way?"
    assert [c["id"] for c in msg["chips"]] == ["too_big", "unclear", "boring", "not_now"]
    assert msg["chips"][-1] == {"id": "not_now", "label": "Not now", "action": "not_now", "task_id": t["id"]}
    system, user = (m["content"] for m in llm.requests[0]["messages"])
    assert "Playbook: avoiding" in system and "Too big · Unclear · Boring · Not now" in system
    assert "NOT an ordinary check-in" in user
    assert store.pushovers[-1]["level"] == NORMAL            # never loud, even for a critical task
    after = store.tasks[t["id"]]
    assert (after["avoid_asks"], after["remind_at"]) == (1, store.now + timedelta(days=3))

    for _ in range(40):                                      # the rest of the day and the next: nothing
        store.advance(60)
        tick()
    assert len(sent(store)) == 1


def test_silence_means_one_more_try_in_3_days_then_the_task_is_parked(store, monkeypatch):
    t = store.add_task("Taxes", remind_at=local(9, 10), focus=True)
    ignored(store, t)
    use_llm(monkeypatch, *([DOWN] * 10))
    tick()
    assert store.messages[-1]["content"] == "Taxes keeps slipping, and that's fine. What's in the way?"   # template
    at(store, 12, 10)                                        # 3 days later, Today's 3 has moved on
    assert tick() == [t["id"]]
    assert store.tasks[t["id"]]["avoid_asks"] == 2 and len(sent(store)) == 2
    at(store, 15, 10)                                        # 3 more silent days
    assert tick() == []
    after = store.tasks[t["id"]]
    assert after["parked_until"] == store.now + timedelta(days=7) and after["status"] == "open"
    assert store.events[-1]["kind"] == "parked" and len(sent(store)) == 2      # parked without a word
    for d in range(16, 22):
        at(store, d, 10)
        assert tick() == []
    assert len(sent(store)) == 2


def test_not_now_parks_the_task_for_7_days_without_the_llm(store, monkeypatch, client):
    t = store.add_task("Taxes", remind_at=local(9, 10), focus=True)
    ignored(store, t)
    llm = use_llm(monkeypatch, DOWN)
    tick()
    calls = len(llm.requests)
    r = client.post(f"/tasks/{t['id']}/action", json={"action": "not_now"})
    assert r.status_code == 200 and len(llm.requests) == calls
    after = store.tasks[t["id"]]
    assert after["parked_until"] == store.now + timedelta(days=7) and after["focus_date"] is None
    assert store.messages[-1]["content"] == "[Not now: Taxes — parked until Mon 16/11]"
    at(store, 12, 10)
    assert tick() == [] and len(sent(store)) == 1            # no second try: they answered
    # After the park, only events since the park count: the old ones don't make it "avoided" again.
    at(store, 16, 10, 5)
    store.m_add_event("[What now?]")                          # (they're around: not a lapse)
    store.tasks[t["id"]].update(focus_date=store.focus_day())
    use_llm(monkeypatch, "Taxes: open the form?")
    assert tick() == [t["id"]]
    assert [c["id"] for c in store.messages[-1]["chips"]] == ["start5", "done", "later", "stuck"]


def test_too_big_is_recorded_by_code_and_gets_three_steps_with_start(store, monkeypatch):
    t = store.add_task("Taxes", next_action="Do the taxes", remind_at=local(9, 10), focus=True)
    ignored(store, t)
    use_llm(monkeypatch, DOWN)
    tick()
    store.advance(5)
    step = {"tool": "add_subtask", "args": {"task_id": t["id"], "title": "x"}}
    llm = use_llm(monkeypatch,
                  {**step, "args": {"task_id": t["id"], "title": "Find last year's PDF"}},
                  {**step, "args": {"task_id": t["id"], "title": "Fill in page 1"}},
                  {**step, "args": {"task_id": t["id"], "title": "Send it to the accountant"}},
                  {"tool": "update_task", "args": {"id": t["id"], "next_action": "Find last year's PDF"}},
                  "Three steps: find last year's PDF, fill in page 1, send it. Start with the PDF?")
    r = chat("Too big")
    event = next(e for e in store.events if e["kind"] == "avoided")
    assert (event["task_id"], event["avoid_reason"]) == (t["id"], "too_big")    # logged before the model ran
    assert "Playbook: avoiding" in llm.requests[0]["messages"][0]["content"]
    assert r.task_ids == [t["id"]]
    assert [(c["id"], c["task_id"]) for c in r.reply_message["chips"]] == [("start5", t["id"]), ("done", t["id"])]
    assert len(store.t_subtasks(t["id"])) == 3 and store.tasks[t["id"]]["next_action"] == "Find last year's PDF"
    # A fresh start: it isn't avoided any more, so no second reason message and no park.
    after = store.tasks[t["id"]]
    assert (after["avoid_asks"], after["avoid_asked_at"]) == (0, None)
    assert store.t_event_counts(t["id"]).get("ignored") is None


def test_agent_noticed_reason_and_a_started_session_also_end_the_avoidance(store, monkeypatch):
    t = store.add_task("Call the landlord", remind_at=local(9, 10), focus=True)
    ignored(store, t)
    use_llm(monkeypatch, {"tool": "log_interaction", "args": {"task_id": t["id"], "kind": "stuck",
                                                              "note": "dreads the call", "avoid_reason": "people"}},
            "I'll draft what to say. OK?")
    chat("I just hate calling him")
    assert store.events[-1]["avoid_reason"] == "people" and store.tasks[t["id"]]["avoid_reset_at"] == store.now

    other = store.add_task("Taxes", focus=True, avoid_asks=1, avoid_asked_at=store.now)
    ignored(store, other)
    store.advance(1)
    actions.apply(other["id"], "start", 5)
    assert store.tasks[other["id"]]["avoid_asks"] == 0 and not store.t_event_counts(other["id"]).get("ignored")


def test_an_avoided_task_waits_while_other_tasks_are_due(store, monkeypatch):
    avoided = store.add_task("Taxes", remind_at=local(9, 10), focus=True)
    ignored(store, avoided)
    other = store.add_task("Call the bank", remind_at=local(9, 10), focus=True)
    use_llm(monkeypatch, "The bank?", "What's in the way with the taxes?")
    assert tick() == [other["id"]]                           # the ordinary check-in goes alone
    assert [c["id"] for c in store.messages[-1]["chips"]][:2] == ["start5", "done"]
    store.advance(25)
    assert tick() == [avoided["id"]]                         # the reason message: its own message, after the gap
    assert store.messages[-1]["chips"][0]["id"] == "too_big"


# ---------------------------------------------------------------- F7: de-escalation

def test_undated_tasks_get_one_normal_check_in_a_day(store, monkeypatch):
    t = store.add_task("Sort the photos", priority=3, remind_at=local(9, 10), focus=True)
    # Even when the coach asks for an earlier follow-up, the one-a-day cap holds.
    use_llm(monkeypatch, {"tool": "schedule_followup", "args": {"task_id": t["id"], "minutes_from_now": 30,
                                                                "reason": "soon"}}, "Photos?", "Photos again?")
    tick()
    for _ in range(20):
        store.advance(30)
        tick()
    assert len(sent(store)) == 1 and [p["level"] for p in store.pushovers] == [NORMAL]
    assert store.tasks[t["id"]]["remind_at"] == local(10, 7)  # tomorrow, after quiet hours


def test_a_near_deadline_still_escalates(store, monkeypatch):
    store.add_task("File the report", priority=2, remind_at=local(9, 10), due_at=local(9, 18), focus=True)
    use_llm(monkeypatch, *([DOWN] * 5))
    for _ in range(3):
        tick()
        store.advance(20)
    assert [p["level"] for p in store.pushovers][:2] == [URGENT, URGENT] and len(store.pushovers) == 3


# ---------------------------------------------------------------- F7: re-entry after a lapse

def test_three_silent_days_give_exactly_one_reentry_message_and_nothing_loud(store, monkeypatch):
    at(store, 8, 20)
    store.m_add("user", "good night")                         # the last activity: Sunday 20:00
    store.add_task("Sort the photos", priority=3, remind_at=local(9, 10), next_action="Open the folder")
    store.add_task("Renew passport", priority=2, due_at=local(2, 9), remind_at=local(9, 11))   # long overdue
    meeting = store.add_task("Team sync", kind="meeting", due_at=local(11, 15), remind_at=local(11, 14, 30))
    use_llm(monkeypatch, *([DOWN] * 200))
    run_days(store, monkeypatch, local(9, 6), local(12, 23))  # Monday 06:00 → Thursday 23:00

    by_day: dict[int, list[str]] = {}
    for m in sent(store):
        by_day.setdefault(m["created_at"].astimezone(TZ).day, []).append(m["kind"])
    assert by_day[9][0] == "morning" and "evening" in by_day[9]              # day 1: as usual
    assert by_day[10][0] == "morning" and "evening" not in by_day[10]        # 48 hours pass on Tuesday at 20:00
    assert by_day[11] == ["reentry", "meeting", "meeting"]                   # day 3: one re-entry; meetings go on
    assert by_day.get(12, []) == []                                          # still away: quiet, no second one
    reentry = next(m for m in sent(store) if m["kind"] == "reentry")
    assert reentry["content"] == "Good to see you. Want me to pick one thing for today?"
    assert [c["id"] for c in reentry["chips"]] == ["pick_one", "move_overdue"]
    assert reentry["created_at"] == local(11, 7, 30)

    meeting_msgs = {m["id"] for m in sent(store) if m["task_ids"] == [meeting["id"]]}
    loud = [p for p in store.pushovers if p["level"] > NORMAL]
    assert len(loud) == 2 and all(p["url"].endswith(tuple(f"m={i}" for i in meeting_msgs)) for p in loud)
    # no task check-in after the lapse began (Tuesday 20:00)
    assert not [m for m in sent(store) if m["kind"] == "nudge" and m["created_at"] >= local(10, 20)]


def test_coming_back_ends_the_lapse(store, monkeypatch):
    at(store, 5, 20)
    store.m_add("user", "bye")
    t = store.add_task("Taxes", remind_at=local(9, 10), due_at=local(9, 9))
    asked = store.add_task("Call mum", remind_at=local(9, 10), remind_requested=True)
    use_llm(monkeypatch, *([DOWN] * 5))
    at(store, 9, 10)                                          # Thursday 20:00 → Monday 10:00: 58 hours without Shabbat
    assert scheduler.is_lapsed(store.now)
    assert tick() == [asked["id"]]                            # a reminder they asked for still arrives
    at(store, 9, 10, 30)
    assert tick() == []
    store.m_add_event("[What now?]")                          # one tap: they're back
    assert not scheduler.is_lapsed(store.now)
    assert tick() == [t["id"]]


def test_shabbat_alone_is_not_a_lapse(store):
    at(store, 13, 12)
    store.m_add("user", "shabbat shalom")                     # Friday noon
    at(store, 15, 13)                                         # Sunday 13:00: 49 hours later
    assert not scheduler.is_lapsed(store.now)
    at(store, 16, 16)
    assert scheduler.is_lapsed(store.now)


def test_reentry_buttons(store, monkeypatch, client):
    at(store, 9, 7, 30)
    old = store.add_task("Renew passport", due_at=local(3, 9), next_action="Find the old passport")
    weekly = store.add_task("Water the plants", due_at=local(5, 8), recurrence="weekly")
    future = store.add_task("Pay rent", due_at=local(10, 9))
    meeting = store.add_task("Missed meeting", kind="meeting", due_at=local(9, 7))
    llm = use_llm(monkeypatch, "Good to see you. Shall I pick one thing for today?")
    daily = asyncio.run(scheduler.coach.compose_reentry(store.now))
    assert daily.kind == "reentry" and daily.used_llm
    system = llm.requests[0]["messages"][0]["content"]
    assert "Playbook: reentry" in system and "Pick one for me · Move overdue to next week" in system

    r = client.post("/tasks/bulk-action", json={"action": "move_overdue"})
    assert sorted(t["id"] for t in r.json()["tasks"]) == [old["id"], weekly["id"]]
    assert store.tasks[old["id"]]["due_at"] == local(10, 9)                  # Tuesday → next Tuesday, same time
    assert store.tasks[weekly["id"]]["due_at"] == local(12, 8)               # its next occurrence
    assert store.tasks[future["id"]]["due_at"] == local(10, 9) and store.tasks[meeting["id"]]["due_at"] == local(9, 7)
    assert store.messages[-1]["content"] == "[Moved 2 overdue tasks to next week: Renew passport · Water the plants]"
    assert not [e for e in store.events if e["kind"] == "postponed"]         # an amnesty, not a postponement

    llm = use_llm(monkeypatch, {"tool": "set_focus", "args": {"task_ids": [future["id"]]}},
                  "Pay rent: open the bank app.")
    reply = chat("Pick one for me")
    assert "Playbook: reentry" in llm.requests[0]["messages"][0]["content"]
    assert store.tasks[future["id"]]["focus_date"] == store.focus_day()
    assert [c["id"] for c in reply.reply_message["chips"]] == ["start5", "done"]


def test_reentry_without_anything_overdue_has_one_button(store, monkeypatch):
    store.add_task("Sort photos")
    use_llm(monkeypatch, DOWN)
    daily = asyncio.run(scheduler.coach.compose_reentry(store.now))
    assert [c["id"] for c in daily.chips] == ["pick_one"]


# ---------------------------------------------------------------- F8: Sunday

def sunday(store):
    at(store, 8, 7, 30)
    month_ago = store.now - timedelta(days=30)
    for title in ("Report", "Invoice", "Call the bank"):
        store.add_task(title, priority=2)
    stale = [store.add_task(title, created_at=month_ago) for title in ("Learn Spanish", "Fix the shelf")]
    moving = store.add_task("Write the book", created_at=month_ago)
    store.events.append({"task_id": moving["id"], "kind": "progress", "note": None, "avoid_reason": None,
                         "created_at": store.now - timedelta(days=4)})
    waiting = store.add_task("Visa", created_at=month_ago, due_at=local(20, 9))
    for i in range(2):
        done = store.add_task(f"done {i}", status="done")
        store.events.append({"task_id": done["id"], "kind": "done", "note": None, "avoid_reason": None,
                             "created_at": store.now - timedelta(days=3)})
    store.add_task("Dentist", kind="meeting", due_at=local(10, 9, 30))
    return stale, moving, waiting


def test_sunday_is_one_message_with_wins_cleanup_and_the_weeks_meetings(store, monkeypatch):
    stale, _, _ = sunday(store)
    use_llm(monkeypatch, *([DOWN] * 5))
    run_days(store, monkeypatch, local(8, 7, 30), local(8, 11))
    msgs = sent(store)
    assert [m["kind"] for m in msgs] == ["morning"]                          # one message, not two
    assert msgs[0]["content"] == (
        "☀️ Today's 3:\n1. Report\n2. Invoice\n3. Call the bank\n\n"
        "✅ Last week you finished 2 things.\n"
        "🧹 Open for 3+ weeks with no progress: Learn Spanish · Fix the shelf. Drop them or keep?\n\n"
        "📅 Your meetings this week:\nTue 10/11 09:30 — Dentist")
    chips = msgs[0]["chips"]
    assert [c["id"] for c in chips] == ["start_first", "change", "drop", "keep"]
    assert chips[2]["task_ids"] == chips[3]["task_ids"] == [t["id"] for t in stale]
    assert len(store.pushes) == 1


def test_sunday_with_the_llm_adds_the_systems_lines_under_its_plan(store, monkeypatch):
    sunday(store)
    llm = use_llm(monkeypatch, "Today: the report first.")
    asyncio.run(scheduler.run_daily_job("morning"))
    system = llm.requests[0]["messages"][0]["content"]
    assert "Playbook: planning" in system and "Playbook: sunday" in system
    text = store.messages[-1]["content"]
    assert text.startswith("Today: the report first.\n\n✅ Last week") and "📅 Your meetings this week:" in text


def test_drop_them_cancels_exactly_the_listed_tasks_and_silence_keeps_them(store, monkeypatch, client):
    stale, moving, waiting = sunday(store)
    use_llm(monkeypatch, *([DOWN] * 5))
    asyncio.run(scheduler.run_daily_job("morning"))
    ids = store.messages[-1]["chips"][2]["task_ids"]
    open_before = {t["id"] for t in store.tasks.values() if t["status"] == "open"}

    # Silence keeps them, and they aren't offered again the next Sunday (4 weeks).
    at(store, 15, 7, 30)
    asyncio.run(scheduler.run_daily_job("morning"))
    assert "🧹" not in store.messages[-1]["content"] and "drop" not in [c["id"] for c in store.messages[-1]["chips"]]
    assert {t["id"] for t in store.tasks.values() if t["status"] == "open"} == open_before

    r = client.post("/tasks/bulk-action", json={"action": "keep", "task_ids": ids})
    assert r.status_code == 200 and store.messages[-1]["content"] == "[Kept: Learn Spanish · Fix the shelf]"
    assert {t["id"] for t in store.tasks.values() if t["status"] == "open"} == open_before

    client.post("/tasks/bulk-action", json={"action": "drop", "task_ids": ids})
    cancelled = {t["id"] for t in store.tasks.values() if t["status"] == "cancelled"}
    assert cancelled == {t["id"] for t in stale}
    assert store.tasks[moving["id"]]["status"] == store.tasks[waiting["id"]]["status"] == "open"
    assert store.messages[-1]["content"] == "[Dropped: Learn Spanish · Fix the shelf]"


def test_a_weekday_morning_has_no_sunday_lines(store, monkeypatch):
    sunday(store)
    at(store, 9, 7, 30)
    use_llm(monkeypatch, DOWN)
    asyncio.run(scheduler.run_daily_job("morning"))
    text = store.messages[-1]["content"]
    assert "🧹" not in text and "✅" not in text and "meetings this week" not in text


# ---------------------------------------------------------------- F9: fences

def test_a_fence_fires_during_a_running_session(store, monkeypatch):
    work = store.add_task("Write the report", next_action="List the headings", focus=True)
    fence = store.add_task("Pick up the kids", kind="fence", due_at=local(9, 10, 10), remind_at=local(9, 10, 10),
                           remind_requested=True, recurrence="weekdays")
    llm = use_llm(monkeypatch)
    actions.start_session(work["id"], 25)                     # 10:00–10:25: other check-ins are held
    store.m_add("assistant", "an earlier message", "nudge")   # ... and the gap would hold one too
    at(store, 9, 10, 10)
    assert tick() == [fence["id"]]
    msg = store.messages[-1]
    assert msg["content"] == "⛔ 10:10 — Pick up the kids. Time to stop."
    assert (msg["kind"], msg["chips"]) == ("reminder", [])
    assert store.pushovers[-1]["level"] == URGENT and store.pushes[-1]["title"] == "⛔ Pick up the kids"
    assert llm.requests == []                                 # written by code: it never fails
    after = store.tasks[fence["id"]]
    assert after["status"] == "open" and after["due_at"] == after["remind_at"] == local(10, 10, 10)
    assert store.sessions[0]["ended_at"] is None              # the session itself goes on
    store.advance(5)
    assert tick() == []                                       # once: no follow-up nagging


def test_a_one_off_fence_closes_and_a_late_one_is_skipped(store, monkeypatch):
    use_llm(monkeypatch)
    once = store.add_task("Leave for the airport", kind="fence", due_at=local(9, 10), remind_at=local(9, 10),
                          remind_requested=True)
    assert tick() == [once["id"]]
    assert store.tasks[once["id"]]["status"] == "done" and not [e for e in store.events if e["kind"] == "done"]

    night = store.add_task("Go to bed", kind="fence", due_at=local(9, 23, 30), remind_at=local(9, 23, 30),
                           remind_requested=True, recurrence="daily")
    at(store, 9, 23, 30)
    assert tick() == []                                       # quiet hours are never overruled
    assert store.tasks[night["id"]]["remind_at"] == local(10, 7)
    at(store, 10, 7)
    assert tick() == [] and len(sent(store)) == 1             # seven hours late: skipped, not sent
    assert store.tasks[night["id"]]["due_at"] == local(10, 23, 30)


def test_a_fence_is_learned_from_chat_and_what_now_respects_it(store, monkeypatch):
    llm = use_llm(monkeypatch, {"tool": "add_task", "args": {"title": "Pick up the kids", "kind": "fence",
                                                             "due_at": "2026-11-09T10:20", "recurrence": "weekdays"}},
                  "Got it: I'll stop you at 10:20 on weekdays.")
    chat("btw I pick up the kids at 10:20 every weekday")
    fence = next(t for t in store.tasks.values() if t["kind"] == "fence")
    assert (fence["due_at"], fence["remind_at"], fence["recurrence"]) == (local(9, 10, 20), local(9, 10, 20), "weekdays")
    assert "hard stop" in llm.requests[1]["messages"][-1]["content"]
    assert store.messages[-1]["chips"] == []                  # no ▶ Start under a hard stop

    store.add_task("Write the report", next_action="List the headings", estimate_min=40, focus=True)
    answer = actions.what_now()[1]
    assert answer["content"] == "Before 10:20: just 5 minutes of this: List the headings — Write the report."


# ---------------------------------------------------------------- F12: settings and preferences from chat

class SettingsDB:
    """Just enough of db.* for user_settings.save / load."""

    def __init__(self):
        self.rows: dict[str, str] = {}

    def fetch_all(self, sql, params=()):
        return [{"key": k, "value": v} for k, v in self.rows.items()]

    def connect(self):
        db = self

        class Conn:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def transaction(self):
                return self

            def execute(self, sql, params):
                if sql.lstrip().startswith("insert"):
                    db.rows[params[0]] = params[1]
                else:
                    for k in params[0]:
                        db.rows.pop(k, None)

        return Conn()


@pytest.fixture
def settings_db(monkeypatch):
    fake = SettingsDB()
    monkeypatch.setattr(user_settings.db, "fetch_all", fake.fetch_all)
    monkeypatch.setattr(user_settings.db, "connect", fake.connect)
    return fake


def test_no_messages_after_10pm_changes_quiet_start(store, monkeypatch, settings_db):
    llm = use_llm(monkeypatch, {"tool": "update_setting", "args": {"key": "quiet_start", "value": "22:00"}},
                  "Done: quiet hours now start at 22:00 (was 23:00). Say 'undo' to revert.")
    chat("stop messaging me after 10pm")
    assert config.get_settings().quiet_start == time(22) and settings_db.rows == {"quiet_start": "22:00"}
    result = llm.requests[1]["messages"][-1]["content"]
    assert '"old": "23:00"' in result and '"new": "22:00"' in result
    assert scheduler.guardrails().quiet_start == time(22)      # applies at once, no screen visited

    use_llm(monkeypatch, {"tool": "update_setting", "args": {"key": "quiet_start", "value": "23:00"}}, "Reverted.")
    chat("undo")
    assert config.get_settings().quiet_start == time(23)


def test_a_bad_setting_value_goes_back_to_the_model_and_changes_nothing(store, monkeypatch, settings_db):
    llm = use_llm(monkeypatch, {"tool": "update_setting", "args": {"key": "morning_time", "value": "early"}},
                  {"tool": "update_setting", "args": {"key": "morning_time", "value": "06:30"}}, "Done: 06:30.")
    chat("send the morning plan early, at half past six")
    assert "use HH:MM" in llm.requests[1]["messages"][-1]["content"]
    assert config.get_settings().morning_time == time(6, 30)
    use_llm(monkeypatch, {"tool": "update_setting", "args": {"key": "morning_time", "value": "default"}}, "Back.")
    chat("actually put it back")
    assert config.get_settings().morning_time == time(7, 30) and settings_db.rows == {}


def test_a_preference_is_remembered_and_reaches_every_prompt(store, monkeypatch, client):
    use_llm(monkeypatch, {"tool": "remember_preference", "args": {"text": "Keep messages to one sentence"}},
            "Will do.")
    chat("shorter messages please")
    assert [p["text"] for p in store.preferences] == ["Keep messages to one sentence"]
    for frame in ("system", "checkin", "daily_morning", "daily_checkin", "daily_evening", "daily_reentry"):
        rendered = prompting.render(frame)
        assert "# What the user asked of you (always follow these)\n- Keep messages to one sentence" in rendered

    t = store.add_task("Taxes", remind_at=store.now, focus=True)
    llm = use_llm(monkeypatch, "Taxes?")
    assert tick() == [t["id"]]
    assert "Keep messages to one sentence" in llm.requests[0]["messages"][0]["content"]

    listed = client.get("/preferences").json()
    assert listed == [{"id": 1, "text": "Keep messages to one sentence"}]
    assert client.delete("/preferences/1").json() == [] and client.delete("/preferences/1").status_code == 404
    assert "What the user asked" not in prompting.render("system")


def test_chat_gets_twelve_tools_and_the_other_contexts_their_subset(store, monkeypatch):
    llm = use_llm(monkeypatch, "Hi.")
    chat("hello")
    names = [t["function"]["name"] for t in llm.requests[0]["tools"]]
    assert len(names) == 12 and {"update_setting", "remember_preference"} <= set(names)
    t = store.add_task("Taxes", remind_at=store.now, focus=True)
    llm = use_llm(monkeypatch, {"tool": "update_setting", "args": {"key": "max_nags", "value": "50"}}, "Taxes?")
    tick()
    assert [x["function"]["name"] for x in llm.requests[0]["tools"]] == ["schedule_followup"]
    assert "not available here" in llm.requests[1]["messages"][-1]["content"]   # a check-in can't change settings
    assert t["id"] in store.messages[-1]["task_ids"]
