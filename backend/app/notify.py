"""
Sending notifications: browser push and Pushover.

Pushover is what actually gets attention: per-message priority, a loud sound for urgent messages and
alarms that repeat until acknowledged. PUSHOVER_MODE (⚙ Settings) decides how the two are combined:
  always — every message goes to both
  only   — Pushover instead of browser push (browser push if Pushover fails)
  backup — browser push; Pushover only when no browser got it, and for alarms
  off    — browser push only
"""

import json
import logging
from datetime import datetime, timedelta, timezone

import httpx
from pywebpush import WebPushException, webpush

from . import db
from .config import get_settings
from .logic import ALARM, NORMAL, URGENT
from .tokens import make_action_token

log = logging.getLogger("notify")


PUSH_BODY_MAX = 300


def push_body(text: str) -> str:
    text = " ".join(text.split())  # notifications show one paragraph anyway
    return text if len(text) <= PUSH_BODY_MAX else text[: PUSH_BODY_MAX - 1] + "…"


def send_push(title: str, body: str, *, task_id: int | None = None, message_id: int | None = None,
              priority: int = 1, url: str | None = None) -> int:
    """
    Send to every subscribed browser. Returns how many deliveries succeeded.
    message_id: the chat message this notification shows; tapping it opens the chat there.
    task_id: adds the ✓ Done / ⏰ Snooze buttons (with a signed token).
    """
    s = get_settings()
    if url is None:
        url = f"/?m={message_id}" if message_id is not None else "/"
    payload: dict = {"title": title, "body": push_body(body), "url": url, "priority": priority, "api": s.api_url}
    if message_id is not None:
        payload["messageId"] = message_id
    if task_id is not None:
        payload["taskId"] = task_id
        payload["token"] = make_action_token(task_id, s.action_secret)

    sent = 0
    for sub in db.fetch_all("select id, endpoint, p256dh, auth from push_subscriptions"):
        try:
            webpush(
                subscription_info={"endpoint": sub["endpoint"], "keys": {"p256dh": sub["p256dh"], "auth": sub["auth"]}},
                data=json.dumps(payload),
                vapid_private_key=s.vapid_private_key,
                vapid_claims={"sub": s.vapid_subject},
                ttl=3600,
            )
            sent += 1
        except WebPushException as e:
            status = getattr(e.response, "status_code", None)
            if status in (404, 410):  # subscription expired or revoked → forget it
                db.execute("delete from push_subscriptions where id = %s", (sub["id"],))
                log.info("Removed expired push subscription %s", sub["id"])
            else:
                log.warning("Push failed (%s): %s", status, e)
    return sent


PUSHOVER_API = "https://api.pushover.net/1"
ALARM_TAG = "annoying-reminder-alarm"   # every alarm carries it, so one call cancels them all
TITLE_MAX, MESSAGE_MAX = 250, 1024

# Until when an alarm may still be repeating. None = none; datetime.max = unknown (after a restart),
# so the first interaction after a restart cancels once to be sure.
_alarm_until: datetime | None = datetime.max.replace(tzinfo=timezone.utc)


def pushover_mode() -> str:
    s = get_settings()
    return s.pushover_mode if s.pushover_enabled else "off"


def send_pushover(title: str, message: str, *, level: int = NORMAL, url: str | None = None,
                  expire_s: int = 3600) -> bool:
    """
    One Pushover message. level: NORMAL, URGENT or ALARM (see logic.py).
    An ALARM repeats every PUSHOVER_ALARM_RETRY_MIN for expire_s seconds, until acknowledged in the
    Pushover app or cancelled with cancel_alarms().
    """
    global _alarm_until
    s = get_settings()
    if not s.pushover_enabled:
        return False
    sound = s.pushover_sound_urgent if level >= URGENT else s.pushover_sound
    data: dict = {"token": s.pushover_token, "user": s.pushover_user, "title": title[:TITLE_MAX],
                  "message": message[:MESSAGE_MAX] or "…", "priority": min(level, ALARM)}
    if sound != "default":
        data["sound"] = sound
    if level >= ALARM:
        retry = max(30, s.pushover_alarm_retry_min * 60)
        expire_s = min(max(expire_s, retry), 10800)  # Pushover: retry ≥ 30 s, expire ≤ 3 h
        data.update({"retry": retry, "expire": expire_s, "tags": ALARM_TAG})
    if url:
        data.update({"url": url, "url_title": "Open chat"})
    try:
        r = httpx.post(f"{PUSHOVER_API}/messages.json", data=data, timeout=15)
        r.raise_for_status()
    except httpx.HTTPError as e:
        log.warning("Pushover failed: %s", e)
        return False
    if level >= ALARM:
        until = datetime.now(timezone.utc) + timedelta(seconds=expire_s)
        _alarm_until = until if _alarm_until is None else max(_alarm_until, until)
    return True


def cancel_alarms() -> None:
    """
    Stop any repeating alarm: called when the user writes in the chat or acts on a task, since they've
    clearly seen it. No API call when no alarm can be running.
    """
    global _alarm_until
    s = get_settings()
    if _alarm_until is None or not s.pushover_enabled:
        return
    if _alarm_until <= datetime.now(timezone.utc):
        _alarm_until = None
        return
    _alarm_until = None
    try:
        r = httpx.post(f"{PUSHOVER_API}/receipts/cancel_by_tag/{ALARM_TAG}.json",
                       data={"token": s.pushover_token}, timeout=15)
        r.raise_for_status()
    except httpx.HTTPError as e:
        log.warning("Cancelling Pushover alarms failed: %s", e)


def notify(title: str, body: str, *, level: int = NORMAL, expire_s: int = 3600,
           task_id: int | None = None, message_id: int | None = None, priority: int = 1) -> None:
    """Send one message to the user's devices, following the Pushover mode (see the top of this file)."""
    s = get_settings()
    mode = pushover_mode()
    link = s.web_origin + (f"/?m={message_id}" if message_id is not None else "/")
    # Urgent messages also stay on screen in the browser until dismissed (sw.js: priority ≥ 2).
    web_priority = max(priority, 2 if level >= URGENT else 1)

    def browser() -> int:
        return send_push(title, body, task_id=task_id, message_id=message_id, priority=web_priority)

    def pushover() -> bool:
        return send_pushover(title, body, level=level, url=link, expire_s=expire_s)

    if mode == "only":
        if not pushover():
            browser()
        return
    sent = browser()
    if mode == "always" or (mode == "backup" and (sent == 0 or level >= ALARM)):
        pushover()
