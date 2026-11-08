"""Signed tokens for notification buttons (Done / Snooze) — work without a login session."""

import base64
import hashlib
import hmac
import time


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _unb64(data: str) -> bytes:
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


def make_action_token(task_id: int, secret: str, ttl_seconds: int = 3 * 24 * 3600) -> str:
    expires = int(time.time()) + ttl_seconds
    payload = f"{task_id}.{expires}"
    sig = hmac.new(secret.encode(), payload.encode(), hashlib.sha256).digest()
    return f"{payload}.{_b64(sig)}"


def verify_action_token(token: str, secret: str) -> int | None:
    """Returns the task id if the token is genuine and not expired, else None."""
    try:
        task_id_s, expires_s, sig_s = token.split(".")
        payload = f"{task_id_s}.{expires_s}"
        expected = hmac.new(secret.encode(), payload.encode(), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, _unb64(sig_s)):
            return None
        if int(expires_s) < time.time():
            return None
        return int(task_id_s)
    except (ValueError, TypeError):
        return None
