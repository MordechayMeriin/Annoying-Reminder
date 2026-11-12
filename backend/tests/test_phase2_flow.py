"""
Phase 2 end to end (in-memory store, scripted LLM): What now?, focus sessions with their end check-in,
silence → paused, the focus hold, breadcrumbs and the wins of the day.
"""

import asyncio
import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from fakes import FakeStore, ScriptedLLM

from app import actions, agent, scheduler
from app.config import get_settings
from app.llm import LLMUnavailable
from app.logic import URGENT
from app.tokens import make_action_token

TZ = ZoneInfo("Asia/Jerusalem")


def local(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


@pytest.fixture
def store(monkeypatch):
    s = FakeStore(local(9, 10))
    s.install(monkeypatch.setattr)
    scheduler._delivering_since = None
    return s


def at(store, h, mi=0, d=9):
    store.now = local(d, h, mi).astimezone(store.now.tzinfo)


def tick() -> tuple[list[int], list[int]]:
    """What /tick does: task check-ins, then session ends."""
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))
    ends = scheduler.plan_sessions()
    if ends:
        asyncio.run(scheduler.deliver_session_ends(ends))
    return ids, ends


def use_llm(monkeypatch, *steps) -> ScriptedLLM:
    fake = ScriptedLLM(list(steps))
    fake.install(monkeypatch.setattr)
    return fake


@pytest.fixture
def client(store, monkeypatch):
    from fastapi.testclient import TestClient

    from app import main, user_settings

    monkeypatch.setattr(user_settings, "load", lambda: None)
    monkeypatch.setattr(main.notify, "cancel_alarms", lambda: None)
    main.app.dependency_overrides[main.require_owner] = lambda: "owner"
    yield TestClient(main.app)
    main.app.dependency_overrides.clear()


def token(task_id: int) -> str:
    return make_action_token(task_id, get_settings().action_secret)


# ---------------------------------------------------------------- F5: one tap → a session → the end check-in

def test_one_tap_on_a_notification_starts_a_5_minute_session(store, monkeypatch, client):
    t = store.add_task("Taxes", next_action="Open the form", remind_at=local(9, 10), focus=True)
    use_llm(monkeypatch, "Open the form?")
    tick()
    assert [a["action"] for a in store.pushes[-1]["actions"]] == ["start", "done"]
    r = client.post("/actions", json={"token": token(t["id"]), "action": "start", "minutes": 5})
    session = r.json()["session"]
    assert (session["task_id"], session["planned_min"], session["next_action"]) == (t["id"], 5, "Open the form")
    assert session["ends_at"].startswith("2026-11-09T10:05")
    assert store.messages[-1]["content"] == "[▶ 5 min: Open the form (Taxes)]"


def test_session_end_check_in_then_silence_means_paused(store, monkeypatch):
    t = store.add_task("Taxes", next_action="Open the form", focus=True, priority=2)
    llm = use_llm(monkeypatch)
    actions.start_session(t["id"], 5)
    store.advance(4)
    assert tick() == ([], [])                          # still running: nothing
    store.advance(5)                                   # first tick after the end (10:09)
    assert tick()[1] == [1]
    msg = store.messages[-1]
    assert (msg["kind"], msg["task_ids"]) == ("session", [t["id"]])
    assert msg["content"] == "⏱ Time's up: Open the form. Done, another round, or stop?"
    assert [(c["id"], c["task_id"]) for c in msg["chips"]] == [("done", 1), ("another", 1), ("stop", 1)]
    push = store.pushes[-1]
    assert push["title"] == "⏱ Taxes" and [a["action"] for a in push["actions"]] == ["done", "another"]
    assert store.pushovers[-1]["level"] == URGENT
    assert llm.requests == []                          # a template: no LLM, never fails

    sent = len(store.messages)
    store.advance(10)
    tick()
    assert store.sessions[0]["outcome"] is None        # 10 minutes of silence: still waiting
    store.advance(5)
    tick()                                             # 15 minutes after the check-in
    s = store.sessions[0]
    assert (s["outcome"], s["ended_at"]) == ("paused", local(9, 10, 5))   # actual = planned on silence
    assert store.events[-1]["kind"] == "paused"
    assert len(store.messages) == sent                 # nothing more is sent
    assert store.tasks[t["id"]]["focus_date"] == store.focus_day() and store.tasks[t["id"]]["status"] == "open"
    for _ in range(6):
        store.advance(10)
        tick()
    assert len(store.messages) == sent


def test_no_other_check_in_during_a_session_but_a_meeting_comes_through(store, monkeypatch):
    focus_task = store.add_task("Taxes", focus=True)
    other = store.add_task("Call the bank", remind_at=local(9, 10, 10), focus=True)
    meeting = store.add_task("Team sync", kind="meeting", due_at=local(9, 10, 30), remind_at=local(9, 10, 15))
    use_llm(monkeypatch, "Anything to prepare?", "The bank?")
    actions.start_session(focus_task["id"], 25)                      # 10:00–10:25, hold until 10:35
    seen = []
    for _ in range(6):                                              # 10:05 … 10:30
        store.advance(5)
        tick()
        seen += [m for m in store.messages if m["role"] == "assistant" and m not in seen]
    assert [m["kind"] for m in seen] == ["meeting", "session", "meeting"]   # 10:15, 10:25 end, 10:30 start
    assert all(other["id"] not in m["task_ids"] for m in seen)
    assert store.tasks[other["id"]]["remind_at"] == local(9, 10, 35)
    assert meeting["id"] in seen[0]["task_ids"]
    store.advance(5)
    tick()                                                           # 10:35: the hold is over, but the
    assert store.tasks[other["id"]]["remind_at"] == local(9, 10, 50)  # 10:30 meeting started the gap
    at(store, 10, 50)
    tick()
    assert other["id"] in store.messages[-1]["task_ids"]


def test_the_tasks_own_check_in_inside_the_session_is_replaced_by_the_end_check_in(store, monkeypatch):
    t = store.add_task("Taxes", remind_at=local(9, 10, 10), focus=True)
    later = store.add_task("Dentist", remind_at=local(9, 17), remind_requested=True)
    actions.start_session(t["id"], 25)
    actions.start_session(later["id"], 5)    # a new session closes the running one
    assert store.tasks[t["id"]]["remind_at"] is None
    assert store.tasks[later["id"]]["remind_at"] == local(9, 17)   # outside the session: kept
    assert [s["outcome"] for s in store.sessions] == ["stopped", None]


def test_done_on_the_session_end_closes_session_and_task_without_the_llm(store, monkeypatch, client):
    t = store.add_task("Taxes", focus=True, resume_note="page 3")
    llm = use_llm(monkeypatch)
    actions.start_session(t["id"], 5)
    store.advance(7)
    tick()
    r = client.post("/actions", json={"token": token(t["id"]), "action": "done"})   # ✓ Done on the notification
    assert r.status_code == 200 and llm.requests == []
    s = store.sessions[0]
    assert (s["outcome"], s["ended_at"]) == ("done", store.now)          # actual runs until the tap
    task = store.tasks[t["id"]]
    assert task["status"] == "done" and task["resume_note"] is None   # cleared on Done
    assert store.messages[-1]["content"] == "[Marked done: Taxes]"


def test_another_round_starts_a_session_of_the_same_length(store, client):
    t = store.add_task("Taxes", next_action="Open the form", focus=True)
    sid = client.post(f"/tasks/{t['id']}/start", json={}).json()["session"]["id"]   # 25 from the app
    store.advance(27)
    r = client.post(f"/sessions/{sid}/end", json={"outcome": "another"}).json()
    assert r["session"]["planned_min"] == 25 and r["session"]["id"] != sid
    assert store.sessions[0]["outcome"] == "another"
    assert store.messages[-1]["content"] == "[▶ Another round, 25 min: Open the form (Taxes)]"
    # Another round from the notification, after the session already went silent: still a new round
    store.advance(60)
    tick()
    tick()
    assert store.sessions[1]["outcome"] == "paused"
    client.post("/actions", json={"token": token(t["id"]), "action": "another"})
    assert store.sessions[-1]["planned_min"] == 25 and store.sessions[-1]["ended_at"] is None


def test_stop_offers_the_breadcrumb_and_the_next_check_in_uses_it(store, monkeypatch, client):
    t = store.add_task("Taxes", next_action="Fill in column F", focus=True)
    sid = client.post(f"/tasks/{t['id']}/start", json={"minutes": 25}).json()["session"]["id"]
    store.advance(12)
    client.post(f"/sessions/{sid}/end", json={"outcome": "stopped"})
    assert store.messages[-1]["content"] == "[Stopped after 12 min: Taxes]"
    focus = client.get("/focus").json()
    assert focus["session"] is None and focus["resume"] == {"session_id": sid, "task_id": t["id"], "title": "Taxes"}

    client.post(f"/sessions/{sid}/note", json={"text": "row 40"})
    assert store.tasks[t["id"]]["resume_note"] == "row 40"
    assert client.get("/focus").json()["resume"] is None

    # shown on the next session screen, and used by the next check-in (LLM context and template)
    session = client.post(f"/tasks/{t['id']}/start", json={"minutes": 5}).json()["session"]
    assert session["resume_note"] == "row 40"
    client.post(f"/sessions/{session['id']}/end", json={"outcome": "stopped"})
    store.tasks[t["id"]]["remind_at"] = store.now + timedelta(minutes=30)
    store.advance(30)
    llm = use_llm(monkeypatch, "Row 40 next: column F?")
    tick()
    assert "Where they stopped last time: row 40" in llm.requests[0]["messages"][1]["content"]
    store.tasks[t["id"]]["remind_at"] = store.now + timedelta(minutes=30)
    store.advance(30)
    use_llm(monkeypatch, LLMUnavailable("down"))
    tick()
    assert "You stopped at: row 40" in store.messages[-1]["content"]          # the template has it too


def test_ignoring_the_breadcrumb_changes_nothing(store, client):
    t = store.add_task("Taxes", focus=True)
    sid = client.post(f"/tasks/{t['id']}/start", json={"minutes": 5}).json()["session"]["id"]
    store.advance(30)
    tick()   # silence → paused
    assert client.get("/focus").json()["resume"]["session_id"] == sid
    store.advance(3 * 60)
    assert client.get("/focus").json()["resume"] is None    # gone by itself
    assert store.tasks[t["id"]]["resume_note"] is None
    client.post(f"/sessions/{sid}/note", json={"text": None})   # ✕ is fine too
    assert store.tasks[t["id"]]["resume_note"] is None


def test_breadcrumb_from_chat_closes_the_field(store, monkeypatch, client):
    t = store.add_task("Taxes", focus=True)
    sid = client.post(f"/tasks/{t['id']}/start", json={"minutes": 5}).json()["session"]["id"]
    client.post(f"/sessions/{sid}/end", json={"outcome": "stopped"})
    llm = use_llm(monkeypatch, {"tool": "update_task", "args": {"id": t["id"], "resume_note": "page 3"}}, "Noted.")
    asyncio.run(agent.handle_user_message("I stopped at page 3"))
    assert store.tasks[t["id"]]["resume_note"] == "page 3"
    assert client.get("/focus").json()["resume"] is None
    assert "Focus sessions" in llm.requests[0]["messages"][0]["content"]


def test_complete_task_from_chat_ends_the_running_session(store, monkeypatch):
    t = store.add_task("Taxes", focus=True)
    actions.start_session(t["id"], 25)
    use_llm(monkeypatch, {"tool": "complete_task", "args": {"id": t["id"]}}, "Nice.")
    store.advance(8)
    asyncio.run(agent.handle_user_message("finished the taxes"))
    assert store.sessions[0]["outcome"] == "done"
    store.advance(30)
    assert tick() == ([], [])   # no end check-in for a finished task


def test_session_length_can_change_and_plus_5(store, client):
    t = store.add_task("Taxes", focus=True)
    sid = client.post(f"/tasks/{t['id']}/start", json={"minutes": 25}).json()["session"]["id"]
    assert client.patch(f"/sessions/{sid}", json={"planned_min": 45}).json()["session"]["planned_min"] == 45
    store.advance(46)
    tick()
    assert store.sessions[0]["end_sent_at"] is not None
    r = client.patch(f"/sessions/{sid}", json={"planned_min": 50})       # +5 after the end check-in
    assert r.json()["session"]["end_sent"] is False
    assert client.patch(f"/sessions/{sid}", json={"planned_min": 7}).status_code == 422
    client.post(f"/sessions/{sid}/end", json={"outcome": "stopped"})
    assert client.patch(f"/sessions/{sid}", json={"planned_min": 30}).status_code == 409


def test_session_end_in_quiet_hours_is_never_sent_and_becomes_paused(store):
    at(store, 22, 50)
    t = store.add_task("Read", focus=True)
    actions.start_session(t["id"], 15)        # ends 23:05, inside quiet hours
    for _ in range(6):
        store.advance(5)
        tick()
    assert not [m for m in store.messages if m["kind"] == "session"] and not store.pushes
    assert store.sessions[0]["outcome"] == "paused"


def test_midday_check_in_is_skipped_when_a_session_ran_in_the_last_hour(store, monkeypatch):
    t = store.add_task("Taxes", focus=True, due_at=local(9, 18))
    at(store, 11, 50)
    actions.start_session(t["id"], 45)   # its start event is the last activity (70 minutes before 13:00)
    at(store, 13)
    for m in store.messages:
        m["created_at"] = local(9, 11, 50).astimezone(store.now.tzinfo)
    llm = use_llm(monkeypatch, "How's taxes?")
    store.sessions[0].update(ended_at=local(9, 12, 35), outcome="paused")
    asyncio.run(scheduler.run_daily_job("checkin"))
    assert llm.requests == []                       # the session ran until 12:35
    at(store, 13, 40)
    asyncio.run(scheduler.run_daily_job("checkin"))
    assert store.messages[-1]["kind"] == "checkin"
    assert [c["id"] for c in store.messages[-1]["chips"]] == ["start_next", "what_now"]
    assert "action" in store.messages[-1]["chips"][1]   # What now? is code now, not a text chip


# ---------------------------------------------------------------- F4: What now?

def test_what_now_answers_without_the_llm_in_under_a_second(store, monkeypatch, client):
    store.add_task("Taxes", next_action="Find last year's PDF", estimate_min=90, focus=True, focus_rank=1)
    short = store.add_task("Reply to Dana", next_action="Write two lines", estimate_min=10, focus=True, focus_rank=2)
    store.add_task("Team sync", kind="meeting", due_at=local(9, 10, 40))
    llm = use_llm(monkeypatch)
    t0 = time.perf_counter()
    r = client.post("/what-now", json={})
    assert time.perf_counter() - t0 < 1 and llm.requests == []
    event, reply = r.json()["messages"]
    assert (event["role"], event["kind"], event["content"]) == ("user", "event", "[What now?]")
    assert reply["content"] == "Do this: Write two lines — Reply to Dana (15 min)."   # Taxes won't fit before 10:40
    assert reply["task_ids"] == [short["id"]] and reply["kind"] == "chat"
    chips = {c["id"]: c for c in reply["chips"]}
    assert chips["start"] == {"id": "start", "label": "▶ Start", "action": "start", "minutes": 15,
                              "task_id": short["id"]}
    assert chips["something_else"]["exclude"] == [short["id"]] and chips["low_energy"]["low_energy"] is True


def test_something_else_and_low_energy(store, client):
    a = store.add_task("Taxes", estimate_min=60, focus=True, focus_rank=1)
    b = store.add_task("Laundry", estimate_min=10)
    r = client.post("/what-now", json={"chip": "something_else", "exclude": [a["id"]]}).json()
    assert r["messages"][0]["content"] == "[Something else]" and r["messages"][1]["task_ids"] == [b["id"]]
    r = client.post("/what-now", json={"chip": "low_energy", "low_energy": True}).json()
    assert r["messages"][1]["content"].startswith("Something small: Laundry")
    assert "low_energy" not in [c["id"] for c in r["messages"][1]["chips"]]


def test_what_now_in_hebrew_and_when_all_is_done(store, client):
    store.m_add("user", "מה המצב")
    t = store.add_task("ארנונה", focus=True)
    actions.apply(t["id"], "done")
    r = client.post("/what-now", json={}).json()
    assert r["messages"][0]["content"] == "[מה עכשיו?]"
    assert r["messages"][1]["content"] == "כל שלוש המשימות של היום בוצעו. אין עוד משהו פתוח."
    assert r["messages"][1]["chips"] == []


def test_what_now_chip_start_opens_a_session(store, client):
    t = store.add_task("Taxes", estimate_min=10, focus=True)
    chip = next(c for c in client.post("/what-now", json={}).json()["messages"][1]["chips"] if c["id"] == "start")
    r = client.post(f"/tasks/{chip['task_id']}/action", json={"action": chip["action"], "minutes": chip["minutes"]})
    assert r.json()["session"]["planned_min"] == 15 and r.json()["session"]["task_id"] == t["id"]


# ---------------------------------------------------------------- F11: wins

def test_focus_card_counts_wins_including_steps_and_sessions(store, client):
    parent = store.add_task("Move house", focus=True)
    step = store.add_task("Book the van", parent_id=parent["id"])
    other = store.add_task("Taxes", focus=True)
    actions.apply(step["id"], "done")
    sid = client.post(f"/tasks/{other['id']}/start", json={"minutes": 25}).json()["session"]["id"]
    store.advance(20)
    focus = client.get("/focus").json()
    assert focus["session"]["id"] == sid and focus["wins"] == {"done": 1, "sessions": 1, "minutes": 20}
    assert focus["doneToday"] == 1


def test_evening_leads_with_wins_even_without_the_llm(store, monkeypatch, client):
    t = store.add_task("Taxes", focus=True)
    store.add_task("Laundry", due_at=local(9, 12))
    client.post(f"/tasks/{t['id']}/start", json={"minutes": 25})
    store.advance(25)
    actions.apply(t["id"], "done")
    at(store, 21, 30)
    llm = use_llm(monkeypatch, LLMUnavailable("down"))
    asyncio.run(scheduler.run_daily_job("evening"))
    assert store.messages[-1]["content"] == "🌙 Done today: 1 · 1 focus session (25 min). 1 open item(s) carry over to tomorrow."
    assert len(llm.requests) == 1


def test_evening_prompt_gets_the_wins(store, monkeypatch, client):
    t = store.add_task("Taxes", focus=True)
    client.post(f"/tasks/{t['id']}/start", json={"minutes": 5})
    store.advance(5)
    actions.apply(t["id"], "done")
    at(store, 21, 30)
    llm = use_llm(monkeypatch, "Good day.")
    asyncio.run(scheduler.run_daily_job("evening"))
    facts = llm.requests[0]["messages"][1]["content"]
    assert "Done today (steps count too):\n- Taxes" in facts and "Focus sessions today: 1 (5 min)" in facts
