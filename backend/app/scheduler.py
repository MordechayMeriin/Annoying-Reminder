"""
The /tick logic, called every 5 minutes by cron-job.org.

1. plan_tick()  (synchronous, fast, no LLM)
   due tasks → guardrails (logic.plan_contacts) → postpone what may not be sent now
   → lease the rest for one delivery.
2. deliver()    (background task)
   one combined message (LLM or template) → saved in the chat → one push → next check-in times.
3. Daily jobs: morning plan, mid-day check-ins, evening review (background, once per day each),
   plus the meeting digests: the week's meetings at the start of the week, tomorrow's in the evening.
"""

import logging
import time
from datetime import datetime, timedelta

from . import coach, db, notify, tasks, thread
from .config import get_settings
from .jewish_calendar import rest_config, rest_until
from .logic import (ContactState, Guardrails, day_start, job_is_due, next_reminder_after_nag,
                    plan_contacts)

log = logging.getLogger("scheduler")

LEASE_MIN = 15
_delivering_since: float | None = None  # one delivery at a time (single Render instance)


def guardrails() -> Guardrails:
    s = get_settings()
    cfg = rest_config(s)
    return Guardrails(
        quiet_start=s.quiet_start, quiet_end=s.quiet_end, day_reset=s.day_reset_time,
        min_gap_min=s.min_gap_min, max_per_day=s.max_proactive_per_day,
        max_per_task_per_day=s.max_per_task_per_day,
        followup_min_min=s.followup_min_min, followup_max_days=s.followup_max_days,
        rest_until=(lambda now: rest_until(now, cfg)) if cfg else None,
    )


def _delivery_running() -> bool:
    return _delivering_since is not None and time.monotonic() - _delivering_since < 3 * 60


# ---------------------------------------------------------------- 1. plan (sync)

def plan_tick() -> list[int]:
    """Returns the ids of the tasks to contact about now, in message order (leased)."""
    global _delivering_since
    if _delivery_running():
        return []  # the previous message isn't out yet; the gap rule needs its timestamp
    tasks.close_finished_meetings()
    due = tasks.due_for_contact()
    if not due:
        return []
    s = get_settings()
    now_local = tasks.now_utc().astimezone(s.tz)
    state = thread.contact_state(day_start(now_local, s.day_reset_time))
    plan = plan_contacts(due, state, now_local, guardrails())
    for task_id, until in plan.defer.items():
        tasks.defer(task_id, until)
    order = [t["id"] for t in plan.send]
    leased = set(tasks.lease(order, LEASE_MIN))
    ids = [i for i in order if i in leased]
    if ids:
        _delivering_since = time.monotonic()
    return ids


# ---------------------------------------------------------------- 2. deliver (background)

async def deliver(task_ids: list[int]) -> None:
    global _delivering_since
    try:
        await _deliver(task_ids)
    except Exception:
        log.exception("Delivery failed for tasks %s (the lease expires and they'll be retried)", task_ids)
    finally:
        _delivering_since = None


async def _deliver(task_ids: list[int]) -> None:
    s = get_settings()
    now = tasks.now_utc()
    by_id = {t["id"]: t for t in tasks.get_many(task_ids) if t["status"] == "open"}
    group = [by_id[i] for i in task_ids if i in by_id]
    if not group:
        return
    state: ContactState = thread.contact_state(day_start(now.astimezone(s.tz), s.day_reset_time))

    composed = await coach.compose(group, now, state.sent_today_by_task)
    msg = thread.add("assistant", composed.text, composed.kind, [t["id"] for t in group])
    single = group[0] if len(group) == 1 else None
    notify.notify(
        composed.title, composed.text,
        task_id=single["id"] if single else None,            # ✓ / ⏰ only for exactly one task
        message_id=msg["id"],
        priority=max(t["priority"] for t in group),
    )
    log.info("Sent message %s about tasks %s (llm=%s)", msg["id"], [t["id"] for t in group], composed.used_llm)

    for task in group:
        _after_contact(task, now, composed.followups.get(task["id"]))


def _after_contact(task: dict, now: datetime, followup: datetime | None) -> None:
    s = get_settings()
    if task["last_notified_at"] and not thread.user_replied_since(task["last_notified_at"]):
        tasks.add_event(task["id"], "ignored")

    nag_count = task["nag_count"] + 1
    if task["kind"] == "meeting" and task["due_at"]:
        # Meetings: one reminder before, one at start time, then stop.
        next_at = task["due_at"] if now < task["due_at"] - timedelta(minutes=2) else None
        escalate = False
    else:
        next_at, escalate = next_reminder_after_nag(now, nag_count, task["priority"], s.nag_interval_min, s.max_nags)
        if followup is not None:
            next_at = followup  # the coach chose a time (already clamped by code)

    if escalate and not task["escalated"]:
        notify.send_pushover(task["title"], f"Ignored {nag_count} times. Do it now.", emergency=True,
                             url=f"{s.web_origin}/?task={task['id']}")
    tasks.finish_contact(task["id"], nag_count, next_at, requested=task["kind"] == "meeting", escalate=escalate)


# ---------------------------------------------------------------- 3. daily jobs

def claim_job(job: str, run_date) -> bool:
    """Insert-or-skip, so a job runs at most once per day even if ticks overlap."""
    row = db.fetch_one(
        "insert into job_runs (job, run_date) values (%s, %s) on conflict do nothing returning job",
        (job, run_date),
    )
    return row is not None


def due_daily_jobs() -> list[str]:
    """
    Which daily jobs should run now (and claim them).
    Nothing runs on Shabbat / holidays. A job isn't claimed then, so if its window reaches past the
    end (e.g. Saturday's 20:00 "tomorrow's meetings" when quiet lasts until 20:30) it runs right after;
    otherwise it's skipped that day (Saturday's morning plan).
    """
    s = get_settings()
    now_local = datetime.now(s.tz)
    today = now_local.date()
    if rest_until(now_local, rest_config(s)) is not None:
        return []
    jobs: list[str] = []
    if job_is_due(now_local, s.morning_time) and claim_job("morning", today):
        jobs.append("morning")
    for i, t in enumerate(s.checkin_times):
        if job_is_due(now_local, t, window_hours=1) and claim_job(f"checkin{i}", today):
            jobs.append("checkin")
    if job_is_due(now_local, s.evening_time, window_hours=2) and claim_job("evening", today):
        jobs.append("evening")
    if (s.week_meetings_time and now_local.weekday() == s.week_start_day
            and job_is_due(now_local, s.week_meetings_time) and claim_job("week_meetings", today)):
        jobs.append("week_meetings")
    if (s.meetings_eve_time and job_is_due(now_local, s.meetings_eve_time, window_hours=2)
            and claim_job("meetings_eve", today)):
        jobs.append("meetings_eve")
    return jobs


async def run_daily_job(job: str) -> None:
    if job in coach.MEETING_JOBS:
        return run_meeting_digest(job)
    try:
        text = await coach.compose_daily(job, tasks.now_utc())
        if text is None:
            return
        msg = thread.add("assistant", text, job)
        notify.notify(coach.JOB_TITLES[job], text, message_id=msg["id"], priority=1)
    except Exception:
        log.exception("Daily job %s failed", job)


def run_meeting_digest(job: str) -> None:
    """The week's / tomorrow's meetings: one chat message (linked to the meetings) and one push."""
    try:
        digest = coach.meeting_digest(job, tasks.now_utc())
        if digest is None:
            return  # no meetings, nothing to say
        msg = thread.add("assistant", digest.text, "meeting", digest.task_ids)
        notify.notify(digest.title, digest.text, message_id=msg["id"], priority=1)
    except Exception:
        log.exception("Meeting digest %s failed", job)
