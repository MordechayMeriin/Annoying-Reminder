"""
The DynamoDB storage (db, tasks, thread, sessions, preferences, settings, push, job runs) against moto's
in-memory DynamoDB. The flow tests use FakeStore instead; these check the real queries behave the same.
"""

from datetime import date, datetime, timedelta, timezone

import boto3
import pytest
from moto import mock_aws

from app import db, notify, preferences, scheduler, sessions, tasks, thread, user_settings
from app.config import get_settings
from app.logic import ContactState


@pytest.fixture(autouse=True)
def dynamo(monkeypatch):
    with mock_aws():
        db._client = None
        boto3.client("dynamodb", region_name=get_settings().aws_region).create_table(
            **db.table_spec(get_settings().dynamodb_table))
        yield
        db._client = None


def now():
    return datetime.now(timezone.utc)


def today():
    return datetime.now(get_settings().tz).date()


# ---------------------------------------------------------------- db basics

def test_times_round_trip_and_sort():
    a = datetime(2026, 1, 2, 3, 4, 5, 6, tzinfo=timezone.utc)
    assert db.parse_ts(db.ts(a)) == a
    assert db.ts(a) < db.ts(a + timedelta(microseconds=1)) < db.ts(a + timedelta(days=400))


def test_counters_count_up():
    assert [db.next_id("x") for _ in range(3)] == [1, 2, 3]
    db.set_counter_at_least("x", 10)
    db.set_counter_at_least("x", 5)       # never goes back
    assert db.next_id("x") == 11


def test_mutate_retries_after_a_lost_race():
    t = tasks.create("Race")
    calls = []

    def change(row):
        calls.append(1)
        if len(calls) == 1:   # someone else writes between our read and our write
            tasks.update(t["id"], title="Theirs")
        return {**row, "priority": 3}

    out = db.mutate(tasks.TASK, "task", db.pad(t["id"]), change)
    assert len(calls) == 2 and out["priority"] == 3 and tasks.get(t["id"])["title"] == "Theirs"


# ---------------------------------------------------------------- tasks

def test_create_get_update():
    due = now() + timedelta(hours=3)
    t = tasks.create("Call plumber", due_at=due, description="leak", estimate_min=15)
    assert t["id"] == 1 and t["status"] == "open" and t["remind_at"] == due and t["remind_requested"]
    got = tasks.get(1)
    assert got == t
    assert got["nag_count"] == 0 and got["escalated"] is False and got["focus_date"] is None

    u = tasks.update(1, description="big leak", title="Call the plumber", parent_id=99)   # parent_id not updatable
    assert u["title"] == "Call the plumber" and u["parent_id"] is None
    assert tasks.recent_events(1)[0]["kind"] == "description_changed"
    assert tasks.recent_events(1)[0]["note"] == "leak"
    assert tasks.get(999) is None and tasks.update(999, title="x") is None


def test_meeting_reminder_lead_and_new_time_resets_nagging():
    s = get_settings()
    start = now() + timedelta(hours=5)
    m = tasks.create("Standup", kind="meeting", due_at=start)
    assert m["remind_at"] == start - timedelta(minutes=s.meeting_lead_min)
    tasks.finish_contact(m["id"], 3, None, requested=False, escalate=True)
    later = start + timedelta(hours=1)
    u = tasks.update(m["id"], due_at=later)
    assert u["nag_count"] == 0 and u["escalated"] is False and u["remind_requested"]
    assert u["remind_at"] == later - timedelta(minutes=s.meeting_lead_min)


def test_constraints_are_checked():
    with pytest.raises(ValueError):
        tasks.create("x", kind="party")
    with pytest.raises(ValueError):
        tasks.create("x", priority=7)
    t = tasks.create("x")
    with pytest.raises(ValueError):
        tasks.update(t["id"], status="maybe")


def test_subtasks_complete_and_cancel():
    parent = tasks.create("Move house")
    a = tasks.create("Pack books", parent_id=parent["id"])
    b = tasks.create("Book van", parent_id=parent["id"])
    c = tasks.create("Old idea", parent_id=parent["id"])
    tasks.cancel(c["id"])
    assert [s["id"] for s in tasks.subtasks(parent["id"])] == [a["id"], b["id"]]
    assert tasks.subtasks_many([parent["id"], a["id"]]) == {parent["id"]: tasks.subtasks(parent["id"]), a["id"]: []}

    tasks.complete(a["id"])
    done = tasks.complete(parent["id"])
    assert done["status"] == "done" and done["completed_at"] and done["remind_at"] is None
    assert tasks.get(b["id"])["status"] == "done"            # open steps close with it
    assert tasks.get(c["id"])["status"] == "cancelled"
    assert tasks.complete(parent["id"])["status"] == "done"  # a second tap changes nothing

    other = tasks.create("Plan party")
    step = tasks.create("Invite", parent_id=other["id"])
    tasks.cancel(other["id"])
    assert tasks.get(step["id"])["status"] == "cancelled"


def test_recurring_task_rolls_forward():
    due = now() - timedelta(days=3, hours=1)
    t = tasks.create("Water plants", due_at=due, remind_at=due - timedelta(minutes=10), recurrence="daily")
    tasks.finish_contact(t["id"], 2, due - timedelta(minutes=10), requested=False, escalate=False)
    out = tasks.complete(t["id"])
    assert out["status"] == "open" and out["due_at"] > now() and out["remind_at"] == out["due_at"] - timedelta(minutes=10)
    assert out["nag_count"] == 0 and out["remind_requested"]
    assert [e["kind"] for e in tasks.recent_events(t["id"])] == ["done"]


def test_due_for_contact_and_lease():
    past = now() - timedelta(minutes=5)
    quiet = tasks.create("Someday")                                       # undated, not in focus
    tasks.set_followup(quiet["id"], past, requested=False)
    asked = tasks.create("Reminder", remind_at=past)                      # the user asked
    meeting = tasks.create("Meeting", kind="meeting", due_at=now() + timedelta(minutes=5))
    near = tasks.create("Report", due_at=now() + timedelta(hours=10))
    tasks.set_followup(near["id"], past, requested=False)
    later = tasks.create("Later", remind_at=now() + timedelta(hours=1))
    parked = tasks.create("Parked", remind_at=past)
    tasks.park(parked["id"], now() + timedelta(days=2))

    ids = [t["id"] for t in tasks.due_for_contact(today())]
    assert set(ids) == {asked["id"], meeting["id"], near["id"]}
    assert later["id"] not in ids and quiet["id"] not in ids
    assert {t["id"] for t in tasks.due_for_contact(today(), lapsed=True)} == {asked["id"], meeting["id"]}

    assert tasks.lease(ids) == ids
    assert tasks.lease(ids) == []                                         # an overlapping tick gets nothing
    assert tasks.due_for_contact(today()) == []


def test_defer_only_moves_a_due_task():
    t = tasks.create("x", remind_at=now() - timedelta(minutes=1))
    until = now() + timedelta(hours=1)
    tasks.defer(t["id"], until)
    assert tasks.get(t["id"])["remind_at"] == until
    tasks.defer(t["id"], until + timedelta(hours=5))                      # not due any more: unchanged
    assert tasks.get(t["id"])["remind_at"] == until


def test_block_unblock_snooze_park_avoid():
    t = tasks.create("Taxes")
    until = now() + timedelta(days=1)
    b = tasks.block(t["id"], "waiting for form", until)
    assert b["blocked_reason"] == "waiting for form" and b["remind_at"] == until
    assert [x["id"] for x in tasks.list_tasks("blocked")] == [t["id"]]
    tasks.unblock(t["id"])
    s = tasks.snooze(t["id"], 30)
    assert s["remind_requested"] and s["remind_at"] > now() + timedelta(minutes=29)

    tasks.avoid_asked(t["id"], now() + timedelta(hours=2))
    tasks.avoid_asked(t["id"], now() + timedelta(hours=2))
    assert tasks.get(t["id"])["avoid_asks"] == 2
    tasks.reset_avoidance(t["id"])
    r = tasks.get(t["id"])
    assert r["avoid_asks"] == 0 and r["avoid_reset_at"] is not None
    kinds = [e["kind"] for e in tasks.recent_events(t["id"], 10)]
    assert kinds == ["postponed", "unblocked", "blocked"]

    tasks.cancel(t["id"])
    assert tasks.park(t["id"], until) is None and tasks.set_followup(t["id"], until, True) is None


def test_event_counts_start_after_a_fresh_start():
    t = tasks.create("Gym")
    tasks.add_event(t["id"], "postponed")
    tasks.add_event(t["id"], "ignored")
    assert tasks.event_counts(t["id"]) == {"postponed": 1, "ignored": 1}
    tasks.reset_avoidance(t["id"])
    tasks.add_event(t["id"], "postponed")
    assert tasks.event_counts_many([t["id"]]) == {t["id"]: {"postponed": 1}}
    with pytest.raises(ValueError):
        tasks.add_event(t["id"], "danced")


def test_focus_today_and_done_since():
    d = today()
    a, b, c, m = (tasks.create("A"), tasks.create("B"), tasks.create("C"),
                  tasks.create("Meet", kind="meeting", due_at=now() + timedelta(hours=1)))
    out = tasks.set_focus([b["id"], a["id"], b["id"], m["id"]], d)
    assert [t["id"] for t in out] == [b["id"], a["id"]] and [t["focus_rank"] for t in out] == [1, 2]
    tasks.set_focus([c["id"], a["id"]], d)                     # B drops out
    assert tasks.get(b["id"])["focus_date"] is None
    tasks.complete(a["id"])
    rows, done = tasks.focus_today(d)
    assert [t["id"] for t in rows] == [c["id"], a["id"]] and done == {a["id"]}

    tasks.park(c["id"], now() + timedelta(days=1))              # parking takes it out of Today's 3
    assert [t["id"] for t in tasks.focus_today(d)[0]] == [a["id"]]

    start = now() - timedelta(minutes=1)
    tasks.complete(b["id"])
    assert [r["id"] for r in tasks.done_since(start)] == [a["id"], b["id"]]
    assert [t["id"] for t in tasks.list_tasks("done_today")] == [a["id"], b["id"]]


def test_list_scopes_and_meetings():
    overdue = tasks.create("Overdue", due_at=now() - timedelta(hours=1))
    soon = tasks.create("Next week", due_at=now() + timedelta(days=3))
    undated = tasks.create("Undated", priority=3)
    rec = tasks.create("Weekly sync", kind="meeting", due_at=now() - timedelta(days=7), recurrence="weekly")
    one = tasks.create("Dentist", kind="meeting", due_at=now() + timedelta(days=1))
    assert [t["id"] for t in tasks.list_tasks("overdue")] == [rec["id"], overdue["id"]]
    assert soon["id"] in [t["id"] for t in tasks.list_tasks("upcoming")]
    assert tasks.list_tasks("open")[-1]["id"] == undated["id"]          # undated last
    ids = [t["id"] for t in tasks.meetings_between(now(), now() + timedelta(days=2))]
    assert ids == [rec["id"], one["id"]]


def test_close_finished_meetings_and_fences():
    old = tasks.create("Old meeting", kind="meeting", due_at=now() - timedelta(hours=3))
    tasks.close_finished_meetings()
    assert tasks.get(old["id"])["status"] == "done"
    fence = tasks.create("Leave", kind="fence", due_at=now() - timedelta(minutes=1))
    assert tasks.fence_fired(fence["id"])["status"] == "done"
    daily = tasks.create("Pickup", kind="fence", due_at=now() - timedelta(minutes=1), recurrence="daily")
    rolled = tasks.fence_fired(daily["id"])
    assert rolled["status"] == "open" and rolled["due_at"] > now()


def test_stale_tasks():
    t = tasks.create("Ancient")
    db.mutate(tasks.TASK, "task", db.pad(t["id"]), lambda r: {**r, "created_at": now() - timedelta(days=30)})
    moving = tasks.create("Ancient but moving")
    db.mutate(tasks.TASK, "task", db.pad(moving["id"]), lambda r: {**r, "created_at": now() - timedelta(days=30)})
    tasks.add_event(moving["id"], "progress")
    tasks.create("New")
    assert [x["id"] for x in tasks.stale_tasks()] == [t["id"]]
    tasks.mark_stale_offered([t["id"]])
    assert tasks.stale_tasks() == []


# ---------------------------------------------------------------- thread

def test_messages_since_page_and_links():
    t1, t2 = tasks.create("One"), tasks.create("Two")
    start = now()
    m1 = thread.add("assistant", "Morning!", "morning", task_ids=[t1["id"]], chips=[{"label": "✓", "action": "done"}])
    m2 = thread.add("user", "ok", "chat")
    m3 = thread.add_event("[Marked done: One]", [t1["id"], t2["id"], t1["id"]])
    assert m3["task_ids"] == [t1["id"], t2["id"]] and m3["task_id"] == t1["id"]
    assert [m["id"] for m in thread.since(start)] == [m1["id"], m2["id"], m3["id"]]
    assert [m["id"] for m in thread.since(start, limit=2)] == [m2["id"], m3["id"]]
    assert thread.since(start)[0]["chips"] == [{"label": "✓", "action": "done"}]

    page = thread.page()
    assert [m["id"] for m in page] == [1, 2, 3]
    assert page[0]["task_title"] == "One" and page[2]["task_titles"] == {str(t1["id"]): "One", str(t2["id"]): "Two"}
    assert [m["id"] for m in thread.page(before_id=3, limit=1)] == [2]
    assert [m["id"] for m in thread.page(after_id=1)] == [2, 3]
    assert [m["id"] for m in thread.page(before_id=3, after_id=1)] == [2]
    assert [m["id"] for m in thread.by_ids([3, 1, 77])] == [1, 3]

    assert [m["id"] for m in thread.for_tasks([t2["id"]])] == [m3["id"]]
    thread.link([m2["id"]], [t2["id"]])
    thread.link([m3["id"]], [t1["id"]])                          # t2's link from m3 goes away
    assert [m["id"] for m in thread.for_tasks([t2["id"]])] == [m2["id"]]
    assert [m["id"] for m in thread.for_tasks([t1["id"], t2["id"]], limit=2)] == [m2["id"], m3["id"]]
    assert thread.recent_task_ids(start) == [t1["id"], t2["id"]]


def test_the_guardrail_counts():
    t = tasks.create("x")
    before = now()
    assert thread.last_proactive() is None and thread.last_user_reply_at() is None
    n1 = thread.add("assistant", "nudge 1", "nudge", task_ids=[t["id"]])
    thread.add("assistant", "hi", "chat")
    n2 = thread.add("assistant", "nudge 2", "nudge", task_ids=[t["id"]])
    assert thread.last_proactive()["id"] == n2["id"]
    assert not thread.user_replied_since(before)
    reply = thread.add("user", "שלום", "chat")
    assert thread.user_replied_since(before) and not thread.user_replied_since(reply["created_at"])
    assert thread.last_user_reply_at() == reply["created_at"] and thread.last_user_text() == "שלום"
    assert thread.last_kind_at("nudge") == n2["created_at"] and thread.last_kind_at("reentry") is None
    state = thread.contact_state(before)
    assert state == ContactState(last_proactive_at=n2["created_at"], sent_today=2, sent_today_by_task={t["id"]: 2})
    assert n1["id"] < n2["id"]
    # A new day: nothing sent yet today, but the last proactive time still counts.
    tomorrow = thread.contact_state(now() + timedelta(seconds=1))
    assert tomorrow == ContactState(last_proactive_at=n2["created_at"], sent_today=0, sent_today_by_task={})


def test_message_kinds_are_checked():
    with pytest.raises(ValueError):
        thread.add("system", "x")


# ---------------------------------------------------------------- sessions

def test_session_lifecycle():
    t = tasks.create("Write")
    s1 = sessions.start(t["id"], 25)
    assert sessions.open_sessions() == [s1] and sessions.latest() == s1 and sessions.latest_for_task(t["id"]) == s1
    assert sessions.claim_end(s1["id"]) and not sessions.claim_end(s1["id"])
    assert sessions.set_planned(s1["id"], 30)["end_sent_at"] is None
    ended = sessions.end(s1["id"], "done")
    assert ended["outcome"] == "done" and sessions.end(s1["id"], "stopped") is None
    assert sessions.open_sessions() == []
    s2 = sessions.start(t["id"], 5)
    assert sessions.latest()["id"] == s2["id"]
    sessions.close_notes_for_task(t["id"])
    assert sessions.get(s1["id"])["note_closed_at"] and sessions.get(s2["id"])["note_closed_at"]
    n, minutes = sessions.stats_since(now() - timedelta(hours=1), now())
    assert n == 2
    with pytest.raises(ValueError):
        sessions.start(t["id"], 7)


# ---------------------------------------------------------------- small things

def test_preferences():
    preferences.load()
    p = preferences.add("Shorter   messages please")
    assert p["text"] == "Shorter messages please" and preferences.items() == [p]
    assert preferences.add("shorter messages please") == p           # already known
    assert preferences.delete(p["id"]) and not preferences.delete(p["id"])
    assert preferences.items() == []


def test_settings_save_all_or_nothing():
    assert user_settings.save({"evening_time": "22:15", "max_nags": "3"}) == {}
    assert user_settings.stored() == {"evening_time": "22:15", "max_nags": "3"}
    assert user_settings.save({"evening_time": None}) == {}
    assert user_settings.stored() == {"max_nags": "3"}
    assert user_settings.save({"evening_time": "21:00", "max_nags": "lots"})
    assert user_settings.stored() == {"max_nags": "3"}
    user_settings.save({"max_nags": None})


def test_push_subscriptions():
    notify.subscribe("https://push.example/1", "k", "a")
    notify.subscribe("https://push.example/1", "k2", "a2")           # same browser: replaced
    notify.subscribe("https://push.example/2", "k", "a")
    subs = notify.subscriptions()
    assert [(s["endpoint"], s["p256dh"]) for s in subs] == [("https://push.example/1", "k2"), ("https://push.example/2", "k")]


def test_daily_jobs_run_once():
    assert scheduler.claim_job("morning", date(2026, 11, 16))
    assert not scheduler.claim_job("morning", date(2026, 11, 16))
    assert scheduler.claim_job("morning", date(2026, 11, 17)) and scheduler.claim_job("evening", date(2026, 11, 16))


# ---------------------------------------------------------------- the one-off import from Postgres

def test_import_from_postgres_rows():
    import importlib.util
    from pathlib import Path
    spec = importlib.util.spec_from_file_location(
        "migrate", Path(__file__).resolve().parent.parent / "scripts" / "migrate_from_postgres.py")
    migrate = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migrate)

    t0 = now() - timedelta(hours=2)
    d = today()
    base = {"description": None, "next_action": None, "estimate_min": None, "focus_date": None, "focus_rank": None,
            "resume_note": None, "parked_until": None, "stale_offered_at": None, "avoid_asked_at": None,
            "avoid_asks": 0, "avoid_reset_at": None, "kind": "task", "priority": 1, "status": "open",
            "due_at": None, "remind_at": None, "remind_requested": False, "recurrence": None, "nag_count": 0,
            "escalated": False, "blocked_reason": None, "blocked_until": None, "last_notified_at": None,
            "created_at": t0, "completed_at": None, "parent_id": None}
    data = {
        "tasks": [{**base, "id": 5, "title": "Parent", "focus_date": d, "focus_rank": 1},
                  {**base, "id": 9, "title": "Step", "parent_id": 5, "status": "done", "completed_at": t0}],
        "task_events": [{"id": 1, "task_id": 9, "kind": "done", "note": None, "avoid_reason": None, "created_at": t0}],
        "messages": [{"id": 40, "role": "assistant", "content": "Hi", "kind": "morning", "task_id": 5,
                      "task_ids": [5, 9], "chips": [{"label": "ok"}], "model": "m", "created_at": t0}],
        "sessions": [{"id": 3, "task_id": 5, "started_at": t0, "planned_min": 25, "ended_at": None, "outcome": None,
                      "end_sent_at": None, "note_closed_at": None, "user_id": None}],
        "preferences": [{"id": 2, "text": "Be brief", "created_at": t0, "user_id": None}],
        "app_settings": [{"key": "max_nags", "value": "3", "updated_at": t0}],
        "push_subscriptions": [{"id": 1, "endpoint": "https://push.example/a", "p256dh": "k", "auth": "a",
                                "created_at": t0}],
        "job_runs": [{"job": "morning", "run_date": d, "ran_at": t0}],
    }
    items, counters = migrate.build_items(data)
    db.write_batch(items)
    for name, value in counters.items():
        db.set_counter_at_least(name, value)

    assert tasks.get(5)["title"] == "Parent" and [s["id"] for s in tasks.subtasks(5)] == [9]
    assert [t["id"] for t in tasks.focus_today(d)[0]] == [5]
    assert [r["id"] for r in tasks.done_since(t0 - timedelta(seconds=1))] == [9]
    assert thread.page()[0]["chips"] == [{"label": "ok"}] and [m["id"] for m in thread.for_tasks([9])] == [40]
    assert sessions.open_sessions()[0]["id"] == 3
    preferences.load()
    assert preferences.items()[0]["text"] == "Be brief"
    assert user_settings.stored() == {"max_nags": "3"} and len(notify.subscriptions()) == 1
    assert not scheduler.claim_job("morning", d)
    assert tasks.create("New")["id"] == 10 and thread.add("user", "x")["id"] == 41   # ids continue
    assert migrate.table_has_data()
