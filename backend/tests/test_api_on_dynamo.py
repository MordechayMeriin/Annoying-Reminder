"""Smoke test: the real API routes on moto's DynamoDB (no LLM, no push). Catches values the JSON
responses can't carry (Decimals, dates) and routes that still expect SQL."""

from datetime import datetime, timedelta, timezone

import boto3
import pytest
from fastapi.testclient import TestClient
from moto import mock_aws

from app import db, notify, tasks, thread, user_settings
from app.auth import require_owner, require_cron
from app.config import get_settings
from app.main import app


@pytest.fixture
def client(monkeypatch):
    with mock_aws():
        db._client = None
        boto3.client("dynamodb", region_name=get_settings().aws_region).create_table(
            **db.table_spec(get_settings().dynamodb_table))
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


def test_the_main_screens_and_buttons(client):
    now = datetime.now(timezone.utc)
    parent = tasks.create("Write report", due_at=now + timedelta(hours=5), next_action="Open the doc", estimate_min=25)
    tasks.create("Outline", parent_id=parent["id"])
    tasks.create("Standup", kind="meeting", due_at=now + timedelta(hours=2))
    thread.add("assistant", "Morning plan", "morning", task_ids=[parent["id"]], chips=[{"label": "▶", "action": "start"}])

    msgs = ok(client.get("/messages"))
    assert "Morning plan" in str(msgs)
    assert ok(client.get("/tasks"))
    ok(client.get("/tasks/overview"))
    ok(client.get("/focus"))
    detail = ok(client.get(f"/tasks/{parent['id']}"))
    assert detail["title"] == "Write report" and [x["title"] for x in detail["subtasks"]] == ["Outline"]
    ok(client.patch(f"/tasks/{parent['id']}", json={"title": "Write the report"}))
    ok(client.post("/what-now", json={}))

    started = ok(client.post(f"/tasks/{parent['id']}/start", json={"minutes": 25}))
    session_id = started["session"]["id"]
    ok(client.patch(f"/sessions/{session_id}", json={"planned_min": 30}))
    ok(client.post(f"/sessions/{session_id}/end", json={"outcome": "stopped"}))
    ok(client.post(f"/sessions/{session_id}/note", json={"text": "Stopped at the intro"}))
    ok(client.post(f"/tasks/{parent['id']}/action", json={"action": "done"}))
    assert tasks.get(parent["id"])["status"] == "done"

    ok(client.get("/preferences"))
    ok(client.get("/settings"))
    ok(client.patch("/settings", json={"values": {"max_nags": "3"}}))
    assert client.patch("/settings", json={"values": {"max_nags": "lots"}}).status_code == 422
    ok(client.post("/push/subscribe", json={"endpoint": "https://push.example/x", "keys": {"p256dh": "k", "auth": "a"}}))
    assert len(notify.subscriptions()) == 1 and user_settings.stored() == {"max_nags": "3"}
    ok(client.post("/tick"))
