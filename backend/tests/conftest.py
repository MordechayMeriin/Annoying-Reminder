"""Dummy settings so app modules can be imported without a real environment."""

import os
import sys
from pathlib import Path

for key, value in {
    "DATABASE_URL": "postgresql://test/test",
    "SUPABASE_URL": "https://example.supabase.co",
    "SUPABASE_ANON_KEY": "anon",
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
