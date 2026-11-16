"""The owner check: Cognito access tokens, verified locally against the pool's public keys."""

import asyncio
import json
import time

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException

from app import auth, httpclient
from app.config import get_settings

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
OTHER_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def jwks(key=KEY, kid="k1"):
    public = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key()))
    return {"keys": [{**public, "kid": kid, "alg": "RS256", "use": "sig"}]}


def token(key=KEY, kid="k1", **overrides):
    s = get_settings()
    claims = {"sub": s.owner_user_id, "iss": auth._issuer(), "token_use": "access", "client_id": s.cognito_client_id,
              "exp": int(time.time()) + 3600, "iat": int(time.time())}
    claims.update(overrides)
    claims = {k: v for k, v in claims.items() if v is not None}
    return jwt.encode(claims, key, algorithm="RS256", headers={"kid": kid})


class FakeKeysClient:
    def __init__(self, status=200, exc=None, body=None):
        self.status, self.exc, self.body = status, exc, body if body is not None else jwks()
        self.calls = 0

    async def get(self, url, **kw):
        self.calls += 1
        assert url == f"{auth._issuer()}/.well-known/jwks.json"
        if self.exc:
            raise self.exc
        return httpx.Response(self.status, json=self.body)


@pytest.fixture
def keys(monkeypatch):
    client = FakeKeysClient()
    monkeypatch.setattr(httpclient, "client", lambda: client)
    monkeypatch.setattr(auth, "_keys", {})
    monkeypatch.setattr(auth, "_keys_at", 0.0)
    return client


def check(tok):
    try:
        return asyncio.run(auth.require_owner(f"Bearer {tok}"))
    except HTTPException as e:
        return e.status_code


def test_the_owner_gets_in(keys):
    assert check(token()) == get_settings().owner_user_id


def test_keys_are_fetched_once(keys):
    check(token())
    check(token())
    assert keys.calls == 1


def test_someone_else_is_403(keys):
    assert check(token(sub="someone-else")) == 403


@pytest.mark.parametrize("overrides", [
    {"exp": int(time.time()) - 600},                       # expired
    {"iss": "https://cognito-idp.us-west-2.amazonaws.com/other_pool"},
    {"client_id": "another-app"},
    {"token_use": "id"},                                   # an id token, not an access token
    {"token_use": None},
])
def test_bad_tokens_are_401(keys, overrides):
    assert check(token(**overrides)) == 401


def test_a_forged_signature_is_401(keys):
    assert check(token(key=OTHER_KEY)) == 401


def test_garbage_is_401(keys):
    assert check("not-a-jwt") == 401


def test_no_header_is_401():
    with pytest.raises(HTTPException) as e:
        asyncio.run(auth.require_owner(""))
    assert e.value.status_code == 401


def test_an_unknown_key_id_refetches_but_not_every_time(keys):
    assert check(token(kid="k1")) == get_settings().owner_user_id
    assert check(token(kid="rotated")) == 401          # fetched within the last minute: not again
    assert keys.calls == 1


@pytest.mark.parametrize("status", [500, 502, 503, 429])
def test_aws_errors_are_503_not_a_logout(monkeypatch, keys, status):
    keys.status = status
    assert check(token()) == 503


def test_aws_unreachable_is_503(keys):
    keys.exc = httpx.ConnectTimeout("slow")
    assert check(token()) == 503
