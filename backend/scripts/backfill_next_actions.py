"""
One-off: give every open task without a next action its first step (and an estimate), with one LLM
call per task. Run it once after migration 006, where the API's env vars are set (Render → Shell):

    cd backend
    python scripts/backfill_next_actions.py --dry-run   # print what it would save
    python scripts/backfill_next_actions.py             # save it

Meetings are skipped, and so are subtasks (a step is its own next action). Safe to run again: it only
touches tasks that still have no next action. Each call counts toward LLM_CALLS_PER_DAY.
"""

import argparse
import asyncio
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import db, tasks  # noqa: E402
from app.llm import LLMUnavailable, chat_completion  # noqa: E402

PROMPT = """You help a person with ADHD start their tasks. For the task below, reply with JSON only:
{"next_action": "...", "estimate_min": N}
- next_action: the very first physical step, verb first, 15 minutes or less, in the task's language
  (Hebrew or English). For a task over 45 minutes, the first 15-minute chunk.
- estimate_min: your guess of the whole task in minutes."""


def parse(text: str) -> tuple[str, int | None] | None:
    """The model's JSON answer → (next_action, estimate_min), or None if unusable."""
    m = re.search(r"\{.*\}", text or "", re.S)
    if not m:
        return None
    try:
        obj = json.loads(m.group(0))
    except ValueError:
        return None
    step = str(obj.get("next_action") or "").strip()
    if not step:
        return None
    try:
        est = int(float(obj.get("estimate_min")))
        est = est if 1 <= est <= 10000 else None
    except (TypeError, ValueError):
        est = None
    return step[: tasks.NEXT_ACTION_MAX], est


def describe(t: dict) -> str:
    lines = [f"Task: {t['title']}"]
    if t.get("description"):
        lines.append(f"Details: {t['description']}")
    return "\n".join(lines)


async def run(dry_run: bool) -> None:
    todo = [t for t in tasks.list_tasks("open")
            if t["kind"] == "task" and not t["parent_id"] and not t.get("next_action")]
    print(f"{len(todo)} open task(s) without a next action")
    saved = 0
    for t in todo:
        try:
            msg = await chat_completion([{"role": "system", "content": PROMPT},
                                         {"role": "user", "content": describe(t)}], temperature=0.2)
        except LLMUnavailable as e:
            print(f"  stopping: the LLM is unavailable ({e})")
            break
        result = parse(msg.get("content") or "")
        if result is None:
            print(f"  #{t['id']} {t['title']!r}: no usable answer, skipped")
            continue
        step, est = result
        print(f"  #{t['id']} {t['title']!r} → {step}" + (f" (~{est} min)" if est else ""))
        if not dry_run:
            tasks.update(t["id"], next_action=step, **({"estimate_min": est} if est else {}))
            saved += 1
    print(f"Saved {saved}." if not dry_run else "Dry run: nothing saved.")


def main() -> None:
    ap = argparse.ArgumentParser(description="Fill in next actions for existing open tasks.")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    try:
        asyncio.run(run(a.dry_run))
    finally:
        db.close_pool()


if __name__ == "__main__":
    main()
