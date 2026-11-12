"""
Settings.

* Secrets and deployment values (keys, URLs, spend guards) come only from environment variables
  (Render → Environment).
* Behaviour settings (times, delays, limits, Shabbat) are listed in SPECS. Their defaults can come
  from environment variables, and the owner can override them in the app (⚙ Settings). Overrides
  live in the app_settings table; user_settings.py loads them into memory with apply_overrides().
"""

import os
from dataclasses import dataclass, field, fields, replace
from datetime import time
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


def _req(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def _opt(name: str, default: str) -> str:
    return os.environ.get(name, "").strip() or default


# ================================================================ parsers (raise ValueError)

def _time(value: str) -> time:
    try:
        hh, mm = value.strip().split(":")
        return time(int(hh), int(mm))
    except (ValueError, TypeError):
        raise ValueError("use HH:MM, e.g. 07:30") from None


def _times(value: str) -> list[time]:
    return sorted(_time(v) for v in value.split(",") if v.strip())


def _time_or_off(value: str) -> time | None:
    """A time, or None when the value is "off" (the feature is disabled)."""
    return None if value.strip().lower() in ("off", "none", "no", "false", "0", "") else _time(value)


_WEEKDAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")


def _weekday(value: str) -> int:
    """'sun' / 'Sunday' → Python weekday (Mon=0 … Sun=6)."""
    key = value.strip().lower()[:3]
    if key not in _WEEKDAYS:
        raise ValueError("use sun, mon, tue, wed, thu, fri or sat")
    return _WEEKDAYS.index(key)


def _bool(value: str) -> bool:
    v = value.strip().lower()
    if v in ("1", "true", "yes", "on"):
        return True
    if v in ("0", "false", "no", "off"):
        return False
    raise ValueError("use true or false")


def _zone(value: str) -> ZoneInfo:
    try:
        return ZoneInfo(value.strip())
    except (ZoneInfoNotFoundError, ValueError):
        raise ValueError("unknown time zone (e.g. Asia/Jerusalem)") from None


# ================================================================ editable settings

@dataclass(frozen=True)
class Spec:
    key: str             # API / database key; the env var is KEY.upper()
    kind: str            # time | times | time_off | int | float | bool | weekday | timezone | choice
    default: str         # built-in default, as text
    label: str
    group: str
    help: str = ""
    min: float | None = None
    max: float | None = None
    attr: str = ""       # Settings attribute when it differs from key
    options: tuple[tuple[str, str], ...] = ()   # choice: (value, label)

    @property
    def env(self) -> str:
        return self.key.upper()

    @property
    def setting_attr(self) -> str:
        return self.attr or self.key

    def parse(self, raw: str) -> Any:
        raw = str(raw).strip()
        if self.kind == "time":
            return _time(raw)
        if self.kind == "times":
            return _times(raw)
        if self.kind == "time_off":
            return _time_or_off(raw)
        if self.kind == "weekday":
            return _weekday(raw)
        if self.kind == "bool":
            return _bool(raw)
        if self.kind == "timezone":
            return _zone(raw)
        if self.kind == "choice":
            value = raw.lower()
            if value not in {v for v, _ in self.options}:
                raise ValueError("choose one of: " + ", ".join(v for v, _ in self.options))
            return value
        try:
            value = int(raw) if self.kind == "int" else float(raw)
        except ValueError:
            raise ValueError("must be a whole number" if self.kind == "int" else "must be a number") from None
        if self.min is not None and value < self.min:
            raise ValueError(f"must be at least {self.min:g}")
        if self.max is not None and value > self.max:
            raise ValueError(f"must be at most {self.max:g}")
        return value

    def canonical(self, raw: str) -> str:
        """Validated, normalised text (what gets stored). Raises ValueError."""
        v = self.parse(raw)
        if self.kind == "time":
            return f"{v:%H:%M}"
        if self.kind == "times":
            return ", ".join(f"{t:%H:%M}" for t in v)
        if self.kind == "time_off":
            return "off" if v is None else f"{v:%H:%M}"
        if self.kind == "weekday":
            return _WEEKDAYS[v]
        if self.kind == "bool":
            return "true" if v else "false"
        if self.kind == "timezone":
            return v.key
        if self.kind == "choice":
            return v
        return f"{v:g}" if self.kind == "float" else str(v)


_G_DAY, _G_QUIET, _G_SHABBAT, _G_MEET, _G_NAG, _G_PUSHOVER, _G_APP = (
    "Daily messages", "Quiet hours", "Shabbat & holidays", "Meetings", "Check-ins & limits", "Notifications",
    "App")

PUSHOVER_MODES = (
    ("always", "Every message"),
    ("only", "Instead of browser"),
    ("backup", "As a backup"),
    ("off", "Never"),
)
PUSHOVER_ALARM_FOR = (
    ("critical", "Critical tasks"),
    ("important", "Important + critical"),
    ("all", "Every task"),
    ("off", "Nothing"),
)
# Pushover's built-in sounds (https://pushover.net/api#sounds). "default" = what the Pushover app uses.
PUSHOVER_SOUNDS = (
    ("default", "Pushover app default"),
    ("pushover", "Pushover"), ("bike", "Bike"), ("bugle", "Bugle"), ("cashregister", "Cash register"),
    ("classical", "Classical"), ("cosmic", "Cosmic"), ("falling", "Falling"), ("gamelan", "Gamelan"),
    ("incoming", "Incoming"), ("intermission", "Intermission"), ("magic", "Magic"),
    ("mechanical", "Mechanical"), ("pianobar", "Piano bar"), ("siren", "Siren"),
    ("spacealarm", "Space alarm"), ("tugboat", "Tug boat"),
    ("alien", "Alien alarm (long)"), ("climb", "Climb (long)"), ("persistent", "Persistent (long)"),
    ("echo", "Pushover echo (long)"), ("updown", "Up down (long)"),
    ("vibrate", "Vibrate only"), ("none", "Silent"),
)

SPECS: dict[str, Spec] = {s.key: s for s in [
    Spec("timezone", "timezone", "Asia/Jerusalem", "Time zone", _G_DAY, attr="tz"),
    Spec("morning_time", "time", "07:30", "Morning plan", _G_DAY),
    Spec("checkin_times", "times", "13:00, 17:00", "Mid-day check-ins", _G_DAY,
         "Comma-separated times. Empty = none."),
    Spec("evening_time", "time", "21:30", "Evening review", _G_DAY),
    Spec("day_reset_time", "time", "04:00", "New chat day starts at", _G_DAY,
         "The assistant only sees today's chat, from this time."),

    Spec("quiet_start", "time", "23:00", "Quiet from", _G_QUIET, "No messages at all in between."),
    Spec("quiet_end", "time", "07:00", "Quiet until", _G_QUIET),

    Spec("shabbat_mode", "bool", "true", "Don't disturb on Shabbat & holidays", _G_SHABBAT,
         "Nothing is sent from candle lighting to havdalah (plus the margins below). "
         "Shabbat and yom tov only."),
    Spec("latitude", "float", "32.0956", "Latitude", _G_SHABBAT, "Your location, for sunset times.", -90, 90),
    Spec("longitude", "float", "34.9566", "Longitude", _G_SHABBAT, "Defaults are Rosh HaAyin.", -180, 180),
    Spec("altitude", "float", "60", "Altitude (m)", _G_SHABBAT, min=-500, max=9000),
    Spec("diaspora", "bool", "false", "Outside Israel (two-day holidays)", _G_SHABBAT),
    Spec("candle_lighting_min", "int", "20", "Candle lighting (min before sunset)", _G_SHABBAT,
         min=0, max=90),
    Spec("havdalah_min", "int", "0", "Havdalah (min after sunset)", _G_SHABBAT,
         "0 = nightfall (three stars). 72 = Rabbeinu Tam.", 0, 120),
    Spec("shabbat_before_min", "int", "60", "Quiet starts before candle lighting (min)", _G_SHABBAT,
         min=0, max=600),
    Spec("shabbat_after_min", "int", "60", "Quiet continues after havdalah (min)", _G_SHABBAT,
         min=0, max=600),

    Spec("meeting_lead_min", "int", "15", "Reminder before a meeting (min)", _G_MEET,
         "Plus one at the start time.", 0, 24 * 60),
    Spec("week_start_day", "weekday", "sun", "First day of the week", _G_MEET),
    Spec("week_meetings_time", "time_off", "08:00", "This week's meetings at", _G_MEET,
         "On the first day of the week. Off = don't send."),
    Spec("meetings_eve_time", "time_off", "20:00", "Tomorrow's meetings at", _G_MEET,
         "Every evening. Off = don't send."),

    Spec("nag_interval_min", "int", "30", "Follow-up interval (min)", _G_NAG,
         "Default time between check-ins; faster for important tasks.", 5, 24 * 60),
    Spec("max_nags", "int", "4", "Stop after this many check-ins", _G_NAG, min=1, max=50),
    Spec("min_gap_min", "int", "20", "Minimum gap between messages (min)", _G_NAG,
         "Meetings are exempt.", 0, 24 * 60),
    Spec("max_proactive_per_day", "int", "20", "Messages per day (max)", _G_NAG, min=1, max=200),
    Spec("max_per_task_per_day", "int", "4", "Check-ins per task per day (max)", _G_NAG,
         "Reminders you asked for don't count.", 1, 50),
    Spec("followup_min_min", "int", "10", "Earliest follow-up the assistant may pick (min)", _G_NAG,
         min=1, max=24 * 60),
    Spec("followup_max_days", "int", "7", "Latest follow-up the assistant may pick (days)", _G_NAG,
         min=1, max=60),
    Spec("reply_link_hours", "float", "3", "A reply refers to the last check-in for (hours)", _G_NAG,
         min=0, max=48),

    Spec("browser_push", "bool", "true", "Browser notifications", _G_PUSHOVER,
         "Off: everything goes to Pushover, whatever \"Send to Pushover\" says. Browser notifications "
         "are still used while Pushover isn't set up, so reminders are never lost."),
    Spec("pushover_mode", "choice", "always", "Send to Pushover", _G_PUSHOVER,
         "Every message: Pushover and browser push. Instead of browser: browser push only if Pushover "
         "fails. As a backup: only when no browser got it, and alarms.", options=PUSHOVER_MODES),
    Spec("pushover_sound", "choice", "default", "Sound: normal", _G_PUSHOVER,
         "Daily messages and first check-ins.", options=PUSHOVER_SOUNDS),
    Spec("pushover_sound_urgent", "choice", "persistent", "Sound: urgent", _G_PUSHOVER,
         "Meetings, reminders you asked for, important tasks, ignored check-ins, alarms.",
         options=PUSHOVER_SOUNDS),
    Spec("pushover_urgent_after", "int", "1", "Urgent after this many ignored check-ins", _G_PUSHOVER,
         "Check-ins about a task you keep ignoring get louder.", 0, 20),
    Spec("pushover_alarm_for", "choice", "important", "Alarm for", _G_PUSHOVER,
         "An alarm repeats until you acknowledge it in Pushover, write in the chat or act on a task.",
         options=PUSHOVER_ALARM_FOR),
    Spec("pushover_alarm_after", "int", "2", "Alarm after this many ignored check-ins", _G_PUSHOVER,
         "If check-ins stop sooner (see above), the last one is the alarm.", 0, 50),
    Spec("pushover_alarm_retry_min", "int", "2", "Alarm repeats every (min)", _G_PUSHOVER,
         "For up to an hour, and never into quiet hours or Shabbat.", 1, 30),

    Spec("show_model", "bool", "false", "Show which AI model replied", _G_APP,
         "A small grey line under the title, for the latest message only. Empty when code wrote it "
         "(a template or a button's answer)."),
]}


def default_raw(spec: Spec) -> str:
    """The default for a setting, as canonical text: the env var if set (and valid), else built-in."""
    raw = os.environ.get(spec.env, "").strip()
    if raw:
        try:
            return spec.canonical(raw)
        except ValueError:
            pass
    return spec.canonical(spec.default)


def _env(key: str) -> Any:
    spec = SPECS[key]
    raw = os.environ.get(spec.env, "").strip() or spec.default
    try:
        return spec.parse(raw)
    except ValueError as e:
        raise RuntimeError(f"Invalid environment variable {spec.env}={raw!r}: {e}") from None


REASONING_EFFORTS = ("minimal", "low", "medium", "high")


def _reasoning_effort(value: str) -> str:
    value = value.lower()
    if value and value not in REASONING_EFFORTS:
        # Fail at startup: a bad value would otherwise make every LLM call fail quietly.
        raise RuntimeError(f"LLM_REASONING_EFFORT must be empty or one of {', '.join(REASONING_EFFORTS)}")
    return value


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

    # --- Web push (VAPID; optional: without the keys there are no browser notifications) ---
    vapid_private_key: str = field(default_factory=lambda: _opt("VAPID_PRIVATE_KEY", ""))
    vapid_public_key: str = field(default_factory=lambda: _opt("VAPID_PUBLIC_KEY", ""))
    vapid_subject: str = field(default_factory=lambda: _opt("VAPID_SUBJECT", ""))  # mailto:you@example.com

    # --- Pushover (optional; strongly recommended: priorities, loud sounds, repeating alarms) ---
    pushover_token: str = field(default_factory=lambda: _opt("PUSHOVER_TOKEN", ""))
    pushover_user: str = field(default_factory=lambda: _opt("PUSHOVER_USER", ""))

    # --- URLs ---
    web_origin: str = field(default_factory=lambda: _req("WEB_ORIGIN").rstrip("/"))   # the static site URL
    api_url: str = field(default_factory=lambda: _req("API_URL").rstrip("/"))         # this service's URL

    # --- Behaviour (editable in the app; see SPECS) ---
    tz: ZoneInfo = field(default_factory=lambda: _env("timezone"))
    morning_time: time = field(default_factory=lambda: _env("morning_time"))
    checkin_times: list[time] = field(default_factory=lambda: _env("checkin_times"))
    evening_time: time = field(default_factory=lambda: _env("evening_time"))
    quiet_start: time = field(default_factory=lambda: _env("quiet_start"))
    quiet_end: time = field(default_factory=lambda: _env("quiet_end"))
    nag_interval_min: int = field(default_factory=lambda: _env("nag_interval_min"))
    max_nags: int = field(default_factory=lambda: _env("max_nags"))
    meeting_lead_min: int = field(default_factory=lambda: _env("meeting_lead_min"))
    # Extra meeting reminders: the week's meetings at the start of the week, and tomorrow's
    # meetings the evening before. None = disabled.
    week_start_day: int = field(default_factory=lambda: _env("week_start_day"))
    week_meetings_time: time | None = field(default_factory=lambda: _env("week_meetings_time"))
    meetings_eve_time: time | None = field(default_factory=lambda: _env("meetings_eve_time"))

    # Shabbat & holidays as don't-disturb time (jewish_calendar.py)
    shabbat_mode: bool = field(default_factory=lambda: _env("shabbat_mode"))
    latitude: float = field(default_factory=lambda: _env("latitude"))
    longitude: float = field(default_factory=lambda: _env("longitude"))
    altitude: float = field(default_factory=lambda: _env("altitude"))
    diaspora: bool = field(default_factory=lambda: _env("diaspora"))
    candle_lighting_min: int = field(default_factory=lambda: _env("candle_lighting_min"))
    havdalah_min: int = field(default_factory=lambda: _env("havdalah_min"))
    shabbat_before_min: int = field(default_factory=lambda: _env("shabbat_before_min"))
    shabbat_after_min: int = field(default_factory=lambda: _env("shabbat_after_min"))

    # Proactive-contact guardrails (enforced in code, before any LLM call)
    min_gap_min: int = field(default_factory=lambda: _env("min_gap_min"))
    max_proactive_per_day: int = field(default_factory=lambda: _env("max_proactive_per_day"))
    max_per_task_per_day: int = field(default_factory=lambda: _env("max_per_task_per_day"))
    followup_min_min: int = field(default_factory=lambda: _env("followup_min_min"))
    followup_max_days: int = field(default_factory=lambda: _env("followup_max_days"))

    # Conversation
    # The chat "day" starts here: the LLM only sees messages since the last reset.
    day_reset_time: time = field(default_factory=lambda: _env("day_reset_time"))
    # A reply this soon after a check-in treats that check-in's tasks as the likely topic.
    reply_link_hours: float = field(default_factory=lambda: _env("reply_link_hours"))
    # The app shows which model wrote the latest message (messages carry it only when this is on).
    show_model: bool = field(default_factory=lambda: _env("show_model"))

    # Browser push (only when the VAPID keys are set) and Pushover (only when PUSHOVER_TOKEN and
    # PUSHOVER_USER are set); notify.py combines them.
    browser_push: bool = field(default_factory=lambda: _env("browser_push"))
    pushover_mode: str = field(default_factory=lambda: _env("pushover_mode"))
    pushover_sound: str = field(default_factory=lambda: _env("pushover_sound"))
    pushover_sound_urgent: str = field(default_factory=lambda: _env("pushover_sound_urgent"))
    pushover_urgent_after: int = field(default_factory=lambda: _env("pushover_urgent_after"))
    pushover_alarm_for: str = field(default_factory=lambda: _env("pushover_alarm_for"))
    pushover_alarm_after: int = field(default_factory=lambda: _env("pushover_alarm_after"))
    pushover_alarm_retry_min: int = field(default_factory=lambda: _env("pushover_alarm_retry_min"))

    # --- Spend guards (env only: they cap OpenRouter spend even if the login were compromised) ---
    chat_per_minute: int = field(default_factory=lambda: int(_opt("CHAT_PER_MINUTE", "15")))
    llm_calls_per_day: int = field(default_factory=lambda: int(_opt("LLM_CALLS_PER_DAY", "300")))
    llm_timeout_s: float = field(default_factory=lambda: float(_opt("LLM_TIMEOUT_S", "25")))
    # A whole chat turn (several model calls and tools) stops after this, so "typing…" never hangs.
    chat_turn_timeout_s: float = field(default_factory=lambda: float(_opt("CHAT_TURN_TIMEOUT_S", "60")))
    llm_max_tokens: int = field(default_factory=lambda: int(_opt("LLM_MAX_TOKENS", "500")))
    # Thinking effort for reasoning models (OpenRouter `reasoning.effort`). Empty = provider default.
    llm_reasoning_effort: str = field(default_factory=lambda: _reasoning_effort(_opt("LLM_REASONING_EFFORT", "")))

    @property
    def pushover_enabled(self) -> bool:
        return bool(self.pushover_token and self.pushover_user)

    @property
    def browser_push_available(self) -> bool:
        return bool(self.vapid_private_key and self.vapid_public_key and self.vapid_subject)


# ================================================================ access

_base: Settings | None = None        # from the environment
_overrides: dict[str, str] = {}      # canonical text, from the app (app_settings table)
_merged: Settings | None = None      # what get_settings() returns


def get_settings() -> Settings:
    global _base, _merged
    if _merged is None:
        if _base is None:
            _base = Settings()
        _merged = replace(_base, **{SPECS[k].setting_attr: SPECS[k].parse(v) for k, v in _overrides.items()})
    return _merged


def overrides() -> dict[str, str]:
    return dict(_overrides)


def validate(values: dict[str, str]) -> tuple[dict[str, str], dict[str, str]]:
    """(canonical values, errors by key) for setting values given as text."""
    ok, errors = {}, {}
    for key, raw in values.items():
        spec = SPECS.get(key)
        if spec is None:
            errors[key] = "unknown setting"
            continue
        try:
            ok[key] = spec.canonical(raw)
        except ValueError as e:
            errors[key] = str(e)
    return ok, errors


def apply_overrides(values: dict[str, str]) -> dict[str, str]:
    """Replace the in-memory overrides. Invalid / unknown ones are skipped and returned as errors."""
    global _overrides, _merged
    ok, errors = validate(values)
    _overrides, _merged = ok, None
    return errors


def _check_specs() -> None:
    names = {f.name for f in fields(Settings)}
    missing = [s.key for s in SPECS.values() if s.setting_attr not in names]
    assert not missing, f"SPECS without a Settings field: {missing}"


_check_specs()
