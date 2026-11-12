"""
The /tick logic, called every 5 minutes by cron-job.org.

1. plan_tick()  (synchronous, fast, no LLM)
   due tasks → guardrails (logic.plan_contacts) → postpone what may not be sent now
   → lease the rest for one delivery.
2. deliver()    (background task)
   one combined message (LLM or template) → saved in the chat → one push (as loud as its most
   pressing task: normal / urgent / alarm, see logic.alert_level) → next check-in times.
3. Daily jobs: morning plan, mid-day check-ins, evening review (background, once per day each),
   plus the meeting digests: the week's meetings at the start of the week, tomorrow's in the evening.
4. Focus sessions: plan_sessions() (sync) records silence as paused and claims due end check-ins;
   deliver_session_ends() (background) sends them: a template with ✓ Done · ▶ Another round · Stop.
   While a session runs, other check-ins wait (the focus hold, in logic.contact_decision).
5. Gentler pressure (Phase 3):
   - An avoided task (3+ postponed / ignored in 7 days) gets no more ordinary check-ins: one reason
     message (Too big · Unclear · Boring · Not now), one more try 3 days later, then it's parked.
   - Only meetings, fences, asked-for reminders and near deadlines are ever urgent or alarms; every
     other task gets one normal check-in a day at most.
   - A lapse (48 hours with no chat or tap, Shabbat and holidays not counted) stops task check-ins and
     the mid-day / evening messages; the next morning message is the re-entry message, once.
   - Fences (hard stops) are sent at their time, urgent, through sessions and the gap, never through
     quiet hours or Shabbat (then they're skipped: late, they'd only be noise).
   - The first morning of the week carries the week's meetings (instead of a separate digest), last
     week's wins and the cleanup (Drop them · Keep).
"""

import asyncio
import logging
import time
from dataclasses import replace
from datetime import datetime, timedelta

from . import actions, coach, db, notify, sessions, tasks, thread
from .config import get_settings
from .jewish_calendar import next_period, periods_between, rest_config, rest_until
from .logic import (ALARM, AVOID_RETRY_DAYS, NORMAL, URGENT, AlertPolicy, ContactState, Guardrails, alarm_window_s,
                    alert_level, avoid_move, chip_label, contact_decision, day_start, escalates, fence_is_stale,
                    focus_day, focus_hold_until, job_is_due, lapsed, next_reminder_after_nag, plan_contacts,
                    reentry_due, session_end_due, session_end_at, session_last_active, session_silent,
                    user_active_recently, week_meetings_merged)

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


def alert_policy() -> AlertPolicy:
    s = get_settings()
    return AlertPolicy(urgent_after=s.pushover_urgent_after, alarm_for=s.pushover_alarm_for,
                       alarm_after=s.pushover_alarm_after, max_nags=s.max_nags)


_UNSET = object()


def _replied_since(last_reply: datetime | None, t: datetime) -> bool:
    return last_reply is not None and last_reply > t


def ignored_streak(task: dict, last_reply=_UNSET) -> int:
    """
    Check-ins about this task in a row with no reply from the user since (approximate).
    last_reply: thread.last_user_reply_at(), when the caller already has it (one query for many tasks).
    """
    if not task["last_notified_at"]:
        return 0
    if last_reply is _UNSET:
        last_reply = thread.last_user_reply_at()
    return 0 if _replied_since(last_reply, task["last_notified_at"]) else task["nag_count"]


def message_level(group: list[dict], now: datetime, last_reply=_UNSET) -> tuple[int, int]:
    """(level, alarm seconds) for one message: as loud as its most pressing task."""
    s = get_settings()
    policy = alert_policy()
    if last_reply is _UNSET:
        last_reply = thread.last_user_reply_at()
    level = max(alert_level(t, ignored_streak(t, last_reply), policy, now) for t in group)
    if level < ALARM:
        return level, 0
    now_local = now.astimezone(s.tz)
    cfg = rest_config(s)
    period = next_period(now, cfg) if cfg else None
    window = alarm_window_s(now_local, s.quiet_start, period.start if period else None)
    if window < s.pushover_alarm_retry_min * 60:
        return URGENT, 0  # quiet hours or Shabbat start too soon for an alarm to repeat even once
    return ALARM, window


def _delivery_running() -> bool:
    return _delivering_since is not None and time.monotonic() - _delivering_since < 3 * 60


def is_lapsed(now: datetime) -> bool:
    """No chat message and no tap for 48 hours, not counting Shabbat and holidays (logic.lapsed)."""
    last = thread.last_user_reply_at()
    if last is None:
        return False
    cfg = rest_config(get_settings())
    rests = []
    if cfg is not None:
        first = last.astimezone(cfg.tz).date() - timedelta(days=4)
        rests = [(p.start, p.end) for p in periods_between(first, now.astimezone(cfg.tz).date() + timedelta(days=1), cfg)]
    return lapsed(last, now, rests)


# ---------------------------------------------------------------- 1. plan (sync)

def plan_tick() -> list[int]:
    """Returns the ids of the tasks to contact about now, in message order (leased)."""
    global _delivering_since
    if _delivery_running():
        return []  # the previous message isn't out yet; the gap rule needs its timestamp
    tasks.close_finished_meetings()
    s = get_settings()
    now = tasks.now_utc()
    now_local = now.astimezone(s.tz)
    due = tasks.due_for_contact(focus_day(now_local, s.day_reset_time), is_lapsed(now))  # focus + lapse filter
    for fence in [t for t in due if t["kind"] == "fence" and fence_is_stale(t, now)]:
        tasks.fence_fired(fence["id"])  # its time passed in quiet hours / Shabbat: skipped, not sent late
        due.remove(fence)
    if not due:
        return []
    state = replace(thread.contact_state(day_start(now_local, s.day_reset_time)),
                    focus_hold_until=focus_hold_until(sessions.latest()))
    plan = plan_contacts(due, state, now_local, guardrails())
    for task_id, until in plan.defer.items():
        tasks.defer(task_id, until)
    order = _without_avoided(plan.send, now)
    leased = set(tasks.lease(order, LEASE_MIN))
    ids = [i for i in order if i in leased]
    if ids:
        _delivering_since = time.monotonic()
    return ids


def _without_avoided(send: list[dict], now: datetime) -> list[int]:
    """
    Avoided tasks get no ordinary check-ins (logic.avoid_move). Returns the ids for this tick's message:
    the tasks that aren't avoided; or, when there are none, ONE avoided task whose reason message is due
    (it's sent alone; another one waits for a later tick). Waiting ones are postponed; a task whose two
    reason messages went unanswered is parked, silently.
    """
    counts = tasks.event_counts_many([t["id"] for t in send if t["kind"] == "task"], 7)
    normal, asks = [], []
    for t in send:
        move = avoid_move(t, counts.get(t["id"]), now)
        if move is None:
            normal.append(t["id"])
        elif move == "ask":
            asks.append(t["id"])
        elif move == "wait":
            tasks.defer(t["id"], t["avoid_asked_at"] + timedelta(days=AVOID_RETRY_DAYS))
        else:
            actions.park(t["id"], silent=True)
            log.info("Task %s: two reason messages unanswered, parked", t["id"])
    return normal or asks[:1]


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
    # Database and notification calls block, so they run in worker threads; only the LLM part
    # (coach.compose) runs on the event loop.
    now = tasks.now_utc()
    group, state, avoided = await asyncio.to_thread(_load_group, task_ids, now)
    if not group:
        return
    composed = await coach.compose(group, now, state.sent_today_by_task, avoided)
    await asyncio.to_thread(_send, group, composed, now, avoided)


def _load_group(task_ids: list[int], now: datetime) -> tuple[list[dict], ContactState | None, bool]:
    """The tasks, today's contact counts, and whether this is an avoided task's reason message."""
    s = get_settings()
    by_id = {t["id"]: t for t in tasks.get_many(task_ids) if t["status"] == "open"}
    group = [by_id[i] for i in task_ids if i in by_id]
    if not group:
        return [], None, False
    avoided = False
    if len(group) == 1 and group[0]["kind"] == "task":
        avoided = avoid_move(group[0], tasks.event_counts(group[0]["id"], 7), now) == "ask"
    return group, thread.contact_state(day_start(now.astimezone(s.tz), s.day_reset_time)), avoided


def _send(group: list[dict], composed: "coach.Composed", now: datetime, avoided: bool = False) -> None:
    msg = thread.add("assistant", composed.text, composed.kind, [t["id"] for t in group], composed.chips)
    single = group[0] if len(group) == 1 else None
    last_reply = thread.last_user_reply_at()
    # The reason message about an avoided task is never loud, whatever the task is.
    level, alarm_s = (NORMAL, 0) if avoided else message_level(group, now, last_reply)
    notify.notify(
        composed.title, composed.text,
        level=level, expire_s=alarm_s,
        task_id=single["id"] if single and composed.push_actions else None,  # ▶ Start / ✓ Done: one task
        actions=composed.push_actions,
        message_id=msg["id"],
        priority=1 if avoided else max(t["priority"] for t in group),
    )
    log.info("Sent message %s about tasks %s (llm=%s, level=%s, avoided=%s)",
             msg["id"], [t["id"] for t in group], composed.used_llm, level, avoided)

    for task in group:
        _after_contact(task, now, composed.followups.get(task["id"]), alarmed=level == ALARM,
                       last_reply=last_reply, avoided=avoided)


def _after_contact(task: dict, now: datetime, followup: datetime | None, alarmed: bool = False,
                   last_reply=_UNSET, avoided: bool = False) -> None:
    s = get_settings()
    if task["kind"] == "fence":
        tasks.fence_fired(task["id"])  # once: a recurring fence moves to its next time, a one-off closes
        return
    if last_reply is _UNSET:
        last_reply = thread.last_user_reply_at()
    if task["last_notified_at"] and not _replied_since(last_reply, task["last_notified_at"]):
        tasks.add_event(task["id"], "ignored")
    if avoided:
        # One more look in 3 days: a last try, or (after that one) the park. Nothing in between.
        tasks.avoid_asked(task["id"], now + timedelta(days=AVOID_RETRY_DAYS))
        return

    nag_count = task["nag_count"] + 1
    if task["kind"] == "meeting" and task["due_at"]:
        # Meetings: one reminder before, one at start time, then stop.
        next_at = task["due_at"] if now < task["due_at"] - timedelta(minutes=2) else None
    else:
        # Tasks that may escalate (asked-for reminders, near deadlines) follow up within the day; every
        # other task gets one check-in a day (the per-task cap in logic.contact_decision holds the LLM to it).
        interval = s.nag_interval_min if escalates(task, now) else 24 * 60
        priority = task["priority"] if escalates(task, now) else 1
        next_at = next_reminder_after_nag(now, nag_count, priority, interval, s.max_nags)
        if followup is not None:
            next_at = followup  # the coach chose a time (already clamped by code)
    tasks.finish_contact(task["id"], nag_count, next_at, requested=task["kind"] == "meeting", escalate=alarmed)


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
    for t in s.checkin_times:
        # Keyed by the time itself, not its position: editing the list mid-day can't repeat or skip one.
        if job_is_due(now_local, t, window_hours=1) and claim_job(f"checkin@{t:%H:%M}", today):
            jobs.append("checkin")
    if job_is_due(now_local, s.evening_time, window_hours=2) and claim_job("evening", today):
        jobs.append("evening")
    # The week's meetings: inside the first morning plan of the week when the two times are within an
    # hour (one message instead of two); otherwise a message of their own, as before.
    if (s.week_meetings_time and now_local.weekday() == s.week_start_day
            and not week_meetings_merged(s.morning_time, s.week_meetings_time)
            and job_is_due(now_local, s.week_meetings_time) and claim_job("week_meetings", today)):
        jobs.append("week_meetings")
    if (s.meetings_eve_time and job_is_due(now_local, s.meetings_eve_time, window_hours=2)
            and claim_job("meetings_eve", today)):
        jobs.append("meetings_eve")
    return jobs


def _first_morning_of_week(now: datetime) -> tuple[bool, bool]:
    """(it's the first day of the week, the week's meetings go inside this morning's message)."""
    s = get_settings()
    first = now.astimezone(s.tz).weekday() == s.week_start_day
    return first, first and week_meetings_merged(s.morning_time, s.week_meetings_time)


async def run_daily_job(job: str) -> None:
    if job in coach.MEETING_JOBS:
        return await asyncio.to_thread(run_meeting_digest, job)
    now = tasks.now_utc()
    sunday, merged = _first_morning_of_week(now) if job == "morning" else (False, False)
    daily = None
    try:
        away = await asyncio.to_thread(is_lapsed, now)
        if job == "checkin" and await asyncio.to_thread(_active_recently, now):
            log.info("Mid-day check-in skipped: the user was active in the last hour")
            return
        if away and job != "morning":
            log.info("%s skipped: the user has been away for 48 hours", job)
            return
        if away:
            # After a lapse the morning plan becomes the re-entry message: once, then quiet mornings.
            if await asyncio.to_thread(_reentry_due, now):
                daily = await coach.compose_reentry(now)
            else:
                log.info("Morning plan skipped: away, and the re-entry message was already sent")
        else:
            daily = await coach.compose_daily(job, now, sunday)
    except Exception:
        log.exception("Daily job %s failed", job)
    try:
        if merged:
            daily = await asyncio.to_thread(_with_week_meetings, daily, now)
        if daily is not None:
            await asyncio.to_thread(_post_daily, job, daily)
    except Exception:
        log.exception("Daily job %s failed", job)


def _reentry_due(now: datetime) -> bool:
    return reentry_due(thread.last_user_reply_at(), thread.last_kind_at("reentry"), now)


def _with_week_meetings(daily: "coach.Daily | None", now: datetime) -> "coach.Daily | None":
    """The week's meetings at the end of the first morning message of the week (or alone, if there's none)."""
    digest = coach.meeting_digest("week_meetings", now)
    if digest is None:
        return daily
    if daily is None:
        return coach.Daily(digest.text, task_ids=digest.task_ids, kind="meeting")
    daily.text = f"{daily.text}\n\n{digest.text}"
    return daily


def _active_recently(now: datetime) -> bool:
    """A chat message or a tap in the last hour, or a focus session that ran in the last hour."""
    times = [t for t in (thread.last_user_reply_at(), session_last_active(sessions.latest(), now)) if t]
    return user_active_recently(max(times) if times else None, now)


def _post_daily(job: str, daily: "coach.Daily") -> None:
    kind = daily.kind or job
    msg = thread.add("assistant", daily.text, kind, daily.task_ids, daily.chips)
    notify.notify(coach.JOB_TITLES[kind], daily.text, message_id=msg["id"], priority=1)


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


# ---------------------------------------------------------------- 4. focus sessions

def plan_sessions() -> list[int]:
    """
    Sync, no LLM. Silence first: a session nobody answered 15 minutes after its end check-in (or after
    its end, if the check-in couldn't go out) is recorded as paused, and nothing more is sent. Then the
    sessions whose time is up get their end check-in, if the guardrails allow (quiet hours, Shabbat and
    the daily cap; not the gap or the per-task cap: the user asked for it). Returns the claimed ids.
    """
    s = get_settings()
    now = tasks.now_utc()
    now_local = now.astimezone(s.tz)
    open_ = sessions.open_sessions()
    due = []
    for sess in open_:
        if session_silent(sess, now):
            actions.close_session(sess, "paused", session_end_at(sess))
            log.info("Session %s on task %s: no answer, paused", sess["id"], sess["task_id"])
        elif session_end_due(sess, now):
            due.append(sess)
    if not due:
        return []
    state = thread.contact_state(day_start(now_local, s.day_reset_time))
    g = guardrails()
    claimed = []
    for sess in due:
        as_contact = {"id": sess["task_id"], "kind": "session", "remind_requested": True, "blocked_until": None}
        if contact_decision(as_contact, state, now_local, g) is None and sessions.claim_end(sess["id"]):
            claimed.append(sess["id"])
    return claimed


async def deliver_session_ends(session_ids: list[int]) -> None:
    for session_id in session_ids:
        try:
            await asyncio.to_thread(_send_session_end, session_id)
        except Exception:
            log.exception("Session end check-in %s failed (silence will record it as paused)", session_id)


def _send_session_end(session_id: int) -> None:
    """The check-in at the end of a session: a template (no LLM), one message, one push."""
    sess = sessions.get(session_id)
    task = tasks.get(sess["task_id"]) if sess else None
    if sess is None or sess["ended_at"] is not None or task is None or task["status"] != "open":
        return
    text, chips, lang = actions.session_end_message(sess, task)
    msg = thread.add("assistant", text, "session", [task["id"]], chips)
    notify.notify(
        f"⏱ {task['title']}", text, level=URGENT, task_id=task["id"], message_id=msg["id"], priority=task["priority"],
        actions=[{"action": "done", "title": chip_label("done", lang)},
                 {"action": "another", "title": chip_label("another", lang)}],
    )
    log.info("Session %s ended: check-in sent as message %s", session_id, msg["id"])
