"""Dummy settings so app modules can be imported without a real environment."""

import os
import sys
from pathlib import Path

for key, value in {
    "AWS_REGION": "us-west-2",
    "DYNAMODB_TABLE": "prominder-test",
    "COGNITO_USER_POOL_ID": "us-west-2_TESTPOOL",
    "COGNITO_CLIENT_ID": "testclient",
    # Never reach a real AWS account from the tests (moto intercepts the calls in test_dynamo_store.py).
    "AWS_ACCESS_KEY_ID": "testing",
    "AWS_SECRET_ACCESS_KEY": "testing",
    "OWNER_USER_ID": "00000000-0000-0000-0000-000000000000",
    "CRON_SECRET": "cron",
    "ACTION_SECRET": "action",
    "OPENROUTER_API_KEY": "sk-test",
    "OPENROUTER_MODELS": "test/model",
    "VAPID_PRIVATE_KEY": "x",
    "VAPID_PUBLIC_KEY": "x",
    "VAPID_SUBJECT": "mailto:test@example.com",
    "WEB_ORIGIN": "https://web.example",
    "API_URL": "https://api.example",
}.items():
    os.environ.setdefault(key, value)

sys.path.insert(0, str(Path(__file__).parent))  # so tests can `import fakes`
