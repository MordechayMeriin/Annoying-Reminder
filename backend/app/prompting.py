"""
Prompts live as editable Markdown files in app/prompts/ and are loaded once at startup.

Placeholders are written {like_this}. Only names you pass are replaced, so other braces are safe.
{coach} is always available: it inserts coach.md, the "how to interact" skill shared by every prompt.
Swapping coach.md for a richer skill changes the assistant's style everywhere without code changes.
"""

import re
from functools import lru_cache
from pathlib import Path

PROMPT_DIR = Path(__file__).parent / "prompts"
_PLACEHOLDER = re.compile(r"\{(\w+)\}")


@lru_cache(maxsize=None)
def load(name: str) -> str:
    return (PROMPT_DIR / f"{name}.md").read_text(encoding="utf-8").strip()


def render(name: str, **values: object) -> str:
    values.setdefault("coach", load("coach"))
    text = load(name)
    return _PLACEHOLDER.sub(lambda m: str(values[m.group(1)]) if m.group(1) in values else m.group(0), text)


def check_all() -> None:
    """Fail fast at startup if a prompt file is missing."""
    for name in ("system", "coach", "checkin", "daily_morning", "daily_checkin", "daily_evening"):
        load(name)
