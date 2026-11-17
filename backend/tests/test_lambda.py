"""The Lambda function (lambda_app.py): requests through the function URL, and the daily "is the phone alive?" check."""

import json
from datetime import timedelta

import boto3
import pytest
from moto import mock_aws

from app import alive, auth, db, sync
from app.config import get_settings


def _new_table():
    db._client = None
    aws = boto3.client("dynamodb", region_name=get_settings().aws_region)
    name = get_settings().dynamodb_table
    if name in aws.list_tables()["TableNames"]:
        aws.delete_table(TableName=name)
    aws.create_table(**db.table_spec(name))


@pytest.fixture
def table():
    with mock_aws():
        _new_table()
        sync._forget_cache()
        yield
        db._client = None


@pytest.fixture
def lam():
    from app import lambda_app
    return lambda_app


def url_event(method, path, body=None, token=None):
    """What a Lambda function URL sends (payload format 2.0)."""
    headers = {"host": "abc.lambda-url.us-west-2.on.aws", "content-type": "application/json"}
    if token:
        headers["authorization"] = f"Bearer {token}"
    return {
        "version": "2.0", "routeKey": "$default", "rawPath": path, "rawQueryString": "", "headers": headers,
        "requestContext": {"http": {"method": method, "path": path, "sourceIp": "1.2.3.4", "protocol": "HTTP/1.1"},
                           "domainName": headers["host"], "stage": "$default"},
        "body": json.dumps(body) if body is not None else None, "isBase64Encoded": False,
    }


def call(lam, *args, **kw):
    r = lam.handler(url_event(*args, **kw), None)
    return r["statusCode"], json.loads(r["body"]) if r.get("body") else None


def owner_token():
    s = get_settings()
    return auth.make_session_token(s.owner_google_email, "123")[0]


def test_health(lam):
    assert call(lam, "GET", "/health") == (200, {"ok": True})


def test_the_backup_needs_the_owners_login(lam, table):
    assert call(lam, "GET", "/sync/status")[0] == 401
    assert call(lam, "GET", "/sync/status", token="garbage")[0] == 401
    status, body = call(lam, "GET", "/sync/status", token=owner_token())
    assert status == 200 and body["handedOverAt"] is None


def test_a_login_token_from_render_still_works(lam, table):
    """Same ACTION_SECRET → the same signing key: the phone stays logged in across the move."""
    token = owner_token()   # what Render handed out
    assert call(lam, "POST", "/sync/restore", {"handover": True, "device": "Galaxy S9"}, token)[0] == 200
    status, body = call(lam, "GET", "/sync/status", token=token)
    assert body["handedOverAt"] and body["device"] == "Galaxy S9"


def test_upload_and_bad_rows(lam, table):
    token = owner_token()
    row = {"key": "quiet_start", "value": "22:00", "updated_at": "2026-11-17T08:00:00.000000Z"}
    status, body = call(lam, "POST", "/sync/upload", {"device": "S9", "tables": {"settings": [row]}}, token)
    assert status == 200, body
    assert call(lam, "GET", "/sync/status", token=token)[1]["lastUploadAt"]
    status, body = call(lam, "POST", "/sync/upload", {"tables": {"tasks": [{"id": "x"}]}}, token)
    assert status == 422


def test_google_sign_in_is_rate_limited(lam, monkeypatch):
    async def ok(token):
        return "owner@example.com", "123"
    monkeypatch.setattr(auth, "verify_google", ok)
    lam._login_times.clear()
    codes = [call(lam, "POST", "/auth/google", {"idToken": "t"})[0] for _ in range(11)]
    assert codes[:10] == [200] * 10 and codes[10] == 429


def test_the_daily_event_runs_the_alive_check(lam, monkeypatch):
    monkeypatch.setattr(alive, "check", lambda: {"ok": True, "result": "alive"})
    event = {"source": "aws.events", "detail-type": "Scheduled Event", "detail": {}}
    assert lam.handler(event, None) == {"ok": True, "result": "alive"}


# ---------------------------------------------------------------- alive.check

class Outbox(list):
    def __call__(self, to, subject, body):
        self.append((to, subject, body))


def test_nothing_before_the_handover(table):
    out = Outbox()
    assert alive.check(send=out)["result"] == "not handed over yet" and out == []


def test_a_phone_that_backed_up_recently_is_alive(table):
    sync.restore(handover=True, device="S9")
    sync.upload({}, {}, device="S9")
    out = Outbox()
    assert alive.check(db.now_utc() + timedelta(days=1), send=out)["result"] == "alive" and out == []


def test_two_silent_days_send_one_email_a_day(table):
    sync.restore(handover=True, device="Galaxy S9")
    sync.upload({}, {}, device="Galaxy S9")
    out = Outbox()
    later = db.now_utc() + timedelta(days=2, hours=1)
    assert alive.check(later, send=out)["result"] == "silent; emailed"
    to, subject, body = out[0]
    assert to == get_settings().owner_google_email
    assert "2 days" in subject and "Galaxy S9" in body
    assert alive.check(later + timedelta(hours=5), send=out)["result"] == "silent; already emailed today"
    assert alive.check(later + timedelta(days=1), send=out)["result"] == "silent; emailed"
    assert len(out) == 2


def test_alert_address_can_be_set(table, monkeypatch):
    monkeypatch.setenv("ALERT_EMAIL", "Me@Example.com")
    assert alive.alert_address() == "me@example.com"
