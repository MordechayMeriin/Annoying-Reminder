"""
Behaviour settings the owner changes in the app (⚙ Settings) or by saying so in the chat (the
update_setting tool), stored in the app_settings table.

Only keys listed in config.SPECS are accepted; values are validated and stored as canonical text.
The in-memory copy (config.apply_overrides) is refreshed at startup, on every /tick and after a save,
so a change applies from the next tick at the latest (single Render instance).
"""

import logging
from datetime import datetime
from typing import Any

from . import config, db, preferences
from .jewish_calendar import current_period, next_period, rest_config

log = logging.getLogger("settings")


def load() -> None:
    """Read the stored overrides into memory. Keeps the previous ones if the database is unreachable."""
    try:
        rows = stored()
    except Exception:
        log.exception("Could not load settings; keeping the current ones")
        return
    errors = config.apply_overrides({k: v for k, v in rows.items() if k in config.SPECS})
    for key, msg in errors.items():
        log.warning("Ignoring stored setting %s: %s", key, msg)


def stored() -> dict[str, str]:
    """The saved overrides, by key."""
    return {r["sk"]: r["value"] for r in db.query("setting")}


def write(to_set: dict[str, str], to_reset: list[str]) -> None:
    """Save and remove overrides, all or nothing."""
    now = db.ts(db.now_utc())
    db.transact(
        puts=[db.to_attr({"pk": "setting", "sk": k, "value": v, "updated_at": now}) for k, v in to_set.items()],
        deletes=[("setting", k) for k in to_reset])


def save(values: dict[str, str | None]) -> dict[str, str]:
    """
    Save settings: a text value sets it, None resets it to the default.
    Returns errors by key; nothing is saved if there are any.
    """
    to_set, errors = config.validate({k: v for k, v in values.items() if v is not None})
    errors.update({k: "unknown setting" for k, v in values.items() if v is None and k not in config.SPECS})
    if errors:
        return errors
    to_reset = [k for k, v in values.items() if v is None]
    write(to_set, to_reset)
    load()
    return {}


def describe_fields() -> list[dict[str, Any]]:
    """Every editable setting with its current value (canonical text) and default."""
    current = config.overrides()
    out = []
    for spec in config.SPECS.values():
        default = config.default_raw(spec)
        out.append({
            "key": spec.key, "kind": spec.kind, "label": spec.label, "group": spec.group,
            "help": spec.help, "min": spec.min, "max": spec.max,
            "options": [{"value": v, "label": label} for v, label in spec.options] or None,
            "value": current.get(spec.key, default), "default": default,
            "overridden": spec.key in current,
        })
    return out


def describe(now: datetime) -> dict[str, Any]:
    """
    Everything the settings screen needs: fields with current values, the next quiet time and
    whether Pushover is set up (its keys are env-only).
    """
    out = describe_fields()

    rest = None
    cfg = rest_config(config.get_settings())
    if cfg is not None:
        tz = cfg.tz
        p = next_period(now, cfg)
        if p is not None:
            rest = {"name": p.name, "start": p.start.astimezone(tz).isoformat(timespec="minutes"),
                    "end": p.end.astimezone(tz).isoformat(timespec="minutes"),
                    "active": current_period(now, cfg) is not None}
    s = config.get_settings()
    return {"fields": out, "rest": rest, "pushover": {"configured": s.pushover_enabled},
            "browserPush": {"available": s.browser_push_available},
            "preferences": preferences.to_public()}
