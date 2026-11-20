"""Google sign-in: the owner's Google ID token is traded for the app's own login token, which every request then shows."""

import asyncio
import json
import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app import auth, httpclient
from app.config import get_settings

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OWNER = "owner@example.com"   # conftest sets OWNER_GOOGLE_EMAIL=Owner@Example.com: compared without case


def google_token(key=KEY, kid="g1", **overrides):
    now = int(time.time())
    claims = {"iss": "https://accounts.google.com", "aud": get_settings().google_client_id, "sub": "1234567890",
              "email": OWNER, "email_verified": True, "iat": now, "exp": now + 3600}
    claims.update(overrides)
    claims = {k: v for k, v in claims.items() if v is not None}
    return jwt.encode(claims, key, algorithm="RS256", headers={"kid": kid})


class FakeGoogle:
    def __init__(self):
        public = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(KEY.public_key()))
        self.body = {"keys": [{**public, "kid": "g1", "alg": "RS256", "use": "sig"}]}
        self.status, self.exc, self.calls = 200, None, 0

    async def get(self, url, **kw):
        self.calls += 1
        assert url == auth.GOOGLE_KEYS_URL
        if self.exc:
            raise self.exc
        return httpx.Response(self.status, json=self.body)


@pytest.fixture
def google(monkeypatch):
    fake = FakeGoogle()
    monkeypatch.setattr(httpclient, "client", lambda: fake)
    monkeypatch.setattr(auth.google_keys, "keys", {})
    monkeypatch.setattr(auth.google_keys, "fetched_at", 0.0)
    return fake


def verify(tok):
    try:
        return asyncio.run(auth.verify_google(tok))
    except HTTPException as e:
        return e.status_code


def owner_check(tok):
    try:
        return asyncio.run(auth.require_owner(f"Bearer {tok}"))
    except HTTPException as e:
        return e.status_code


# ---------------------------------------------------------------- Google's token

def test_the_owner_s_google_token_is_accepted(google):
    assert verify(google_token()) == (OWNER, "1234567890")


def test_google_s_keys_are_fetched_once(google):
    verify(google_token())
    verify(google_token())
    assert google.calls == 1


def test_the_short_issuer_form_is_accepted_too(google):
    assert verify(google_token(iss="accounts.google.com")) == (OWNER, "1234567890")


@pytest.mark.parametrize("overrides", [
    {"exp": int(time.time()) - 600},                    # expired
    {"aud": "someone-elses-app.apps.googleusercontent.com"},
    {"iss": "https://evil.example"},
    {"aud": None},
])
def test_bad_google_tokens_are_401(google, overrides):
    assert verify(google_token(**overrides)) == 401


def test_a_forged_google_signature_is_401(google):
    assert verify(google_token(key=OTHER_KEY)) == 401


def test_an_unknown_google_key_is_401(google):
    assert verify(google_token(kid="nope")) == 401


def test_garbage_is_401(google):
    assert verify("not-a-jwt") == 401


@pytest.mark.parametrize("overrides", [
    {"email": "stranger@example.com"},
    {"email_verified": False},
    {"email": None},
])
def test_any_other_account_is_403(google, overrides):
    assert verify(google_token(**overrides)) == 403


def test_google_unreachable_is_503(google):
    google.exc = httpx.ConnectTimeout("slow")
    assert verify(google_token()) == 503


def test_google_sign_in_not_set_up_is_503(google, monkeypatch):
    monkeypatch.setattr(auth, "google_enabled", lambda: False)
    assert verify(google_token()) == 503


# ---------------------------------------------------------------- the app's own token

def test_our_token_lets_the_owner_in():
    token, expires = auth.make_session_token(OWNER, "1234567890")
    assert owner_check(token) == "google:1234567890"
    assert expires - time.time() > 360 * 24 * 3600


def test_an_expired_token_is_401():
    token, _ = auth.make_session_token(OWNER, "1", now=int(time.time()) - 400 * 24 * 3600)
    assert owner_check(token) == 401


def test_a_token_signed_with_another_secret_is_401():
    now = int(time.time())
    forged = jwt.encode({"iss": "prominder", "aud": "prominder", "sub": "google:1", "email": OWNER, "iat": now,
                         "exp": now + 3600}, "guessed-secret", algorithm="HS256")
    assert owner_check(forged) == 401


def test_a_token_for_a_former_owner_is_401():
    token, _ = auth.make_session_token("former@example.com", "1")
    assert owner_check(token) == 401


def test_a_token_from_another_provider_is_401(google):
    """The web app's Cognito tokens (RS256) aren't accepted any more: only the app's own login token is."""
    assert owner_check(google_token()) == 401


def test_a_notification_button_secret_isnt_the_login_key():
    """The login key is derived from ACTION_SECRET, not ACTION_SECRET itself."""
    now = int(time.time())
    forged = jwt.encode({"iss": "prominder", "aud": "prominder", "sub": "google:1", "email": OWNER, "iat": now,
                         "exp": now + 3600}, get_settings().action_secret, algorithm="HS256")
    assert owner_check(forged) == 401


# ---------------------------------------------------------------- POST /auth/google

@pytest.fixture
def client():
    from app.lambda_app import app
    with TestClient(app) as c:
        yield c


def test_sign_in_end_to_end(google, client):
    r = client.post("/auth/google", json={"idToken": google_token()})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["email"] == OWNER
    assert body["expiresAt"] > time.time()
    assert owner_check(body["token"]) == "google:1234567890"


def test_sign_in_as_someone_else_is_refused(google, client):
    r = client.post("/auth/google", json={"idToken": google_token(email="stranger@example.com")})
    assert r.status_code == 403
    assert OWNER in r.json()["detail"]


def test_sign_in_needs_a_token(client):
    assert client.post("/auth/google", json={}).status_code == 422
