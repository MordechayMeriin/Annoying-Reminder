"""
Run a few simulated /tick calls end to end — real scheduler, guardrails, coach and agent — against an
in-memory database and a scripted LLM. No network, no Supabase, no OpenRouter.

    cd backend
    python scripts/simulate_tick.py            # LLM answers
    python scripts/simulate_tick.py --llm-down # OpenRouter unreachable → template fallback
"""

import asyncio
import os
import sys
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

BACKEND = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(BACKEND), str(BACKEND / "tests")]
import conftest  # noqa: E402,F401  (dummy env vars)

from fakes import FakeStore, ScriptedLLM  # noqa: E402

from app import agent, scheduler  # noqa: E402
from app.llm import LLMUnavailable  # noqa: E402

TZ = ZoneInfo("Asia/Jerusalem")
LLM_DOWN = "--llm-down" in sys.argv


def show(store: FakeStore, title: str) -> None:
    print(f"\n=== {title} ({store.now.astimezone(TZ):%a %H:%M}) ===")
    for m in store.messages[show.seen:]:
        about = f" about {m['task_ids']}" if m["task_ids"] else ""
        print(f"  [{m['kind']}] {m['role']}{about}: {m['content']}")
    for p in store.pushes[show.pushed:]:
        buttons = " +✓/⏰" if p["task_id"] else ""
        print(f"  PUSH '{p['title']}' → {p['url']}{buttons}: {p['body']}")
    show.seen, show.pushed = len(store.messages), len(store.pushes)


show.seen = show.pushed = 0


def setattr_(obj, name, value):
    setattr(obj, name, value)


def tick() -> None:
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))


def main() -> None:
    store = FakeStore(datetime(2026, 11, 9, 10, 0, tzinfo=TZ))
    store.install(setattr_)

    down = LLMUnavailable("OpenRouter unreachable (simulated)")
    llm = ScriptedLLM([down] * 10 if LLM_DOWN else [
        # tick 1: two tasks due together → one message, with a follow-up for the report
        {"tool": "schedule_followup", "args": {"task_id": 2, "minutes_from_now": 90, "reason": "after lunch"}},
        "Invoice for Dana first — it's overdue. Then the report outline after lunch?",
        # chat reply
        {"tool": "log_interaction", "args": {"task_id": 1, "kind": "stuck", "note": "don't have the amount"}},
        {"tool": "add_subtask", "args": {"task_id": 1, "title": "Find the amount in Dana's email"}},
        "Start by finding the amount in Dana's email (5 min). Want me to check back in 30 minutes?",
        # tick 3: meeting reminder line
        "Anything to prepare?",
    ])
    llm.install(setattr_)

    store.add_task("Send the invoice to Dana", due_at=datetime(2026, 11, 9, 9, 0, tzinfo=TZ),
                   remind_at=datetime(2026, 11, 9, 9, 55, tzinfo=TZ), description="Client Dana, November work")
    store.add_task("Write the report outline", remind_at=datetime(2026, 11, 9, 10, 0, tzinfo=TZ))
    store.add_task("Team sync", kind="meeting", due_at=datetime(2026, 11, 9, 10, 45, tzinfo=TZ),
                   remind_at=datetime(2026, 11, 9, 10, 30, tzinfo=TZ))

    tick()
    show(store, "Tick 1: two tasks due → one combined message")

    store.advance(5)
    if not LLM_DOWN:
        r = asyncio.run(agent.handle_user_message("I'm stuck on the invoice, I don't have the amount"))
        show(store, f"Reply (linked to tasks {r.task_ids})")

    store.advance(10)
    tick()
    show(store, "Tick 2: nothing new is due, the gap protects you")

    store.advance(15)
    tick()
    show(store, "Tick 3: meeting reminder (skips the gap, time comes from code)")

    print("\nTasks:")
    for t in store.tasks.values():
        rem = t["remind_at"].astimezone(TZ).strftime("%H:%M") if t["remind_at"] else "-"
        print(f"  #{t['id']} {t['title']!r:34} next check-in {rem:5}  nags {t['nag_count']}  parent {t['parent_id']}")
    print(f"\nLLM calls made: {len(llm.requests)}  (fallback mode: {LLM_DOWN})")


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    main()
