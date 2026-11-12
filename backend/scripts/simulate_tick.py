"""
Run a simulated morning end to end — real scheduler, guardrails, coach, agent and actions — against an
in-memory database and a scripted LLM. No network, no Supabase, no OpenRouter.

    cd backend
    python scripts/simulate_tick.py            # LLM answers
    python scripts/simulate_tick.py --llm-down # OpenRouter unreachable → templates and the code's picks

What it shows (Phase 1 of the ADHD-assistant brief):
  07:30  the morning plan sets Today's 3 (no reply needed), with ▶ Start the first one · Change it
  10:00  a check-in about a focus task, with reply buttons and ▶ Start / ✓ Done on the notification;
         an undated task outside Today's 3 stays quiet
  10:05  a reply about one task gets ▶ Start 5 min · ✓ Done (+ a new task gets its first step silently)
  10:10  ✓ Done tapped on a notification: no LLM call, an event in the chat
  10:30  a meeting reminder (skips the gap, time comes from code, no buttons)
"""

import asyncio
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

BACKEND = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(BACKEND), str(BACKEND / "tests")]
import conftest  # noqa: E402,F401  (dummy env vars)

from fakes import FakeStore, ScriptedLLM  # noqa: E402

from app import actions, agent, scheduler  # noqa: E402
from app.llm import LLMUnavailable  # noqa: E402

TZ = ZoneInfo("Asia/Jerusalem")
LLM_DOWN = "--llm-down" in sys.argv


def show(store: FakeStore, title: str) -> None:
    print(f"\n=== {title} ({store.now.astimezone(TZ):%a %H:%M}) ===")
    for m in store.messages[show.seen:]:
        about = f" about {m['task_ids']}" if m["task_ids"] else ""
        print(f"  [{m['kind']}] {m['role']}{about}: {m['content']}")
        if m.get("chips"):
            print("      buttons: " + " · ".join(c["label"] + ("" if c.get("action") else " (text)") for c in m["chips"]))
    for p in store.pushes[show.pushed:]:
        buttons = (" [" + " | ".join(a["title"] for a in p["actions"]) + "]") if p.get("actions") else ""
        print(f"  PUSH '{p['title']}' → {p['url']}{buttons}: {p['body']}")
    show.seen, show.pushed = len(store.messages), len(store.pushes)


show.seen = show.pushed = 0


def setattr_(obj, name, value):
    setattr(obj, name, value)


def at(h: int, m: int = 0) -> datetime:
    return datetime(2026, 11, 9, h, m, tzinfo=TZ)


def tick() -> None:
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))


def main() -> None:
    store = FakeStore(at(7, 30))
    store.install(setattr_)

    invoice = store.add_task("Send the invoice to Dana", due_at=at(9, 0) - timedelta(days=1),
                             remind_at=at(9, 55), description="Client Dana, November work",
                             next_action="Open last month's invoice and copy it")
    report = store.add_task("Write the report outline", priority=2, remind_at=at(10, 0),
                            next_action="List the 5 section headings")
    store.add_task("Book the dentist", priority=2, next_action="Call the clinic")  # undated, no check-in pending
    photos = store.add_task("Sort the holiday photos", remind_at=at(9, 0))  # undated, not in Today's 3
    store.add_task("Team sync", kind="meeting", due_at=at(10, 45), remind_at=at(10, 30))

    down = LLMUnavailable("OpenRouter unreachable (simulated)")
    llm = ScriptedLLM([down] * 20 if LLM_DOWN else [
        # 07:30 morning: the LLM picks Today's 3, then writes the plan
        {"tool": "set_focus", "args": {"task_ids": [invoice["id"], report["id"]]}},
        "Today's 3:\n1. Invoice for Dana — first step: open last month's invoice and copy it\n"
        "2. Report outline\n📅 10:45 Team sync",
        # 10:00 one check-in for both focus tasks
        "Open last month's invoice and copy it? Then the report headings.",
        # 10:05 chat reply about the invoice (+ a new task, first step filled in silently)
        {"tool": "log_interaction", "args": {"task_id": invoice["id"], "kind": "progress", "note": "copied it"}},
        {"tool": "add_task", "args": {"title": "Ask Dana for her VAT number", "next_action": "Write Dana one line",
                                      "estimate_min": 5}},
        "Nice. Saved: Ask Dana for her VAT number. First step: write Dana one line.",
        # 10:30 meeting line
        "Anything to prepare?",
    ])
    llm.install(setattr_)

    asyncio.run(scheduler.run_daily_job("morning"))
    show(store, "Morning plan: Today's 3 is set, no reply needed")

    store.now = at(10, 0).astimezone(store.now.tzinfo)
    tick()
    show(store, "Tick: focus tasks get a check-in; the undated photo task stays quiet")

    store.advance(5)
    if not LLM_DOWN:
        r = asyncio.run(agent.handle_user_message("Copied the invoice, just need the VAT number"))
        show(store, f"Reply (linked to tasks {r.task_ids})")

    store.advance(5)
    calls = len(llm.requests)
    actions.apply(invoice["id"], "done")  # ✓ Done on a notification
    show(store, f"✓ Done from the notification (LLM calls made: {len(llm.requests) - calls})")

    store.advance(20)
    tick()
    show(store, "Tick: meeting reminder (skips the gap, time comes from code)")

    print("\nTasks:")
    for t in store.tasks.values():
        rem = t["remind_at"].astimezone(TZ).strftime("%H:%M") if t["remind_at"] else "-"
        focus = f"focus #{t['focus_rank']}" if t["focus_date"] else "-"
        print(f"  #{t['id']} {t['title']!r:34} {t['status']:5} {focus:8} next check-in {rem:5}  "
              f"first step: {t['next_action'] or '-'}")
    quiet = all(photos["id"] not in m["task_ids"] for m in store.messages)
    print(f"\nUndated task outside Today's 3 stayed quiet: {quiet}")
    print(f"LLM calls made: {len(llm.requests)}  (fallback mode: {LLM_DOWN})")


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    main()
