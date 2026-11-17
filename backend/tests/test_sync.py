"""The phone's backup (sync.py): restore, upload and the handover, on moto's DynamoDB."""

import json
from pathlib import Path
from datetime import date, datetime, timedelta, timezone

import boto3
import pytest
from fastapi.testclient import TestClient
from moto import mock_aws

from app import db, notify, preferences, sessions, sync, tasks, thread, user_settings
from app.auth import require_cron, require_owner
from app.config import get_settings
from app.main import app


def _new_table():
    db._client = None
    aws = boto3.client("dynamodb", region_name=get_settings().aws_region)
    name = get_settings().dynamodb_table
    if name in aws.list_tables()["TableNames"]:
        aws.delete_table(TableName=name)
    aws.create_table(**db.table_spec(name))


@pytest.fixture
def client(monkeypatch):
    with mock_aws():
        _new_table()
        sync._forget_cache()
        monkeypatch.setattr(notify, "send_push", lambda *a, **kw: 0)
        monkeypatch.setattr(notify, "send_pushover", lambda *a, **kw: False)
        app.dependency_overrides[require_owner] = lambda: get_settings().owner_user_id
        app.dependency_overrides[require_cron] = lambda: None
        with TestClient(app) as c:
            yield c
        app.dependency_overrides.clear()
        db._client = None


def ok(r):
    assert r.status_code == 200, r.text
    return r.json()


def _some_data():
    now = datetime.now(timezone.utc)
    parent = tasks.create("Write report", due_at=now + timedelta(hours=5), next_action="Open the doc", estimate_min=25,
                          description="For Dana")
    step = tasks.create("Outline", parent_id=parent["id"])
    tasks.create("Gym", due_at=now + timedelta(days=1), recurrence="weekly")
    tasks.add_event(parent["id"], "postponed", "snoozed 60 min")
    tasks.set_focus([parent["id"]], date(2026, 11, 17))
    thread.add("user", "remind me to write the report", task_ids=[parent["id"]])
    thread.add("assistant", "Morning plan", "morning", task_ids=[parent["id"], step["id"]],
               chips=[{"label": "▶ Start", "action": "start", "task_id": parent["id"]}], model="test/model")
    s = sessions.start(parent["id"], 25)
    sessions.end(s["id"], "stopped")
    preferences.add("Shorter messages")
    user_settings.save({"morning_time": "08:00"})
    return parent, step


def test_restore_hands_the_phone_everything(client):
    parent, step = _some_data()
    out = ok(client.post("/sync/restore", json={"device": "Galaxy S9"}))
    t = out["tables"]
    assert [r["title"] for r in t["tasks"]] == ["Write report", "Outline", "Gym"]
    restored = t["tasks"][0]
    assert restored["due_at"].endswith("Z") and len(restored["due_at"]) == 27        # stored format, microseconds
    assert restored["description"] == "For Dana" and restored["focus_date"] == "2026-11-17"
    assert t["tasks"][1]["parent_id"] == parent["id"]
    assert [e["kind"] for e in t["events"]] == ["postponed"] and t["events"][0]["rid"]
    msgs = t["messages"]
    assert [m["content"] for m in msgs] == ["remind me to write the report", "Morning plan"]
    assert msgs[1]["task_ids"] == [parent["id"], step["id"]] and json.loads(msgs[1]["chips"])[0]["action"] == "start"
    assert len(t["sessions"]) == 1 and t["sessions"][0]["planned_min"] == 25
    assert [p["text"] for p in t["preferences"]] == ["Shorter messages"]
    assert {s["key"]: s["value"] for s in t["settings"]} == {"morning_time": "08:00"}
    assert t["focus"] == [{"date": "2026-11-17", "ids": [parent["id"]]}]
    assert out["counters"] == {"task": 3, "message": 2, "session": 1, "preference": 1}
    assert out["status"]["lastRestoreAt"] and out["status"]["handedOverAt"] is None


def test_what_comes_back_restores_byte_for_byte(client):
    _some_data()
    first = ok(client.post("/sync/restore", json={}))
    _new_table()                                              # a new, empty backup
    tables = first["tables"]
    ok(client.post("/sync/upload", json={"tables": tables}))
    second = ok(client.post("/sync/restore", json={}))
    assert second["tables"] == tables
    assert second["counters"] == first["counters"]


def test_an_upload_is_what_the_old_server_reads(client):
    parent, _ = _some_data()
    created = "2026-11-17T09:00:00.000000Z"
    new_task = {"id": 10, "parent_id": parent["id"], "title": "Send it", "status": "open", "created_at": created,
                "kind": "task", "priority": 2, "remind_at": "2026-11-18T06:00:00.000000Z", "remind_requested": True}
    ok(client.post("/sync/upload", json={
        "device": "Galaxy S9",
        "tables": {
            "tasks": [new_task, {**tasks.TASK.row({}), **_plain(tasks.get(parent["id"])), "title": "Write the report"}],
            "events": [{"task_id": 10, "kind": "done", "created_at": created, "rid": "p0000001"}],
            "messages": [{"id": 5, "role": "assistant", "content": "Done ✓", "kind": "event", "task_ids": [10],
                          "chips": "[]", "created_at": created}],
            "focus": [{"date": "2026-11-18", "ids": [10]}],
            "settings": [{"key": "evening_time", "value": "22:00", "updated_at": created}],
        },
        "deletes": {"preferences": [1], "settings": ["morning_time"]},
    }))
    assert tasks.get(10)["title"] == "Send it" and tasks.get(10)["priority"] == 2
    assert tasks.get(parent["id"])["title"] == "Write the report"
    assert {s["title"] for s in tasks.subtasks(parent["id"])} == {"Outline", "Send it"}   # the parent's step list
    assert [e["kind"] for e in tasks.recent_events(10)] == ["done"]
    assert [m["content"] for m in thread.for_tasks([10])] == ["Done ✓"]
    assert any(True for _ in db.query("mtask#10"))
    assert tasks._focus_ids(date(2026, 11, 18)) == [10]
    assert user_settings.stored() == {"evening_time": "22:00"}
    assert db.query_rows(preferences.PREFERENCE, "pref") == []
    assert db.next_id("task") == 11 and db.next_id("message") == 6     # the server's counters moved past the phone's
    st = ok(client.get("/sync/status"))
    assert st["lastUploadAt"] and st["device"] == "Galaxy S9"


def _plain(row):
    return {k: (None if v is None else db._encode_value(tasks.TASK.fields[k], v)) for k, v in row.items()}


@pytest.mark.parametrize("tables, deletes, why", [
    ({"tasks": [{"id": 1, "title": "x", "status": "open", "created_at": "yesterday"}]}, {}, "created_at"),
    ({"tasks": [{"id": 1, "title": "x", "status": "open", "created_at": "2026-11-17T09:00:00.000000Z",
                 "colour": "red"}]}, {}, "unknown fields"),
    ({"tasks": [{"id": 1, "title": "x"}]}, {}, "missing"),
    ({"widgets": []}, {}, "Unknown tables"),
    ({"focus": [{"ids": [1]}]}, {}, "focus"),
    ({}, {"events": [[1]]}, "can't delete"),
])
def test_a_bad_row_writes_nothing(client, tables, deletes, why):
    good = {"id": 2, "title": "fine", "status": "open", "created_at": "2026-11-17T09:00:00.000000Z"}
    body = {"tables": {**tables, "tasks": [good, *tables.get("tasks", [])]}, "deletes": deletes}
    r = client.post("/sync/upload", json=body)
    assert r.status_code == 422 and why in r.json()["detail"]
    assert tasks.get(2) is None


def test_the_handover_switches_the_web_app_off(client):
    _some_data()
    assert ok(client.get("/tasks"))                                    # before: the web app works
    out = ok(client.post("/sync/restore", json={"handover": True, "device": "Galaxy S9"}))
    handed = out["status"]["handedOverAt"]
    assert handed

    r = client.get("/tasks")
    assert r.status_code == 409 and "Android app" in r.json()["detail"]
    assert client.post("/chat", json={"text": "hi"}).status_code == 409
    assert client.post("/actions", json={"token": "x", "action": "done"}).status_code == 409
    assert ok(client.post("/tick")) == {"handedOver": True, "contacting": [], "sessionEnds": [], "jobs": []}
    assert ok(client.get("/health")) == {"ok": True}
    ok(client.post("/sync/upload", json={"tables": {}}))              # the phone's backup still works
    again = ok(client.post("/sync/restore", json={"handover": True}))  # a reinstall: restore works, same handover
    assert again["status"]["handedOverAt"] == handed and len(again["tables"]["tasks"]) == 3


def test_the_android_tests_sample_still_fits(client):
    """The phone's tests decode this sample; it must still be what the server stores and accepts."""
    sample = json.loads((Path(__file__).resolve().parents[2] / "android/app/src/test/resources/restore-sample.json").read_text())
    ok(client.post("/sync/upload", json={"tables": sample["tables"]}))
    back = ok(client.post("/sync/restore", json={}))["tables"]
    assert back == sample["tables"], "Run scripts/make_android_sync_sample.py again"
    for name, entity in sync.ENTITIES.items():   # every stored field is in the sample, so a new one shows up here
        assert set(sample["tables"][name][0]) == set(entity.fields), name


def test_a_restore_without_handover_leaves_the_web_app_on(client):
    ok(client.post("/sync/restore", json={}))
    assert ok(client.get("/tasks")) is not None
    assert "handedOver" not in ok(client.post("/tick"))
