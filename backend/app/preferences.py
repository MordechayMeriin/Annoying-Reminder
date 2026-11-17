"""
Preferences the owner states in chat that aren't settings: tone, length, topics to avoid
("shorter messages please"). F12 of the brief.

The agent saves them with the remember_preference tool; they're injected into every prompt
(prompting.render, the {preferences} slot) and listed read-only under ⚙, where each can be deleted.
Like the settings, an in-memory copy is refreshed at startup, on every /tick and after a change, so
writing a prompt never needs a database round trip.
"""

import logging
from typing import Any

from . import db
from .db import pad

log = logging.getLogger("preferences")

MAX_ITEMS = 20
MAX_CHARS = 200

_cache: list[dict[str, Any]] = []

PREFERENCE = db.Entity({"id": "int", "text": "str", "created_at": "dt"},
                       lambda p: {"pk": "pref", "sk": pad(p["id"])})


def load() -> None:
    """Read the stored preferences into memory. Keeps the previous ones if the database is unreachable."""
    global _cache
    try:
        _cache = db.query_rows(PREFERENCE, "pref")
    except Exception:
        log.exception("Could not load preferences; keeping the current ones")


def items() -> list[dict[str, Any]]:
    return list(_cache)


def add(text: str) -> dict[str, Any]:
    """Save one preference (one short line). ValueError when it's empty or the list is full."""
    text = " ".join((text or "").split())[:MAX_CHARS]
    if not text:
        raise ValueError("The preference is empty.")
    for row in items():
        if row["text"].lower() == text.lower():
            return row  # already known
    if len(items()) >= MAX_ITEMS:
        raise ValueError(f"There are already {MAX_ITEMS} saved preferences. The user can delete some under ⚙ Settings.")
    row = {"id": db.next_id("preference"), "text": text, "created_at": db.now_utc()}
    db.put(PREFERENCE, row, new=True)
    load()
    return row


def delete(pref_id: int) -> bool:
    deleted = db.delete("pref", pad(pref_id))
    load()
    return deleted


def prompt_text() -> str:
    """The block for the prompts' {preferences} slot; empty when there are none."""
    rows = items()
    if not rows:
        return ""
    return ("# What the user asked of you (always follow these)\n"
            + "\n".join(f"- {r['text']}" for r in rows))


def to_public() -> list[dict[str, Any]]:
    return [{"id": r["id"], "text": r["text"]} for r in items()]
