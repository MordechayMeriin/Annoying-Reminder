"""
Only the owner gets in.

The web app logs in with Supabase Auth and sends its access token. We ask Supabase who the
token belongs to (works with both legacy and new JWT signing keys), then require that the
user id equals OWNER_USER_ID. Results are cached briefly so we don't call Supabase every time.
"""

import hmac
import logging
import time

import httpx
from fastapi import Header, HTTPException

from . import httpclient
from .config import get_settings

log = logging.getLogger("auth")

_cache: dict[str, tuple[str, float]] = {}
_CACHE_SECONDS = 300


def _unavailable() -> HTTPException:
    # 503, not 401: the app logs out on 401, and a Supabase outage shouldn't cost you your login.
    return HTTPException(status_code=503, detail="Can't check your login right now. Try again in a minute.")


async def _user_id_for(token: str) -> str | None:
    """The token's user id; None when Supabase says the token is invalid. Raises 503 when Supabase
    can't answer (outage, rate limit, network)."""
    now = time.time()
    hit = _cache.get(token)
    if hit and hit[1] > now:
        return hit[0]
    s = get_settings()
    try:
        r = await httpclient.client().get(
            f"{s.supabase_url}/auth/v1/user",
            headers={"apikey": s.supabase_anon_key, "Authorization": f"Bearer {token}"},
            timeout=10,
        )
    except httpx.HTTPError as e:
        log.warning("Supabase auth unreachable: %s", type(e).__name__)
        raise _unavailable() from e
    if r.status_code in (401, 403):
        return None
    if r.status_code != 200:
        log.warning("Supabase auth answered %s", r.status_code)
        raise _unavailable()
    try:
        user_id = r.json().get("id")
    except ValueError:
        raise _unavailable() from None
    if user_id:
        if len(_cache) > 50:
            _cache.clear()
        _cache[token] = (user_id, now + _CACHE_SECONDS)
    return user_id


async def require_owner(authorization: str = Header(default="")) -> str:
    if not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Not logged in")
    user_id = await _user_id_for(authorization[7:].strip())
    if user_id is None:
        raise HTTPException(status_code=401, detail="Invalid session")
    if not hmac.compare_digest(user_id, get_settings().owner_user_id):
        raise HTTPException(status_code=403, detail="Not allowed")
    return user_id


def require_cron(x_cron_secret: str = Header(default="")) -> None:
    if not hmac.compare_digest(x_cron_secret, get_settings().cron_secret):
        raise HTTPException(status_code=403, detail="Not allowed")
