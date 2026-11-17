"""
Writes android/app/src/test/resources/restore-sample.json: a /sync/restore answer made by the real backend code
(on moto's fake DynamoDB), which the Android tests decode and encode again. Run it again after changing a stored
field (tests/test_sync.py fails until you do):

    cd backend && python scripts/make_android_sync_sample.py
"""
import json, sys
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tests"))
import conftest  # noqa: F401  (env)
import boto3
from moto import mock_aws
from app import db, preferences, sessions, sync, tasks, thread, user_settings
from app.config import get_settings

with mock_aws():
    boto3.client("dynamodb", region_name=get_settings().aws_region).create_table(**db.table_spec(get_settings().dynamodb_table))
    now = datetime(2026, 11, 17, 8, 0, tzinfo=timezone.utc)
    parent = tasks.create("Write report", due_at=now + timedelta(hours=5), next_action="Open the doc", estimate_min=25, description="For Dana")
    step = tasks.create("Outline", parent_id=parent["id"])
    tasks.create("Standup", kind="meeting", due_at=now + timedelta(days=1), recurrence="weekdays")
    tasks.add_event(parent["id"], "postponed", "snoozed 60 min")
    tasks.set_focus([parent["id"]], date(2026, 11, 17))
    thread.add("user", "remind me to write the report", task_ids=[parent["id"]])
    thread.add("assistant", "בוקר טוב! Morning plan", "morning", task_ids=[parent["id"], step["id"]],
               chips=[{"label": "▶ Start", "action": "start", "task_id": parent["id"]}], model="test/model")
    s = sessions.start(parent["id"], 25)
    sessions.end(s["id"], "stopped")
    preferences.add("Shorter messages")
    user_settings.save({"morning_time": "08:00"})
    out = sync.restore(handover=True, device="fixture")
(ROOT.parent / "android/app/src/test/resources/restore-sample.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n")
print("ok", {k: len(v) for k, v in out["tables"].items()})
