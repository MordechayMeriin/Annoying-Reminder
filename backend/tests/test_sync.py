"""The phone's backup (sync.py): restore, upload and the handover, on moto's DynamoDB.

The rows are the Android tests' sample (android/app/src/test/resources/restore-sample.json): what the phone decodes
and encodes, so both sides are checked against the same data.
"""

import copy
import json
from pathlib import Path

import boto3
import pytest
from fastapi.testclient import TestClient
from moto import mock_aws

from app import db, sync
from app.auth import require_owner
from app.config import get_settings

SAMPLE = json.loads((Path(__file__).resolve().parents[2] / "android/app/src/test/resources/restore-sample.json").read_text())


def _new_table():
    db._client = None
    aws = boto3.client("dynamodb", region_name=get_settings().aws_region)
    name = get_settings().dynamodb_table
    if name in aws.list_tables()["TableNames"]:
        aws.delete_table(TableName=name)
    aws.create_table(**db.table_spec(name))


@pytest.fixture
def client():
    from app.lambda_app import app
    with mock_aws():
        _new_table()
        app.dependency_overrides[require_owner] = lambda: "google:1"
        with TestClient(app) as c:
            yield c
        app.dependency_overrides.clear()
        db._client = None


def ok(r):
    assert r.status_code == 200, r.text
    return r.json()


def tables():
    return copy.deepcopy(SAMPLE["tables"])


def test_restore_hands_the_phone_everything(client):
    ok(client.post("/sync/upload", json={"device": "Galaxy S9", "tables": tables()}))
    out = ok(client.post("/sync/restore", json={"device": "Galaxy S9"}))
    t = out["tables"]
    assert [r["title"] for r in t["tasks"]] == ["Write report", "Outline", "Standup"]
    assert t["tasks"][0]["due_at"].endswith("Z") and len(t["tasks"][0]["due_at"]) == 27   # stored format, microseconds
    assert t["tasks"][1]["parent_id"] == 1
    assert [e["kind"] for e in t["events"]] == ["postponed"] and t["events"][0]["rid"]
    assert t["messages"][1]["task_ids"] == [1, 2] and json.loads(t["messages"][1]["chips"])[0]["action"] == "start"
    assert t["focus"] == SAMPLE["tables"]["focus"] and t["settings"] == SAMPLE["tables"]["settings"]
    assert out["counters"] == SAMPLE["counters"]
    assert out["status"]["lastRestoreAt"] and out["status"]["handedOverAt"] is None


def test_what_comes_back_restores_byte_for_byte(client):
    """Upload, restore into a new backup, upload what came back: the same rows and counters."""
    ok(client.post("/sync/upload", json={"tables": tables()}))
    first = ok(client.post("/sync/restore", json={}))
    _new_table()                                              # a new, empty backup
    ok(client.post("/sync/upload", json={"tables": first["tables"]}))
    second = ok(client.post("/sync/restore", json={}))
    assert second["tables"] == first["tables"] == SAMPLE["tables"]
    assert second["counters"] == first["counters"]


def test_the_android_tests_sample_has_every_stored_field(client):
    """A field added on one side only shows up here: add it to the sample (and the phone's Rows.kt) too."""
    for name, entity in sync.ENTITIES.items():
        assert set(SAMPLE["tables"][name][0]) == set(entity.fields), name


def test_restore_needs_no_scan_permission(client, monkeypatch):
    """The function's role may Query but not Scan (scripts/aws_lambda_setup.py's policy)."""
    ok(client.post("/sync/upload", json={"tables": tables()}))
    real = db.client()

    class NoScan:
        def __getattr__(self, name):
            if name == "scan":
                raise AssertionError("restore must not Scan: the function's role isn't allowed to")
            return getattr(real, name)
    monkeypatch.setattr(db, "client", lambda: NoScan())
    out = ok(client.post("/sync/restore", json={"handover": True}))
    assert len(out["tables"]["tasks"]) == 3 and len(out["tables"]["events"]) == 1


def test_an_upload_keeps_the_links_counters_and_deletes(client):
    ok(client.post("/sync/upload", json={"tables": tables()}))
    created = "2026-11-18T09:00:00.000000Z"
    step = {"id": 10, "parent_id": 1, "title": "Send it", "status": "open", "created_at": created, "priority": 2}
    ok(client.post("/sync/upload", json={
        "device": "Galaxy S9",
        "tables": {
            "tasks": [step],
            "messages": [{"id": 5, "role": "assistant", "content": "Done ✓", "kind": "event", "task_ids": [10, 10],
                          "chips": "[]", "created_at": created}],
        },
        "deletes": {"preferences": [1], "settings": ["morning_time"]},
    }))
    assert set(map(int, db.get_raw("children", db.pad(1))["ids"])) == {2, 10}   # the parent's steps
    assert [it["sk"] for it in db.query("mtask#10")] == [db.pad(5)]              # the message's task link, once
    st = ok(client.get("/sync/status"))
    assert st["lastUploadAt"] and st["device"] == "Galaxy S9"
    out = ok(client.post("/sync/restore", json={}))
    t = out["tables"]
    assert t["tasks"][-1]["title"] == "Send it" and t["tasks"][-1]["priority"] == 2
    assert t["messages"][-1]["task_ids"] == [10] and t["messages"][-1]["task_id"] == 10
    assert t["preferences"] == [] and t["settings"] == []
    assert out["counters"]["task"] == 10 and out["counters"]["message"] == 5   # past the phone's highest ids


@pytest.mark.parametrize("bad, deletes, why", [
    ({"tasks": [{"id": 1, "title": "x", "status": "open", "created_at": "yesterday"}]}, {}, "created_at"),
    ({"tasks": [{"id": 1, "title": "x", "status": "open", "created_at": "2026-11-17T09:00:00.000000Z",
                 "colour": "red"}]}, {}, "unknown fields"),
    ({"tasks": [{"id": 1, "title": "x"}]}, {}, "missing"),
    ({"widgets": []}, {}, "Unknown tables"),
    ({"focus": [{"ids": [1]}]}, {}, "focus"),
    ({"settings": [{"key": "quiet_start"}]}, {}, "settings"),
    ({}, {"events": [[1]]}, "can't delete"),
])
def test_a_bad_row_writes_nothing(client, bad, deletes, why):
    good = {"id": 2, "title": "fine", "status": "open", "created_at": "2026-11-17T09:00:00.000000Z"}
    body = {"tables": {**bad, "tasks": [good, *bad.get("tasks", [])]}, "deletes": deletes}
    r = client.post("/sync/upload", json=body)
    assert r.status_code == 422 and why in r.json()["detail"]
    assert db.get_raw("task", db.pad(2)) is None


def test_too_many_rows_in_one_upload(client):
    rows = [{"id": i, "title": "x", "status": "open", "created_at": "2026-11-17T09:00:00.000000Z"}
            for i in range(1, sync.MAX_ROWS + 2)]
    assert client.post("/sync/upload", json={"tables": {"tasks": rows}}).status_code == 422


def test_the_handover_is_recorded_once(client):
    ok(client.post("/sync/upload", json={"tables": tables()}))
    assert ok(client.post("/sync/restore", json={}))["status"]["handedOverAt"] is None   # a look, not a handover
    out = ok(client.post("/sync/restore", json={"handover": True, "device": "Galaxy S9"}))
    handed = out["status"]["handedOverAt"]
    assert handed and sync.handed_over()
    again = ok(client.post("/sync/restore", json={"handover": True}))   # a reinstall: restore works, same handover
    assert again["status"]["handedOverAt"] == handed and len(again["tables"]["tasks"]) == 3
