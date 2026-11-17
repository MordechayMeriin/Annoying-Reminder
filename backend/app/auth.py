"""
Only the owner gets in.

The web and Android apps log in with Cognito (a code sent by email) and send the access token. The
token is a JWT signed by the user pool: we check its signature against the pool's public keys (fetched
once and cached), that it was issued by our pool for our app client, and that it hasn't expired. Then
we require that the user id (`sub`) equals OWNER_USER_ID. No call to AWS per request.

A logged-out token stays valid until it expires (an hour at most), as with any signed token.
"""

import hmac
import logging
import time

import httpx
import jwt
from fastapi import Header, HTTPException

from . import httpclient
from .config import get_settings

log = logging.getLogger("auth")

_KEYS_TTL_S = 6 * 3600     # Cognito rotates keys rarely; an unknown key id triggers a refetch anyway
_REFETCH_MIN_S = 60        # ... but not more than once a minute (garbage tokens can't make us hammer AWS)
_LEEWAY_S = 30             # clock difference allowed

_keys: dict[str, jwt.PyJWK] = {}
_keys_at = 0.0


def _unavailable() -> HTTPException:
    # 503, not 401: the app logs out on 401, and an AWS outage shouldn't cost you your login.
    return HTTPException(status_code=503, detail="Can't check your login right now. Try again in a minute.")


def _issuer() -> str:
    s = get_settings()
    return f"https://cognito-idp.{s.aws_region}.amazonaws.com/{s.cognito_user_pool_id}"


async def _fetch_keys() -> None:
    global _keys, _keys_at
    try:
        r = await httpclient.client().get(f"{_issuer()}/.well-known/jwks.json", timeout=10)
    except httpx.HTTPError as e:
        log.warning("Cognito keys unreachable: %s", type(e).__name__)
        raise _unavailable() from e
    if r.status_code != 200:
        log.warning("Cognito keys answered %s", r.status_code)
        raise _unavailable()
    try:
        keys = {k["kid"]: jwt.PyJWK(k) for k in r.json()["keys"] if k.get("kid")}
    except (ValueError, KeyError, TypeError, jwt.PyJWTError):
        raise _unavailable() from None
    _keys, _keys_at = keys, time.time()


async def _key(kid: str) -> jwt.PyJWK | None:
    now = time.time()
    if kid not in _keys or now - _keys_at > _KEYS_TTL_S:
        if not _keys or now - _keys_at > _REFETCH_MIN_S:
            await _fetch_keys()
    return _keys.get(kid)


async def _user_id_for(token: str) -> str | None:
    """The token's user id; None when the token is invalid. Raises 503 when the pool's keys can't be fetched."""
    try:
        kid = jwt.get_unverified_header(token).get("kid")
    except jwt.PyJWTError:
        return None
    if not kid:
        return None
    key = await _key(kid)
    if key is None:
        return None
    try:
        claims = jwt.decode(token, key=key.key, algorithms=["RS256"], issuer=_issuer(), leeway=_LEEWAY_S,
                            options={"require": ["exp", "iss", "sub"], "verify_aud": False})
    except jwt.PyJWTError:
        return None
    # An access token for our app client (not an id token, not another client's token).
    if claims.get("token_use") != "access" or claims.get("client_id") != get_settings().cognito_client_id:
        return None
    return claims["sub"]


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
