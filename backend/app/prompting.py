"""
Prompts live as editable Markdown files in app/prompts/ and are loaded once at startup.

Placeholders are written {like_this}. Only names you pass are replaced, so other braces are safe.
Always available:
  {core}      core.md: who the assistant is and the communication rules, in every prompt
  {playbooks} the situational playbooks code chose for this call (logic.select_playbooks), or nothing
  {chips}     the reply buttons this message gets, so a question and its buttons match
  {preferences} what the user asked of the assistant in chat (preferences.py), or nothing
The task frames (system.md, checkin.md, daily_*.md) say what this particular message is for.
"""

import re
from functools import lru_cache
from pathlib import Path
from typing import Iterable

from . import preferences
from .logic import PLAYBOOKS

PROMPT_DIR = Path(__file__).parent / "prompts"
_PLACEHOLDER = re.compile(r"\{(\w+)\}")


@lru_cache(maxsize=None)
def load(name: str) -> str:
    return (PROMPT_DIR / f"{name}.md").read_text(encoding="utf-8").strip()


def playbooks_text(names: Iterable[str]) -> str:
    return "\n\n".join(load(f"playbooks/{n}") for n in names)


def chips_text(chips: Iterable[dict]) -> str:
    labels = [c["label"] for c in chips]
    return " · ".join(labels) if labels else "(none)"


def render(name: str, playbooks: Iterable[str] = (), chips: Iterable[dict] = (), **values: object) -> str:
    values.setdefault("core", load("core"))
    values["playbooks"] = playbooks_text(playbooks)
    values.setdefault("chips", chips_text(chips))
    values.setdefault("preferences", preferences.prompt_text())
    text = load(name)
    out = _PLACEHOLDER.sub(lambda m: str(values[m.group(1)]) if m.group(1) in values else m.group(0), text)
    return re.sub(r"\n{3,}", "\n\n", out)  # an empty {playbooks} leaves no gap


def check_all() -> None:
    """Fail fast at startup if a prompt file is missing."""
    for name in ("system", "core", "checkin", "daily_morning", "daily_checkin", "daily_evening", "daily_reentry"):
        load(name)
    for name in PLAYBOOKS:
        load(f"playbooks/{name}")
