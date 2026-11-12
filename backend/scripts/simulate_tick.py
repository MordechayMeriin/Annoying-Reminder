"""
Run a simulated morning (and then a simulated week) end to end — real scheduler, guardrails, coach, agent and actions — against an
in-memory database and a scripted LLM. No network, no Supabase, no OpenRouter.

    cd backend
    python scripts/simulate_tick.py            # LLM answers
    python scripts/simulate_tick.py --llm-down # OpenRouter unreachable → templates and the code's picks

What it shows (Phases 1 to 3 of the ADHD-assistant brief):
  07:30  the morning plan sets Today's 3 (no reply needed), with ▶ Start the first one · Change it
  10:00  a check-in about a focus task, with reply buttons and ▶ Start / ✓ Done on the notification;
         an undated task outside Today's 3 stays quiet
  10:05  a reply about one task gets ▶ Start 5 min · ✓ Done (+ a new task gets its first step silently)
  10:10  ✓ Done tapped on a notification: no LLM call, an event in the chat
  10:10  What now? picks the next thing by code (no LLM), short enough for the 10:45 meeting
  10:10  ▶ Start 5 min tapped on a notification: a focus session; other check-ins wait
  10:15  the session's end check-in (a template: ✓ Done · ▶ Another round · Stop)
  10:30  no answer → the session is recorded as paused (nothing more sent); the meeting reminder
         comes through (skips the gap, time comes from code, no buttons)
Then Phase 3, gentler pressure:
  Mon 11:00  said in chat: a hard stop ("fence"), a setting and a preference (skipped with --llm-down)
  Mon 12:30  the fence fires during a running focus session (written by code, urgent)
  Tue 10:00  a task ignored three times is avoided: one reason message instead of a check-in
             (Too big · Unclear · Boring · Not now), at normal level; Not now parks it for a week
  Fri 07:30  after 48 silent hours the morning plan is the re-entry message; no task check-ins
  Sun 07:30  one message: Today's 3, last week's wins, old tasks to drop or keep, the week's meetings
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

from app import actions, agent, config, scheduler, sessions, user_settings  # noqa: E402
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


def at(h: int, m: int = 0, d: int = 9) -> datetime:
    return datetime(2026, 11, d, h, m, tzinfo=TZ)   # Mon 9 Nov 2026 by default


def tick() -> None:
    ids = scheduler.plan_tick()
    if ids:
        asyncio.run(scheduler.deliver(ids))
    ends = scheduler.plan_sessions()
    if ends:
        asyncio.run(scheduler.deliver_session_ends(ends))


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

    calls = len(llm.requests)
    actions.what_now()  # the What now? button
    show(store, f"What now? (LLM calls made: {len(llm.requests) - calls})")

    actions.apply(report["id"], "start", 5)  # ▶ Start 5 min on a notification
    show(store, "▶ Start from the notification: a 5-minute focus session")

    store.advance(5)
    tick()
    show(store, "Tick after the session's end: the end check-in (no LLM)")

    store.advance(15)
    tick()
    paused = sessions.latest()
    show(store, f"Tick, no answer: session {paused['outcome']}; the meeting reminder still comes")

    print("\nTasks:")
    for t in store.tasks.values():
        rem = t["remind_at"].astimezone(TZ).strftime("%H:%M") if t["remind_at"] else "-"
        focus = f"focus #{t['focus_rank']}" if t["focus_date"] else "-"
        print(f"  #{t['id']} {t['title']!r:34} {t['status']:5} {focus:8} next check-in {rem:5}  "
              f"first step: {t['next_action'] or '-'}")
    quiet = all(photos["id"] not in m["task_ids"] for m in store.messages)
    print(f"\nUndated task outside Today's 3 stayed quiet: {quiet}")
    for x in store.sessions:
        print(f"Session #{x['id']} on task #{x['task_id']}: {x['planned_min']} min planned, outcome {x['outcome']}")
    print(f"LLM calls made: {len(llm.requests)}  (fallback mode: {LLM_DOWN})")
    phase3(store, invoice_id=invoice["id"], report_id=report["id"])


def phase3(store: FakeStore, invoice_id: int, report_id: int) -> None:
    """Gentler pressure: a fence, the avoided stage, re-entry after a lapse, and Sunday."""
    print("\n\n################ Phase 3: gentler pressure ################")
    saved: dict[str, str] = {}
    setattr_(user_settings, "save", lambda values: saved.update(values) or config.apply_overrides(
        {k: v for k, v in saved.items() if v is not None}))   # settings without a database
    down = LLMUnavailable("OpenRouter unreachable (simulated)")

    def go(d: int, h: int, m: int = 0) -> None:
        store.now = at(h, m, d).astimezone(store.now.tzinfo)

    ScriptedLLM([down] * 5).install(setattr_)
    go(9, 10, 45)
    tick()                                          # the meeting starts (its second and last reminder)
    show(store, "The meeting's start reminder")
    go(9, 11)
    if LLM_DOWN:
        store.add_task("Pick up the kids", kind="fence", due_at=at(12, 30), remind_at=at(12, 30),
                       remind_requested=True, recurrence="weekdays")
    else:
        llm = ScriptedLLM([
            {"tool": "add_task", "args": {"title": "Pick up the kids", "kind": "fence",
                                          "due_at": "2026-11-09T12:30", "recurrence": "weekdays"}},
            "Got it: I'll stop you at 12:30 on weekdays, even mid-session.",
            {"tool": "update_setting", "args": {"key": "quiet_start", "value": "22:00"}},
            "Done: quiet hours now start at 22:00 (was 23:00). Say 'undo' to revert.",
            {"tool": "remember_preference", "args": {"text": "Keep messages to one sentence"}},
            "Will do.",
        ])
        llm.install(setattr_)
        asyncio.run(agent.handle_user_message("btw I pick up the kids at 12:30 every weekday"))
        asyncio.run(agent.handle_user_message("and stop messaging me after 10pm"))
        asyncio.run(agent.handle_user_message("shorter messages please"))
        show(store, f"Said in chat: a hard stop, a setting (quiet from {config.get_settings().quiet_start:%H:%M}) "
                    f"and a preference {[p['text'] for p in store.preferences]}")

    ScriptedLLM([down] * 50).install(setattr_)      # from here on: templates (the same with the LLM up or down)
    go(9, 12, 15)
    actions.apply(report_id, "start", 25)
    go(9, 12, 30)
    tick()
    show(store, "The fence fires during the running session (urgent, by code); the session goes on")
    actions.apply(report_id, "done")

    dentist = next(t for t in store.tasks.values() if t["title"] == "Book the dentist")
    for _ in range(3):
        store.t_add_event(dentist["id"], "ignored")
    go(10, 10)
    store.tasks[dentist["id"]].update(focus_date=store.focus_day(), remind_at=store.now)
    tick()
    level = store.pushovers[-1]["level"]
    show(store, f"An avoided task: one reason message, not a check-in (push level {level} = normal)")
    actions.apply(dentist["id"], "not_now")
    parked = store.tasks[dentist["id"]]["parked_until"].astimezone(TZ)
    show(store, f"Not now: parked until {parked:%a %d/%m}, no LLM")

    # Silence from Tuesday 10:00. Friday morning is 69 hours later: a lapse (Shabbat hasn't started yet).
    store.tasks[invoice_id].update(status="open")  # something overdue, so the re-entry offers to move it
    photos = next(t for t in store.tasks.values() if t["title"] == "Sort the holiday photos")
    go(13, 7, 30)
    store.tasks[photos["id"]].update(focus_date=store.focus_day(), remind_at=store.now)
    asyncio.run(scheduler.run_daily_job("morning"))
    tick()
    show(store, f"After 48 silent hours (lapsed: {scheduler.is_lapsed(store.now)}): the re-entry message, "
                "and no task check-ins")
    actions.bulk("move_overdue")
    show(store, "Move overdue to next week (one tap, no LLM)")

    go(15, 7, 30)                                   # Sunday; the tap on Friday means it isn't a lapse
    store.add_task("Prepare the budget numbers", priority=2, next_action="Export last quarter from the bank")
    store.add_task("Reply to the landlord", priority=2, next_action="Open his last email")
    old = store.add_task("Learn to juggle", created_at=store.now - timedelta(days=40))
    store.add_task("Budget review", kind="meeting", due_at=at(14, 0, 17))
    jobs_before = len(store.messages)
    asyncio.run(scheduler.run_daily_job("morning"))
    show(store, "Sunday: one message instead of two, with the cleanup")
    drop = next(c for c in store.messages[-1]["chips"] if c["id"] == "drop")
    actions.bulk("drop", drop["task_ids"])
    show(store, f"Drop them: exactly the listed task(s) {drop['task_ids']} → {store.tasks[old['id']]['status']}")
    print(f"\nSunday morning messages: {len([m for m in store.messages[jobs_before:] if m['role'] == 'assistant'])}")
    loud = [p for p in store.pushovers if p["level"] > 0 and p["title"].startswith(("📅", "⛔", "⏱")) is False]
    print(f"Urgent or alarm pushes that weren't a meeting, a fence or a timer: {len(loud)}")


if __name__ == "__main__":
    os.environ.setdefault("PYTHONIOENCODING", "utf-8")
    main()
