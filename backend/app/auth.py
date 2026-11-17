"""
Only the owner gets in.

Two ways to log in. Both end with a token the app sends as `Authorization: Bearer …`:

* Google sign-in (the Android app). The app gets an ID token from Google (signed by Google, addressed
  to GOOGLE_CLIENT_ID) and trades it at POST /auth/google. We check Google's signature against
  Google's public keys, the audience, the issuer and the expiry, and that the account is
  OWNER_GOOGLE_EMAIL with a verified address. Then we hand out our own session token: a JWT signed
  with a key derived from ACTION_SECRET, valid for SESSION_DAYS. Changing ACTION_SECRET (or
  OWNER_GOOGLE_EMAIL) logs every device out.
* Cognito (the web app's emailed code; optional, on when COGNITO_* and OWNER_USER_ID are set). The
  token is a Cognito access token: we check its signature against the pool's public keys, that it was
  issued by our pool for our app client, and that it hasn't expired. Then we require that the user id
  (`sub`) equals OWNER_USER_ID.

No call to Google or AWS per request: their public keys are fetched once and cached. A logged-out
token stays valid until it expires, as with any signed token.
"""

import hashlib
import hmac
import logging
import time
from typing import Callable

import httpx
import jwt
from fastapi import Header, HTTPException

from . import httpclient
from .config import get_settings

log = logging.getLogger("auth")

_KEYS_TTL_S = 6 * 3600     # keys rotate rarely; an unknown key id triggers a refetch anyway
_REFETCH_MIN_S = 60        # ... but not more than once a minute (garbage tokens can't make us hammer anyone)
_LEEWAY_S = 30             # clock difference allowed

GOOGLE_KEYS_URL = "https://www.googleapis.com/oauth2/v3/certs"
GOOGLE_ISSUERS = ("accounts.google.com", "https://accounts.google.com")

SESSION_ISSUER = "prominder"
SESSION_DAYS = 365


def _unavailable(what: str) -> HTTPException:
    # 503, not 401: the app logs out on 401, and an outage shouldn't cost you your login.
    return HTTPException(status_code=503, detail=f"Can't check {what} right now. Try again in a minute.")


class _KeySet:
    """A provider's public signing keys (a JWKS URL), fetched when needed and cached."""

    def __init__(self, name: str, url: Callable[[], str]):
        self.name, self.url = name, url
        self.keys: dict[str, jwt.PyJWK] = {}
        self.fetched_at = 0.0

    async def _fetch(self) -> None:
        try:
            r = await httpclient.client().get(self.url(), timeout=10)
        except httpx.HTTPError as e:
            log.warning("%s keys unreachable: %s", self.name, type(e).__name__)
            raise _unavailable("your login") from e
        if r.status_code != 200:
            log.warning("%s keys answered %s", self.name, r.status_code)
            raise _unavailable("your login")
        try:
            keys = {k["kid"]: jwt.PyJWK(k) for k in r.json()["keys"] if k.get("kid")}
        except (ValueError, KeyError, TypeError, jwt.PyJWTError):
            raise _unavailable("your login") from None
        self.keys, self.fetched_at = keys, time.time()

    async def get(self, kid: str) -> jwt.PyJWK | None:
        now = time.time()
        if kid not in self.keys or now - self.fetched_at > _KEYS_TTL_S:
            if not self.keys or now - self.fetched_at > _REFETCH_MIN_S:
                await self._fetch()
        return self.keys.get(kid)


def _issuer() -> str:
    s = get_settings()
    return f"https://cognito-idp.{s.aws_region}.amazonaws.com/{s.cognito_user_pool_id}"


cognito_keys = _KeySet("Cognito", lambda: f"{_issuer()}/.well-known/jwks.json")
google_keys = _KeySet("Google", lambda: GOOGLE_KEYS_URL)


def _kid(token: str) -> str | None:
    try:
        return jwt.get_unverified_header(token).get("kid") or None
    except jwt.PyJWTError:
        return None


# ---------------------------------------------------------------- Google sign-in

def google_enabled() -> bool:
    s = get_settings()
    return bool(s.google_client_id and s.owner_google_email)


async def verify_google(id_token: str) -> tuple[str, str]:
    """(email, Google user id) of the owner's Google ID token. Raises 401 (invalid), 403 (someone else), 503."""
    s = get_settings()
    if not google_enabled():
        raise HTTPException(status_code=503,
                            detail="Google sign-in isn't set up on the server (GOOGLE_CLIENT_ID, OWNER_GOOGLE_EMAIL).")
    invalid = HTTPException(status_code=401, detail="Google's sign-in answer wasn't valid. Try again.")
    kid = _kid(id_token)
    if kid is None:
        raise invalid
    key = await google_keys.get(kid)
    if key is None:
        raise invalid
    try:
        claims = jwt.decode(id_token, key=key.key, algorithms=["RS256"], audience=s.google_client_id,
                            leeway=_LEEWAY_S, options={"require": ["exp", "iss", "sub", "aud"]})
    except jwt.PyJWTError:
        raise invalid from None
    if claims.get("iss") not in GOOGLE_ISSUERS:
        raise invalid
    email = str(claims.get("email") or "").strip().lower()
    verified = claims.get("email_verified") in (True, "true")
    if not (verified and email and hmac.compare_digest(email, s.owner_google_email)):
        log.warning("Google sign-in refused for another account")
        raise HTTPException(status_code=403, detail=f"Only the owner's Google account can use this app. "
                                                    f"Choose {s.owner_google_email} instead.")
    return email, str(claims["sub"])


def _session_key() -> bytes:
    # Derived, so ACTION_SECRET can't be used to forge notification-button tokens from a session or back.
    return hmac.new(get_settings().action_secret.encode(), b"prominder session token v1", hashlib.sha256).digest()


def make_session_token(email: str, google_id: str, now: int | None = None) -> tuple[str, int]:
    """Our own login token for the app, and when it expires (seconds since 1970)."""
    now = int(time.time()) if now is None else now
    expires = now + SESSION_DAYS * 24 * 3600
    claims = {"iss": SESSION_ISSUER, "aud": SESSION_ISSUER, "sub": f"google:{google_id}", "email": email,
              "iat": now, "exp": expires}
    return jwt.encode(claims, _session_key(), algorithm="HS256"), expires


def _session_claims(token: str) -> dict | None:
    try:
        return jwt.decode(token, _session_key(), algorithms=["HS256"], issuer=SESSION_ISSUER, audience=SESSION_ISSUER,
                          leeway=_LEEWAY_S, options={"require": ["exp", "iss", "aud", "sub", "email"]})
    except jwt.PyJWTError:
        return None


# ---------------------------------------------------------------- Cognito

def cognito_enabled() -> bool:
    s = get_settings()
    return bool(s.cognito_user_pool_id and s.cognito_client_id and s.owner_user_id)


async def _cognito_user_id(token: str) -> str | None:
    """The token's user id; None when the token is invalid. Raises 503 when the pool's keys can't be fetched."""
    kid = _kid(token)
    if kid is None:
        return None
    key = await cognito_keys.get(kid)
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


# ---------------------------------------------------------------- the check on every request

async def require_owner(authorization: str = Header(default="")) -> str:
    if not authorization.lower().startswith("bearer "):
        raise HTTPException(status_code=401, detail="Not logged in")
    token = authorization[7:].strip()
    try:
        alg = jwt.get_unverified_header(token).get("alg")
    except jwt.PyJWTError:
        raise HTTPException(status_code=401, detail="Invalid session") from None

    if alg == "HS256":   # our own session token (Google sign-in)
        claims = _session_claims(token)
        if claims is None or not google_enabled():
            raise HTTPException(status_code=401, detail="Invalid session")
        if not hmac.compare_digest(str(claims["email"]), get_settings().owner_google_email):
            raise HTTPException(status_code=401, detail="Invalid session")   # the owner changed: log in again
        return claims["sub"]

    if not cognito_enabled():
        raise HTTPException(status_code=401, detail="Invalid session")
    user_id = await _cognito_user_id(token)
    if user_id is None:
        raise HTTPException(status_code=401, detail="Invalid session")
    if not hmac.compare_digest(user_id, get_settings().owner_user_id):
        raise HTTPException(status_code=403, detail="Not allowed")
    return user_id


def require_cron(x_cron_secret: str = Header(default="")) -> None:
    if not hmac.compare_digest(x_cron_secret, get_settings().cron_secret):
        raise HTTPException(status_code=403, detail="Not allowed")
