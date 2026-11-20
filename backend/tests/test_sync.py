"""The phone's backup (sync.py): restore, upload and the handover, on moto's DynamoDB.

The rows are the Android tests' sample (android/app/src/test/resources/restore-sample.json): what the phone decodes
and encodes, so both sides are checked against the same data.
"""

import copy
import json
import time
from pathlib import Path

import boto3
import pytest
from fastapi.testclient import TestClient
from moto import mock_aws

from app import db, sync
from app.auth import require_owner
from app.config import get_settings

SAMPLE = json.loads((Path(__file__).resolve().parents[2] / "android/app/src/test/resources/restore-sample.json").read_text())


def _new_table(spec=None):
    db._client = None
    aws = boto3.client("dynamodb", region_name=get_settings().aws_region)
    name = get_settings().dynamodb_table
    if name in aws.list_tables()["TableNames"]:
        aws.delete_table(TableName=name)
    aws.create_table(**(spec or db.table_spec)(name))


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
    page = ok(client.post("/sync/restore", json={"paged": True}))
    assert len(page["tables"]["tasks"]) == 3 and page["next"] is None


def test_an_upload_keeps_counters_and_deletes(client):
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
    st = ok(client.get("/sync/status"))
    assert st["lastUploadAt"] and st["device"] == "Galaxy S9"
    out = ok(client.post("/sync/restore", json={}))
    t = out["tables"]
    assert t["tasks"][-1]["title"] == "Send it" and t["tasks"][-1]["priority"] == 2
    assert t["messages"][-1]["task_ids"] == [10] and t["messages"][-1]["task_id"] == 10
    assert t["preferences"] == [] and t["settings"] == []
    assert out["counters"]["task"] == 10 and out["counters"]["message"] == 5   # past the phone's highest ids


def test_only_the_rows_themselves_are_stored(client):
    """Layout 2: no index keys, no step lists, no message links; events in one partition by task and time."""
    ok(client.post("/sync/upload", json={"tables": tables()}))
    for pk in ("task", "msg", "sess", "event"):
        for item in db.query(pk):
            assert not {"g1pk", "g1sk", "g2pk", "g2sk"} & set(item), item
    assert list(db.query("children")) == [] and list(db.query("mtask#1")) == []
    event = SAMPLE["tables"]["events"][0]
    assert [it["sk"] for it in db.query("event")] == [f"{db.pad(event['task_id'])}#{event['created_at']}#{event['rid']}"]


def test_a_setting_set_reset_and_set_again_is_stored(client):
    """The phone once sent a setting both as a row and as a delete: DynamoDB refused the whole batch, for good."""
    ok(client.post("/sync/upload", json={"tables": tables()}))
    row = {"key": "morning_time", "value": "07:30", "updated_at": "2026-11-18T09:00:00.000000Z"}
    out = ok(client.post("/sync/upload", json={"tables": {"settings": [row]}, "deletes": {"settings": ["morning_time"]}}))
    assert out["written"] == 1 and out["deleted"] == 0
    assert db.get_raw("setting", "morning_time")["value"] == "07:30"


def test_a_put_and_a_delete_of_one_key_in_different_groups_of_25(client):
    """The write wins even when the two would land in different BatchWriteItem requests."""
    rows = [{"key": f"k{i:02d}", "value": "x", "updated_at": "2026-11-18T09:00:00.000000Z"} for i in range(40)]
    ok(client.post("/sync/upload", json={"tables": {"settings": rows}, "deletes": {"settings": ["k39", "gone"]}}))
    assert db.get_raw("setting", "k39")["value"] == "x"


def test_a_throttled_upload_stops_before_the_lambdas_timeout(client, monkeypatch):
    real = db.client()

    class Throttled:
        def __getattr__(self, name):
            if name == "batch_write_item":
                return lambda RequestItems: {"UnprocessedItems": RequestItems}
            return getattr(real, name)
    monkeypatch.setattr(db, "client", lambda: Throttled())
    monkeypatch.setattr(sync, "WRITE_SECONDS", 0.5)
    started = time.monotonic()
    r = client.post("/sync/upload", json={"tables": tables()})
    assert r.status_code == 503 and r.json()["code"] == "busy"   # the phone tries again later
    assert time.monotonic() - started < 5


def _restore_pages(client, **body):
    pages, cursor = [], None
    while True:
        page = ok(client.post("/sync/restore", json={"paged": True, "cursor": cursor, **body}))
        pages.append(page)
        cursor = page["next"]
        if cursor is None:
            return pages


def test_restore_in_pages_returns_every_row_once(client, monkeypatch):
    messages = [{"id": i, "role": "user", "content": "x" * 300, "kind": "chat", "task_ids": [],
                 "created_at": "2026-11-18T09:00:00.000000Z"} for i in range(1, 451)]
    ok(client.post("/sync/upload", json={"tables": tables()}))
    ok(client.post("/sync/upload", json={"tables": {"messages": messages}}))
    monkeypatch.setattr(sync, "PAGE_BYTES", 40_000)   # about 100 messages a page
    pages = _restore_pages(client, handover=True, device="Galaxy S9")
    assert len(pages) > 3
    got = [m["id"] for p in pages for m in p["tables"]["messages"]]
    assert got == list(range(1, 451))
    assert sum(len(p["tables"]["tasks"]) for p in pages) == 3
    assert all(p["counters"] == {} for p in pages[:-1]) and pages[-1]["counters"]["message"] == 450
    assert pages[0]["status"]["handedOverAt"] and pages[-1]["status"]["handedOverAt"] == pages[0]["status"]["handedOverAt"]


def test_restore_in_pages_matches_restore_in_one_piece(client):
    ok(client.post("/sync/upload", json={"tables": tables()}))
    pages = _restore_pages(client)
    assert len(pages) == 1 and pages[0]["tables"] == SAMPLE["tables"]


def test_a_cursor_from_elsewhere_is_refused(client):
    r = client.post("/sync/restore", json={"paged": True, "cursor": "not-a-cursor"})
    assert r.status_code == 422 and "cursor" in r.json()["detail"]


def test_a_large_restore_is_gzipped(client):
    messages = [{"id": i, "role": "user", "content": "hello " * 50, "kind": "chat", "task_ids": [],
                 "created_at": "2026-11-18T09:00:00.000000Z"} for i in range(1, 201)]
    ok(client.post("/sync/upload", json={"tables": {"messages": messages}}))
    r = client.post("/sync/restore", json={"paged": True}, headers={"Accept-Encoding": "gzip"})
    assert r.status_code == 200 and r.headers["content-encoding"] == "gzip"
    assert len(r.json()["tables"]["messages"]) == 200


# ---------------------------------------------------------------- layout 1 → 2

def _layout1_spec(name):
    """The table as layout 1 made it: two indexes."""
    spec = db.table_spec(name)
    spec["AttributeDefinitions"] = [{"AttributeName": a, "AttributeType": "S"}
                                    for a in ("pk", "sk", "g1pk", "g1sk", "g2pk", "g2sk")]
    spec["GlobalSecondaryIndexes"] = [
        {"IndexName": f"GSI{n}", "KeySchema": [{"AttributeName": f"g{n}pk", "KeyType": "HASH"},
                                               {"AttributeName": f"g{n}sk", "KeyType": "RANGE"}],
         "Projection": {"ProjectionType": "ALL"}, "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1}}
        for n in (1, 2)]
    return spec


def _layout1_items():
    """A backup written by layout 1: events per task, step lists, message links, index keys."""
    t1 = "2026-11-17T08:00:00.000000Z"
    db.put_raw({"pk": "task", "sk": db.pad(1), "id": 1, "title": "Write report", "status": "open", "created_at": t1,
                "g1pk": "task#open", "g1sk": db.pad(1)})
    db.put_raw({"pk": "task", "sk": db.pad(2), "id": 2, "parent_id": 1, "title": "Outline", "status": "open",
                "created_at": t1, "g1pk": "task#open", "g1sk": db.pad(2)})
    for task_id, rid, kind in ((1, "aaaa0001", "postponed"), (1, "aaaa0002", "done"), (2, "bbbb0001", "stuck")):
        db.put_raw({"pk": f"ev#{task_id}", "sk": f"{t1}#{rid}", "task_id": task_id, "kind": kind, "created_at": t1,
                    "rid": rid, "v": 1, "g1pk": f"ev#{kind}", "g1sk": t1})
    db.put_raw({"pk": "children", "sk": db.pad(1), "ids": {2}})
    db.put_raw({"pk": "msg", "sk": db.pad(1), "id": 1, "role": "user", "content": "hi", "kind": "chat",
                "task_ids": [1], "task_id": 1, "created_at": t1, "g1pk": "msg#chat", "g1sk": db.pad(1)})
    db.put_raw({"pk": "mtask#1", "sk": db.pad(1)})


def test_a_layout1_backup_is_moved_and_restores_completely(client):
    _new_table(_layout1_spec)
    _layout1_items()
    assert not sync.migrated()
    out = ok(client.post("/sync/restore", json={"paged": True}))   # restore moves it first
    assert sync.migrated()
    assert sorted(e["rid"] for e in out["tables"]["events"]) == ["aaaa0001", "aaaa0002", "bbbb0001"]
    assert [t["title"] for t in out["tables"]["tasks"]] == ["Write report", "Outline"]
    assert list(db.query("ev#1")) == [] and list(db.query("ev#2")) == []
    assert list(db.query("children")) == [] and list(db.query("mtask#1")) == []
    assert ok(client.post("/sync/restore", json={"paged": True}))["tables"] == out["tables"]   # once is enough


def test_the_move_stops_at_its_deadline_and_continues_later(client, monkeypatch):
    _new_table(_layout1_spec)
    _layout1_items()
    clock = iter([0.0])   # time runs out after the first task
    monkeypatch.setattr(sync.time, "monotonic", lambda: next(clock, 99.0))
    assert sync.migrate(deadline=1.0) is False
    assert list(db.query("ev#1")) == [] and len(list(db.query("ev#2"))) == 1   # task 1 moved, task 2 not yet
    monkeypatch.setattr(sync.time, "monotonic", time.monotonic)
    assert sync.migrate() is True
    assert len(list(db.query("event"))) == 3 and list(db.query("ev#2")) == []


def test_restore_waits_while_the_move_isnt_finished(client, monkeypatch):
    _new_table(_layout1_spec)
    _layout1_items()
    monkeypatch.setattr(sync, "migrate", lambda deadline=None: False)
    r = client.post("/sync/restore", json={"paged": True})
    assert r.status_code == 503 and r.json()["code"] == "busy"


def test_an_event_delete_also_removes_it_where_layout1_kept_it(client):
    _new_table(_layout1_spec)
    _layout1_items()
    ok(client.post("/sync/upload", json={"deletes": {"events": [[2, "2026-11-17T08:00:00.000000Z", "bbbb0001"]]}}))
    assert list(db.query("ev#2")) == []


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


def test_the_setup_script_brings_a_layout1_table_up_to_date(client):
    import importlib.util
    path = Path(__file__).resolve().parents[1] / "scripts/aws_lambda_setup.py"
    spec = importlib.util.spec_from_file_location("aws_lambda_setup", path)
    setup = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(setup)
    _new_table(_layout1_spec)
    _layout1_items()
    s = get_settings()
    ddb = boto3.client("dynamodb", region_name=s.aws_region)
    setup.upgrade_table(ddb, s.dynamodb_table, s.aws_region)
    t = ddb.describe_table(TableName=s.dynamodb_table)["Table"]
    assert t.get("GlobalSecondaryIndexes", []) == []
    assert (t["ProvisionedThroughput"]["ReadCapacityUnits"], t["ProvisionedThroughput"]["WriteCapacityUnits"]) == db.CAPACITY
    assert sync.migrated() and len(list(db.query("event"))) == 3
    setup.upgrade_table(ddb, s.dynamodb_table, s.aws_region)   # a re-run changes nothing
