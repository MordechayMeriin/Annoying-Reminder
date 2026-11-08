"""
The /tick logic, called every 5 minutes by cron-job.org.

1. Due reminders: plain SQL + push, no LLM (cheap, runs every tick).
2. Daily jobs: morning plan, check-ins, evening review — these use the LLM, once per day each.
"""

import json
import logging
from datetime import datetime, timedelta

from . import db, notify, tasks
from .config import get_settings
from .llm import chat_completion
from .logic import in_quiet_hours, job_is_due, next_reminder_after_nag, quiet_end_after

log = logging.getLogger("scheduler")

PRIORITY_PREFIX = {1: "", 2: "❗ ", 3: "🚨 "}


# ---------------------------------------------------------------- reminders

def _reminder_text(task: dict, now: datetime) -> tuple[str, str]:
    tz = get_settings().tz
    title = PRIORITY_PREFIX.get(task["priority"], "") + task["title"]
    if task["kind"] == "meeting" and task["due_at"]:
        mins = max(0, int((task["due_at"] - now).total_seconds() // 60))
        return title, f"Meeting at {task['due_at'].astimezone(tz):%H:%M} (in {mins} min)"
    if task["due_at"] and task["due_at"] < now:
        return title, f"Overdue since {task['due_at'].astimezone(tz):%a %H:%M}. Do it or snooze it."
    if task["nag_count"] > 0:
        return title, f"Reminder #{task['nag_count'] + 1} — still not done."
    if task["due_at"]:
        return title, f"Due {task['due_at'].astimezone(tz):%a %H:%M}"
    return title, "Time to do this."


def process_due_reminders() -> int:
    s = get_settings()
    now = tasks.now_utc()
    now_local = now.astimezone(s.tz)
    quiet = in_quiet_hours(now_local, s.quiet_start, s.quiet_end)

    # Meetings that ended long ago don't need to stay open.
    db.execute(
        """update tasks set status = 'done', completed_at = now(), remind_at = null
           where status = 'open' and kind = 'meeting' and recurrence is null
             and due_at < now() - interval '2 hours'"""
    )

    due = db.fetch_all(
        f"select {tasks.COLUMNS} from tasks where status = 'open' and remind_at <= now() order by remind_at limit 20"
    )
    sent = 0
    for task in due:
        if quiet and task["priority"] < 3:
            db.execute("update tasks set remind_at = %s where id = %s",
                       (quiet_end_after(now_local, s.quiet_end), task["id"]))
            continue

        title, body = _reminder_text(task, now)
        notify.notify(title, body, task_id=task["id"], priority=task["priority"], url=f"/?task={task['id']}")
        sent += 1

        nag_count = task["nag_count"] + 1
        if task["kind"] == "meeting" and task["due_at"]:
            # Meetings: one reminder before, one at start time, then stop.
            next_at = task["due_at"] if now < task["due_at"] - timedelta(minutes=2) else None
            escalate = False
        else:
            next_at, escalate = next_reminder_after_nag(
                now, nag_count, task["priority"], s.nag_interval_min, s.max_nags)
        if escalate and not task["escalated"]:
            notify.send_pushover(title, f"Ignored {nag_count} times. Do it now.", emergency=True,
                                 url=f"{s.web_origin}/?task={task['id']}")
        db.execute(
            """update tasks set nag_count = %s, last_notified_at = now(), remind_at = %s,
               escalated = escalated or %s where id = %s""",
            (nag_count, next_at, escalate, task["id"]),
        )
    return sent


# ---------------------------------------------------------------- daily jobs

def claim_job(job: str, run_date) -> bool:
    """Insert-or-skip, so a job runs at most once per day even if ticks overlap."""
    row = db.fetch_one(
        "insert into job_runs (job, run_date) values (%s, %s) on conflict do nothing returning job",
        (job, run_date),
    )
    return row is not None


def _snapshot() -> dict:
    pub = lambda rows: [  # noqa: E731
        {k: t[k] for k in ("id", "title", "kind", "priority", "due_at", "nag_count")}
        for t in map(tasks.to_public, rows)
    ]
    return {
        "today_and_overdue": pub(tasks.list_tasks("today")),
        "done_today": pub(tasks.list_tasks("done_today")),
        "upcoming_7_days": pub(tasks.list_tasks("upcoming", 7)),
        "open_without_date": pub([t for t in tasks.list_tasks("open") if t["due_at"] is None]),
    }


JOB_PROMPTS = {
    "morning": (
        "Write the user's morning plan for today. Order: meetings with times, then the 1–3 most important "
        "tasks, then quick wins. Call out anything overdue firmly. Suggest rough time slots around the meetings. "
        "Max ~120 words, plain text with short lines. Language: match the language of most task titles."
    ),
    "checkin": (
        "Write a short mid-day check-in (max 50 words). Mention what's still open for today, most important first. "
        "If everything for today is done, say so in one line. Language: match the task titles."
    ),
    "evening": (
        "Write a short evening review (max 80 words): what got done today, what's still open and will carry over "
        "to tomorrow, and anything that keeps getting pushed off (high nag_count). Language: match the task titles."
    ),
}

JOB_TITLES = {"morning": "☀️ Morning plan", "checkin": "⏰ Check-in", "evening": "🌙 Evening review"}


async def run_daily_job(job: str) -> None:
    s = get_settings()
    snap = _snapshot()
    if job == "checkin" and not snap["today_and_overdue"]:
        return  # nothing to nag about
    now = datetime.now(s.tz)
    messages = [
        {"role": "system", "content": f"You are 'Annoying Reminder', a concise, slightly firm personal assistant. "
                                      f"Now: {now:%Y-%m-%d %H:%M %A} ({s.tz.key})."},
        {"role": "user", "content": JOB_PROMPTS[job] + "\n\nTasks (JSON):\n" + json.dumps(snap, ensure_ascii=False)},
    ]
    try:
        text = ((await chat_completion(messages, temperature=0.5)).get("content") or "").strip()
    except Exception as e:
        log.error("Daily job %s failed: %s", job, e)
        text = f"{len(snap['today_and_overdue'])} open item(s) for today. Open the app to see them."
    db.execute("insert into messages (role, content, kind) values ('assistant', %s, %s)", (text, job))
    notify.notify(JOB_TITLES[job], text, priority=1, url="/")


def due_daily_jobs() -> list[str]:
    """Which daily jobs should run now (and claim them)."""
    s = get_settings()
    now_local = datetime.now(s.tz)
    today = now_local.date()
    jobs: list[str] = []
    if job_is_due(now_local, s.morning_time) and claim_job("morning", today):
        jobs.append("morning")
    for i, t in enumerate(s.checkin_times):
        if job_is_due(now_local, t, window_hours=1) and claim_job(f"checkin{i}", today):
            jobs.append("checkin")
    if job_is_due(now_local, s.evening_time, window_hours=2) and claim_job("evening", today):
        jobs.append("evening")
    return jobs
