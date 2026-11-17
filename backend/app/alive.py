"""
"Is the phone still alive?" (Android Phase C). With the phone as the only brain, nobody else would notice if it
stopped: a dead battery that was never charged, the app removed, Android putting it to sleep for good. So once a
day (EventBridge → the Lambda, see lambda_app.py) this reads when the phone last backed up (sync.py records every
upload, and the phone uploads at least once a day). If that's over two days ago, you get an email. At most one
email a day while it lasts; nothing before the handover.

The email goes through Amazon SES from your own address to itself, the identity scripts/aws_setup.py verified
(SES's sandbox only sends between verified addresses, which is all this needs).
"""

import logging
import os
from datetime import datetime, timedelta
from typing import Any, Callable

from . import db, sync
from .config import get_settings

log = logging.getLogger("alive")

STALE_AFTER = timedelta(days=2)
_ALERT_KEY = ("sync", "alive_alert")


def alert_address() -> str:
    """ALERT_EMAIL, else the owner's Google address."""
    return (os.environ.get("ALERT_EMAIL", "").strip() or get_settings().owner_google_email).lower()


def ses_send(to: str, subject: str, body: str) -> None:
    import boto3  # here, so the module loads without AWS (tests pass their own sender)

    boto3.client("sesv2", region_name=os.environ.get("SES_REGION") or get_settings().aws_region).send_email(
        FromEmailAddress=to, Destination={"ToAddresses": [to]},
        Content={"Simple": {"Subject": {"Data": subject}, "Body": {"Text": {"Data": body}}}})


def check(now: datetime | None = None, send: Callable[[str, str, str], None] = ses_send) -> dict[str, Any]:
    """Runs the check; returns what it found and did (also logged, for CloudWatch)."""
    now = now or db.now_utc()
    st = sync.status()
    if not st["handedOverAt"]:
        return {"ok": True, "result": "not handed over yet"}
    last = db.parse_ts(st["lastUploadAt"]) if st["lastUploadAt"] else db.parse_ts(st["handedOverAt"])
    silent = now - last
    if silent <= STALE_AFTER:
        return {"ok": True, "result": "alive", "lastUploadAt": st["lastUploadAt"]}

    alert = db.get_raw(*_ALERT_KEY) or {}
    sent_at = db.parse_ts(alert["sent_at"]) if alert.get("sent_at") else None
    if sent_at is not None and now - sent_at < timedelta(hours=23):
        return {"ok": False, "result": "silent; already emailed today", "lastUploadAt": st["lastUploadAt"]}

    to = alert_address()
    if not to:
        log.error("The phone has been silent since %s, but there's no address to email (ALERT_EMAIL)", last)
        return {"ok": False, "result": "silent; no address to email"}
    days = silent.days
    hours = silent.seconds // 3600
    body = (
        f"ProMinder on your phone hasn't backed up for {days} days and {hours} hours "
        f"(last backup: {last:%Y-%m-%d %H:%M} UTC, from {st.get('device') or 'your phone'}).\n\n"
        "With the phone as the only brain, that may mean no reminders are arriving either. Worth a look:\n"
        "- Is the phone on, and charged?\n"
        "- Open ProMinder. Does it start? Diagnostics shows the last backup, the last tick and their errors.\n"
        "- Samsung may have put the app to sleep: Setup lists the settings that keep it awake.\n\n"
        "You'll get this once a day until the phone backs up again."
    )
    send(to, "ProMinder: the phone has been silent for 2 days", body)
    db.put_raw({"pk": _ALERT_KEY[0], "sk": _ALERT_KEY[1], "sent_at": db.ts(now), "last_upload_at": st["lastUploadAt"]})
    log.warning("The phone has been silent since %s: emailed %s", last, to)
    return {"ok": False, "result": "silent; emailed", "lastUploadAt": st["lastUploadAt"]}
