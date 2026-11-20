"""
The Lambda function's settings, all from its environment variables (scripts/aws_lambda_setup.py sets them).

The behaviour settings (times, quiet hours, Shabbat, limits) live on the phone now: ⚙ Settings there, backed up as
rows of the `settings` table. The server only needs what the phone mustn't hold (the keys) and its own limits.
"""

import os
from dataclasses import dataclass, field
from zoneinfo import ZoneInfo


def _req(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def _opt(name: str, default: str) -> str:
    return os.environ.get(name, "").strip() or default


def aws_region() -> str:
    """Also used without the full settings (db.py, the setup script). Lambda sets AWS_REGION itself."""
    return _opt("AWS_REGION", "us-west-2")


def dynamodb_table() -> str:
    return _opt("DYNAMODB_TABLE", "prominder")


REASONING_EFFORTS = ("minimal", "low", "medium", "high")


def _reasoning_effort(value: str) -> str:
    value = value.lower()
    if value and value not in REASONING_EFFORTS:
        # Fail at startup: a bad value would otherwise make every LLM call fail quietly.
        raise RuntimeError(f"LLM_REASONING_EFFORT must be empty or one of {', '.join(REASONING_EFFORTS)}")
    return value


@dataclass(frozen=True)
class Settings:
    # --- AWS. Credentials come from the function's role (boto3 finds them). ---
    aws_region: str = field(default_factory=aws_region)
    dynamodb_table: str = field(default_factory=dynamodb_table)

    # --- Google sign-in. Both empty: nobody can log in. ---
    # The OAuth client id of type "Web application" in Google Cloud: the app asks Google for a token
    # addressed to it, and only tokens addressed to it are accepted.
    google_client_id: str = field(default_factory=lambda: _opt("GOOGLE_CLIENT_ID", ""))
    # The one Google account allowed in.
    owner_google_email: str = field(default_factory=lambda: _opt("OWNER_GOOGLE_EMAIL", "").lower())
    # Signs the app's own login token (auth.py). Changing it logs the phone out.
    action_secret: str = field(default_factory=lambda: _req("ACTION_SECRET"))

    # --- LLM (OpenRouter). Empty: /llm says the assistant isn't set up (llm_proxy.configured). ---
    openrouter_api_key: str = field(default_factory=lambda: _opt("OPENROUTER_API_KEY", ""))
    # Comma-separated, in priority order; OpenRouter falls back to the next on errors.
    openrouter_models: list[str] = field(
        default_factory=lambda: [m.strip() for m in _opt("OPENROUTER_MODELS", "").split(",") if m.strip()]
    )
    # The day the daily LLM cap counts in.
    tz: ZoneInfo = field(default_factory=lambda: ZoneInfo(_opt("TIMEZONE", "Asia/Jerusalem")))
    llm_timeout_s: float = field(default_factory=lambda: float(_opt("LLM_TIMEOUT_S", "25")))
    llm_max_tokens: int = field(default_factory=lambda: int(_opt("LLM_MAX_TOKENS", "500")))
    # Thinking effort for reasoning models (OpenRouter `reasoning.effort`). Empty = provider default.
    llm_reasoning_effort: str = field(default_factory=lambda: _reasoning_effort(_opt("LLM_REASONING_EFFORT", "")))


_settings: Settings | None = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings
