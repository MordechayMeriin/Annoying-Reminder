"""Sending notifications: browser push (main channel) and Pushover (critical backup)."""

import json
import logging

import httpx
from pywebpush import WebPushException, webpush

from . import db
from .config import get_settings
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


def send_pushover(title: str, message: str, *, emergency: bool = False, url: str | None = None) -> bool:
    s = get_settings()
    if not s.pushover_enabled:
        return False
    data = {"token": s.pushover_token, "user": s.pushover_user, "title": title, "message": message[:1000]}
    if emergency:
        # Repeats every 5 min for up to 1 hour until you acknowledge it in the Pushover app.
        data.update({"priority": 2, "retry": 300, "expire": 3600})
    else:
        data["priority"] = 1
    if url:
        data.update({"url": url, "url_title": "Open"})
    try:
        r = httpx.post("https://api.pushover.net/1/messages.json", data=data, timeout=15)
        r.raise_for_status()
        return True
    except httpx.HTTPError as e:
        log.warning("Pushover failed: %s", e)
        return False


def notify(title: str, body: str, **kwargs) -> None:
    """Browser push first; if no browser received it, fall back to Pushover."""
    if send_push(title, body, **kwargs) == 0:
        mid = kwargs.get("message_id")
        send_pushover(title, body, url=get_settings().web_origin + (f"/?m={mid}" if mid else "/"))
