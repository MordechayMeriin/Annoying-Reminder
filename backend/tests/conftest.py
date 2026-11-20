"""Dummy settings so the app's modules can be imported without a real environment."""

import os
import sys
from pathlib import Path

for key, value in {
    "AWS_REGION": "us-west-2",
    "DYNAMODB_TABLE": "prominder-test",
    # Never reach a real AWS account from the tests (moto intercepts the calls).
    "AWS_ACCESS_KEY_ID": "testing",
    "AWS_SECRET_ACCESS_KEY": "testing",
    "GOOGLE_CLIENT_ID": "test-web-client.apps.googleusercontent.com",
    "OWNER_GOOGLE_EMAIL": "Owner@Example.com",
    "ACTION_SECRET": "action",
    "OPENROUTER_API_KEY": "sk-test",
    "OPENROUTER_MODELS": "test/model",
}.items():
    os.environ.setdefault(key, value)

sys.path.insert(0, str(Path(__file__).parent))  # so tests can import each other's fixtures


import asyncio  # noqa: E402

import pytest  # noqa: E402


@pytest.fixture(autouse=True)
def _event_loop():
    """Mangum (the Lambda adapter) runs each request on the current event loop, as on Lambda. asyncio.run() in other
    tests leaves none behind, so every test gets its own."""
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    yield
    asyncio.set_event_loop(None)
    loop.close()
