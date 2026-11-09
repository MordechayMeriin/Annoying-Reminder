"""All settings come from environment variables (set them in Render → Environment)."""

import os
from dataclasses import dataclass, field
from datetime import time
from zoneinfo import ZoneInfo


def _req(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def _opt(name: str, default: str) -> str:
    return os.environ.get(name, "").strip() or default


def _time(value: str) -> time:
    hh, mm = value.split(":")
    return time(int(hh), int(mm))


def _times(value: str) -> list[time]:
    return [_time(v.strip()) for v in value.split(",") if v.strip()]


def _time_or_off(value: str) -> time | None:
    """A time, or None when the value is "off" (the feature is disabled)."""
    return None if value.lower() in ("off", "none", "no", "false", "0") else _time(value)


_WEEKDAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")


def _weekday(value: str) -> int:
    """'sun' / 'Sunday' → Python weekday (Mon=0 … Sun=6)."""
    key = value.strip().lower()[:3]
    if key not in _WEEKDAYS:
        raise RuntimeError(f"Unknown weekday: {value!r} (use sun, mon, …)")
    return _WEEKDAYS.index(key)


@dataclass(frozen=True)
class Settings:
    # --- Database (Supabase Postgres, use the *pooler* connection string) ---
    database_url: str = field(default_factory=lambda: _req("DATABASE_URL"))

    # --- Auth (Supabase Auth) ---
    supabase_url: str = field(default_factory=lambda: _req("SUPABASE_URL").rstrip("/"))
    supabase_anon_key: str = field(default_factory=lambda: _req("SUPABASE_ANON_KEY"))
    owner_user_id: str = field(default_factory=lambda: _req("OWNER_USER_ID"))

    # --- Secrets ---
    cron_secret: str = field(default_factory=lambda: _req("CRON_SECRET"))
    action_secret: str = field(default_factory=lambda: _req("ACTION_SECRET"))

    # --- LLM (OpenRouter) ---
    openrouter_api_key: str = field(default_factory=lambda: _req("OPENROUTER_API_KEY"))
    # Comma-separated, in priority order; OpenRouter falls back to the next on errors.
    openrouter_models: list[str] = field(
        default_factory=lambda: [m.strip() for m in _req("OPENROUTER_MODELS").split(",") if m.strip()]
    )

    # --- Web push (VAPID) ---
    vapid_private_key: str = field(default_factory=lambda: _req("VAPID_PRIVATE_KEY"))
    vapid_public_key: str = field(default_factory=lambda: _req("VAPID_PUBLIC_KEY"))
    vapid_subject: str = field(default_factory=lambda: _req("VAPID_SUBJECT"))  # mailto:you@example.com

    # --- Pushover (optional backup channel for critical reminders) ---
    pushover_token: str = field(default_factory=lambda: _opt("PUSHOVER_TOKEN", ""))
    pushover_user: str = field(default_factory=lambda: _opt("PUSHOVER_USER", ""))

    # --- URLs ---
    web_origin: str = field(default_factory=lambda: _req("WEB_ORIGIN").rstrip("/"))   # the static site URL
    api_url: str = field(default_factory=lambda: _req("API_URL").rstrip("/"))         # this service's URL

    # --- Behaviour ---
    tz: ZoneInfo = field(default_factory=lambda: ZoneInfo(_opt("TIMEZONE", "Asia/Jerusalem")))
    morning_time: time = field(default_factory=lambda: _time(_opt("MORNING_TIME", "07:30")))
    checkin_times: list[time] = field(default_factory=lambda: _times(_opt("CHECKIN_TIMES", "13:00,17:00")))
    evening_time: time = field(default_factory=lambda: _time(_opt("EVENING_TIME", "21:30")))
    quiet_start: time = field(default_factory=lambda: _time(_opt("QUIET_START", "23:00")))
    quiet_end: time = field(default_factory=lambda: _time(_opt("QUIET_END", "07:00")))
    nag_interval_min: int = field(default_factory=lambda: int(_opt("NAG_INTERVAL_MIN", "30")))
    max_nags: int = field(default_factory=lambda: int(_opt("MAX_NAGS", "4")))
    meeting_lead_min: int = field(default_factory=lambda: int(_opt("MEETING_LEAD_MIN", "15")))
    # Extra meeting reminders: the week's meetings at the start of the week, and tomorrow's
    # meetings the evening before. Set a time to "off" to disable it.
    week_start_day: int = field(default_factory=lambda: _weekday(_opt("WEEK_START_DAY", "sun")))
    week_meetings_time: time | None = field(
        default_factory=lambda: _time_or_off(_opt("WEEK_MEETINGS_TIME", "08:00")))
    meetings_eve_time: time | None = field(
        default_factory=lambda: _time_or_off(_opt("MEETINGS_EVE_TIME", "20:00")))

    # --- Proactive-contact guardrails (enforced in code, before any LLM call) ---
    min_gap_min: int = field(default_factory=lambda: int(_opt("MIN_GAP_MIN", "20")))
    max_proactive_per_day: int = field(default_factory=lambda: int(_opt("MAX_PROACTIVE_PER_DAY", "20")))
    max_per_task_per_day: int = field(default_factory=lambda: int(_opt("MAX_PER_TASK_PER_DAY", "4")))
    followup_min_min: int = field(default_factory=lambda: int(_opt("FOLLOWUP_MIN_MIN", "10")))
    followup_max_days: int = field(default_factory=lambda: int(_opt("FOLLOWUP_MAX_DAYS", "7")))

    # --- Conversation ---
    # The chat "day" starts here: the LLM only sees messages since the last reset.
    day_reset_time: time = field(default_factory=lambda: _time(_opt("DAY_RESET_TIME", "04:00")))
    # A reply this soon after a check-in treats that check-in's tasks as the likely topic.
    reply_link_hours: float = field(default_factory=lambda: float(_opt("REPLY_LINK_HOURS", "3")))

    # --- Spend guards ---
    chat_per_minute: int = field(default_factory=lambda: int(_opt("CHAT_PER_MINUTE", "15")))
    llm_calls_per_day: int = field(default_factory=lambda: int(_opt("LLM_CALLS_PER_DAY", "300")))
    llm_timeout_s: float = field(default_factory=lambda: float(_opt("LLM_TIMEOUT_S", "25")))
    llm_max_tokens: int = field(default_factory=lambda: int(_opt("LLM_MAX_TOKENS", "500")))

    @property
    def pushover_enabled(self) -> bool:
        return bool(self.pushover_token and self.pushover_user)


_settings: Settings | None = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings
