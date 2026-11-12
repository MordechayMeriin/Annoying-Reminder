"""Task storage and the operations shared by the agent, the coach, the UI and notification buttons."""

from datetime import date, datetime, timedelta, timezone
from typing import Any

from . import db
from .config import get_settings
from .logic import (AVOID_REASONS, FOCUS_MAX, NEAR_DEADLINE_H, STALE_COOLDOWN_DAYS, STALE_DAYS, STALE_MAX,
                    next_occurrence)

COLUMNS = ("id, parent_id, title, description, kind, priority, status, due_at, remind_at, "
           "remind_requested, recurrence, nag_count, escalated, blocked_reason, blocked_until, "
           "last_notified_at, created_at, completed_at, next_action, estimate_min, focus_date, focus_rank, resume_note, "
           "parked_until, stale_offered_at, avoid_asked_at, avoid_asks, avoid_reset_at")

TIME_KEYS = ("due_at", "remind_at", "blocked_until", "last_notified_at", "created_at", "completed_at",
             "parked_until", "stale_offered_at", "avoid_asked_at", "avoid_reset_at")
DESCRIPTION_MAX = 800
NEXT_ACTION_MAX = 200
RESUME_NOTE_MAX = 300
EVENT_KINDS = {"postponed", "committed", "progress", "stuck", "blocked", "unblocked", "ignored", "done",
               "description_changed", "session_started", "session_ended", "paused", "parked", "avoided"}
# Events that show the task is moving: they end "avoided", and a task with one isn't stale.
PROGRESS_KINDS = ("progress", "done", "committed", "session_started", "session_ended")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def to_public(task: dict[str, Any]) -> dict[str, Any]:
    """JSON-friendly task with times in local ISO format."""
    tz = get_settings().tz
    out = dict(task)
    for key in TIME_KEYS:
        if out.get(key) is not None:
            out[key] = out[key].astimezone(tz).isoformat(timespec="minutes")
    return out


def get(task_id: int) -> dict[str, Any] | None:
    return db.fetch_one(f"select {COLUMNS} from tasks where id = %s", (task_id,))


def get_many(ids: list[int]) -> list[dict[str, Any]]:
    if not ids:
        return []
    return db.fetch_all(f"select {COLUMNS} from tasks where id = any(%s::bigint[])", (list(ids),))


def subtasks(task_id: int) -> list[dict[str, Any]]:
    return db.fetch_all(
        f"select {COLUMNS} from tasks where parent_id = %s and status <> 'cancelled' order by created_at",
        (task_id,),
    )


# ---------------------------------------------------------------- task events (interaction log)

def add_event(task_id: int, kind: str, note: str | None = None, avoid_reason: str | None = None) -> None:
    """avoid_reason: why the user avoids the task (a reason button, or noticed by the agent)."""
    if kind not in EVENT_KINDS:
        raise ValueError(f"Unknown event kind: {kind}")
    if avoid_reason is not None and avoid_reason not in AVOID_REASONS:
        raise ValueError(f"Unknown avoid reason: {avoid_reason}")
    db.execute("insert into task_events (task_id, kind, note, avoid_reason) values (%s, %s, %s, %s)",
               (task_id, kind, (note or "")[:500] or None, avoid_reason))


def recent_events(task_id: int, limit: int = 8) -> list[dict[str, Any]]:
    return db.fetch_all(
        "select kind, note, created_at from task_events where task_id = %s order by created_at desc limit %s",
        (task_id, limit),
    )


def event_counts(task_id: int, days: int = 7) -> dict[str, int]:
    return event_counts_many([task_id], days)[task_id]


# Batch versions for several tasks at once (one query each instead of one per task).

def subtasks_many(ids: list[int]) -> dict[int, list[dict[str, Any]]]:
    out: dict[int, list[dict[str, Any]]] = {i: [] for i in ids}
    if not ids:
        return out
    for row in db.fetch_all(
        f"""select {COLUMNS} from tasks where parent_id = any(%s::bigint[]) and status <> 'cancelled'
            order by created_at""",
        (list(ids),),
    ):
        out.setdefault(row["parent_id"], []).append(row)
    return out


def recent_events_many(ids: list[int], limit: int = 8) -> dict[int, list[dict[str, Any]]]:
    out: dict[int, list[dict[str, Any]]] = {i: [] for i in ids}
    if not ids:
        return out
    for row in db.fetch_all(
        """select task_id, kind, note, created_at from (
             select task_id, kind, note, created_at,
                    row_number() over (partition by task_id order by created_at desc) as rn
             from task_events where task_id = any(%s::bigint[])
           ) e where rn <= %s order by task_id, created_at desc""",
        (list(ids), limit),
    ):
        task_id = row.pop("task_id")
        out.setdefault(task_id, []).append(row)
    return out


def event_counts_many(ids: list[int], days: int = 7) -> dict[int, dict[str, int]]:
    out: dict[int, dict[str, int]] = {i: {} for i in ids}
    if not ids:
        return out
    for row in db.fetch_all(
        # Counted from the task's last fresh start (a park, a reason given, a session: reset_avoidance).
        """select e.task_id, e.kind, count(*) as n from task_events e join tasks t on t.id = e.task_id
           where e.task_id = any(%s::bigint[]) and e.created_at > now() - make_interval(days => %s)
             and (t.avoid_reset_at is null or e.created_at > t.avoid_reset_at)
           group by e.task_id, e.kind""",
        (list(ids), days),
    ):
        out.setdefault(row["task_id"], {})[row["kind"]] = row["n"]
    return out


# ---------------------------------------------------------------- create / update

def create(title: str, *, kind: str = "task", priority: int = 1, description: str | None = None,
           due_at: datetime | None = None, remind_at: datetime | None = None,
           recurrence: str | None = None, parent_id: int | None = None,
           next_action: str | None = None, estimate_min: int | None = None) -> dict[str, Any]:
    s = get_settings()
    if remind_at is None and due_at is not None:
        lead = s.meeting_lead_min if kind == "meeting" else 0   # a fence fires at its time
        remind_at = due_at - timedelta(minutes=lead)
    return db.fetch_one(
        f"""insert into tasks (title, kind, priority, description, due_at, remind_at, remind_requested,
                               recurrence, parent_id, next_action, estimate_min)
            values (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) returning {COLUMNS}""",
        (title, kind, priority, _clip(description), due_at, remind_at, remind_at is not None,
         recurrence, parent_id, _clip(next_action, NEXT_ACTION_MAX), estimate_min),
    )


UPDATABLE = {"title", "description", "kind", "priority", "due_at", "remind_at", "recurrence", "status",
             "blocked_reason", "blocked_until", "next_action", "estimate_min", "resume_note"}


def update(task_id: int, **fields: Any) -> dict[str, Any] | None:
    fields = {k: v for k, v in fields.items() if k in UPDATABLE}
    if not fields:
        return get(task_id)
    if "next_action" in fields:
        fields["next_action"] = _clip(fields["next_action"], NEXT_ACTION_MAX)
    if "resume_note" in fields:
        fields["resume_note"] = _clip(fields["resume_note"], RESUME_NOTE_MAX) or None
    if "description" in fields:
        fields["description"] = _clip(fields["description"])
        old = get(task_id)
        if old and old["description"] and old["description"] != fields["description"]:
            add_event(task_id, "description_changed", old["description"])  # keep the old text
    # A new time from the user resets the nagging state and counts as a requested reminder.
    if "remind_at" in fields or "due_at" in fields:
        fields.update(nag_count=0, escalated=False)
        if "remind_at" not in fields and fields.get("due_at") is not None:
            current = get(task_id)
            lead = get_settings().meeting_lead_min if (current and current["kind"] == "meeting") else 0
            fields["remind_at"] = fields["due_at"] - timedelta(minutes=lead)
        fields["remind_requested"] = fields.get("remind_at") is not None
        fields["parked_until"] = None  # a new time from the user ends a park
    sets = ", ".join(f"{k} = %({k})s" for k in fields)
    fields["id"] = task_id
    return db.fetch_one(f"update tasks set {sets} where id = %(id)s returning {COLUMNS}", fields)


def _clip(text: str | None, limit: int = DESCRIPTION_MAX) -> str | None:
    if text is None:
        return None
    text = text.strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


# ---------------------------------------------------------------- lifecycle

def complete(task_id: int) -> dict[str, Any] | None:
    """Mark done (the resume note is cleared). Recurring tasks roll forward to the next occurrence instead."""
    task = get(task_id)
    if task is None or task["status"] != "open":
        return task
    add_event(task_id, "done")
    if task["recurrence"] and task["due_at"]:
        tz = get_settings().tz
        nxt = next_occurrence(task["due_at"], task["recurrence"], tz)
        while nxt <= now_utc():  # catch up if it was overdue for a while
            nxt = next_occurrence(nxt, task["recurrence"], tz)
        lead = (task["due_at"] - task["remind_at"]) if task["remind_at"] else timedelta(0)
        return db.fetch_one(
            f"""update tasks set due_at = %s, remind_at = %s, remind_requested = true, nag_count = 0,
                escalated = false, last_notified_at = null, blocked_reason = null, blocked_until = null,
                resume_note = null where id = %s returning {COLUMNS}""",
            (nxt, nxt - lead, task_id),
        )
    db.execute("update tasks set status = 'done', completed_at = now(), remind_at = null "
               "where parent_id = %s and status = 'open'", (task_id,))
    return db.fetch_one(
        f"""update tasks set status = 'done', completed_at = now(), remind_at = null, resume_note = null
            where id = %s returning {COLUMNS}""",
        (task_id,),
    )


def set_followup(task_id: int, when: datetime, requested: bool) -> dict[str, Any] | None:
    """Next contact time. requested=True: the user asked for it (doesn't use up the per-task cap)."""
    return db.fetch_one(
        f"""update tasks set remind_at = %s, remind_requested = %s, nag_count = 0, escalated = false,
            parked_until = null where id = %s and status = 'open' returning {COLUMNS}""",
        (when, requested, task_id),
    )


def snooze(task_id: int, minutes: int = 60) -> dict[str, Any] | None:
    task = set_followup(task_id, now_utc() + timedelta(minutes=minutes), requested=True)
    if task:
        add_event(task_id, "postponed", f"snoozed {minutes} min")
    return task


def block(task_id: int, reason: str, until: datetime) -> dict[str, Any] | None:
    task = db.fetch_one(
        f"""update tasks set blocked_reason = %s, blocked_until = %s, remind_at = %s,
            remind_requested = false, nag_count = 0 where id = %s and status = 'open' returning {COLUMNS}""",
        (reason, until, until, task_id),
    )
    if task:
        add_event(task_id, "blocked", reason)
    return task


def unblock(task_id: int) -> dict[str, Any] | None:
    task = db.fetch_one(
        f"""update tasks set blocked_reason = null, blocked_until = null
            where id = %s returning {COLUMNS}""",
        (task_id,),
    )
    if task:
        add_event(task_id, "unblocked")
    return task


def cancel(task_id: int) -> dict[str, Any] | None:
    db.execute("update tasks set status = 'cancelled', remind_at = null where parent_id = %s and status = 'open'",
               (task_id,))
    return db.fetch_one(
        f"update tasks set status = 'cancelled', remind_at = null where id = %s returning {COLUMNS}",
        (task_id,),
    )


# ---------------------------------------------------------------- scheduler support

def close_finished_meetings() -> None:
    """Meetings that ended long ago don't need to stay open."""
    db.execute(
        """update tasks set status = 'done', completed_at = now(), remind_at = null
           where status = 'open' and kind = 'meeting' and recurrence is null
             and due_at < now() - interval '2 hours'"""
    )


def due_for_contact(today: date, lapsed: bool = False, limit: int = 20) -> list[dict[str, Any]]:
    """
    Tasks due for a check-in, with the focus filter (logic.may_check_in, here in SQL so quiet tasks
    can't crowd out the rest): meetings and fences, reminders the user asked for, Today's 3 (today =
    logic.focus_day), tasks due within 48 hours, and an avoided task waiting for its last reason message.
    Undated tasks outside Today's 3 stay quiet; parked tasks too. lapsed (the user has been away for 48
    hours): only meetings, fences and reminders the user asked for.
    """
    return db.fetch_all(
        f"""select {COLUMNS} from tasks where status = 'open' and remind_at <= now()
            and (kind in ('meeting', 'fence')
                 or ((parked_until is null or parked_until <= now())
                     and (remind_requested
                          or (not %s and (focus_date = %s or avoid_asks > 0
                                          or due_at < now() + make_interval(hours => %s))))))
            order by remind_at limit %s""",
        (lapsed, today, NEAR_DEADLINE_H, limit),
    )


def meetings_between(start: datetime, end: datetime, kinds: tuple[str, ...] = ("meeting",)) -> list[dict[str, Any]]:
    """Open meetings that may start in [start, end): one-off ones in the window, and every recurring
    one that started before its end (logic.meeting_occurrences expands those).
    kinds=("meeting", "fence"): every fixed event (What now? never picks something longer)."""
    return db.fetch_all(
        f"""select {COLUMNS} from tasks where status = 'open' and kind = any(%s) and due_at < %s
            and (due_at >= %s or recurrence is not null) order by due_at""",
        (list(kinds), end, start),
    )


def defer(task_id: int, until: datetime) -> None:
    """Postpone because of a guardrail. Keeps remind_requested as it was."""
    db.execute("update tasks set remind_at = %s where id = %s and status = 'open' and remind_at <= now()",
               (until, task_id))


def lease(ids: list[int], minutes: int = 15) -> list[int]:
    """Claim due tasks for one delivery, so an overlapping tick can't contact about them twice."""
    if not ids:
        return []
    rows = db.fetch_all(
        """update tasks set remind_at = now() + make_interval(mins => %s)
           where id = any(%s::bigint[]) and status = 'open' and remind_at <= now() returning id""",
        (minutes, list(ids)),
    )
    return [r["id"] for r in rows]


def finish_contact(task_id: int, nag_count: int, next_at: datetime | None, requested: bool,
                   escalate: bool) -> None:
    db.execute(
        """update tasks set nag_count = %s, last_notified_at = now(), remind_at = %s, remind_requested = %s,
           escalated = escalated or %s where id = %s""",
        (nag_count, next_at, requested, escalate, task_id),
    )


def fence_fired(task_id: int) -> dict[str, Any] | None:
    """
    A fence (hard stop) was sent, or its time passed while nothing could be sent: a recurring one moves
    to its next time, a one-off is closed. No done event: a hard stop isn't a win.
    """
    task = get(task_id)
    if task is None or task["status"] != "open" or task["kind"] != "fence":
        return task
    if task["recurrence"] and task["due_at"]:
        tz = get_settings().tz
        nxt = next_occurrence(task["due_at"], task["recurrence"], tz)
        while nxt <= now_utc():
            nxt = next_occurrence(nxt, task["recurrence"], tz)
        return db.fetch_one(
            f"""update tasks set due_at = %s, remind_at = %s, remind_requested = true, nag_count = 0,
                last_notified_at = now() where id = %s returning {COLUMNS}""",
            (nxt, nxt, task_id))
    return db.fetch_one(
        f"""update tasks set status = 'done', completed_at = now(), remind_at = null, last_notified_at = now()
            where id = %s returning {COLUMNS}""",
        (task_id,))


# ---------------------------------------------------------------- avoidance, parking, cleanup (Phase 3)

def avoid_asked(task_id: int, next_at: datetime) -> None:
    """The reason message ("what's in the way?") went out: count it, and look again at next_at."""
    db.execute(
        """update tasks set avoid_asks = avoid_asks + 1, avoid_asked_at = now(), remind_at = %s,
           remind_requested = false, last_notified_at = now() where id = %s""",
        (next_at, task_id))


def reset_avoidance(task_id: int) -> None:
    """
    A fresh start for a task: the user gave a reason, started a session or made progress. Earlier
    postponed / ignored events no longer count toward "avoided", and a park ends.
    """
    db.execute(
        """update tasks set avoid_reset_at = now(), avoid_asks = 0, avoid_asked_at = null, parked_until = null
           where id = %s""",
        (task_id,))


def park(task_id: int, until: datetime) -> dict[str, Any] | None:
    """
    Not now: no check-ins until `until`, and out of Today's 3. When the park ends the task is simply
    open again (counting toward "avoided" starts over from now).
    """
    task = db.fetch_one(
        f"""update tasks set parked_until = %s, remind_at = %s, remind_requested = false, nag_count = 0,
            escalated = false, focus_date = null, focus_rank = null,
            avoid_reset_at = now(), avoid_asks = 0, avoid_asked_at = null
            where id = %s and status = 'open' returning {COLUMNS}""",
        (until, until, task_id))
    if task:
        add_event(task_id, "parked", f"until {until.astimezone(get_settings().tz):%Y-%m-%d}")
    return task


def stale_tasks(limit: int = STALE_MAX) -> list[dict[str, Any]]:
    """
    Sunday cleanup candidates, oldest first: top-level tasks open 21+ days with nothing moving in that
    time, not waiting on a future date, a blocker or a park, and not offered in the last 4 weeks.
    """
    return db.fetch_all(
        f"""select {COLUMNS} from tasks t where status = 'open' and kind = 'task' and parent_id is null
            and recurrence is null and created_at < now() - make_interval(days => %s)
            and (due_at is null or due_at < now())
            and (blocked_until is null or blocked_until <= now())
            and (parked_until is null or parked_until <= now())
            and (stale_offered_at is null or stale_offered_at < now() - make_interval(days => %s))
            and not exists (select 1 from task_events e where e.task_id = t.id and e.kind = any(%s)
                            and e.created_at > now() - make_interval(days => %s))
            order by created_at, id limit %s""",
        (STALE_DAYS, STALE_COOLDOWN_DAYS, list(PROGRESS_KINDS), STALE_DAYS, limit))


def mark_stale_offered(ids: list[int]) -> None:
    """Offered in the Sunday cleanup: whatever the answer (silence keeps them), not again for 4 weeks."""
    if ids:
        db.execute("update tasks set stale_offered_at = now() where id = any(%s::bigint[])", (list(ids),))


# ---------------------------------------------------------------- Today's 3

def set_focus(task_ids: list[int], today: date) -> list[dict[str, Any]]:
    """
    Make these (at most 3, open, not meetings) Today's 3, replacing today's earlier pick. Returns them
    in the given order. Silence accepts it: nothing else is needed from the user.
    """
    ids = list(dict.fromkeys(task_ids))[:FOCUS_MAX]
    with db.connect() as conn, conn.transaction():
        conn.execute("""update tasks set focus_date = null, focus_rank = null
                        where focus_date = %s and not (id = any(%s::bigint[]))""",
                     (today, ids))
        rows = conn.execute(
            f"""update tasks set focus_date = %s, focus_rank = array_position(%s::bigint[], id)
                where id = any(%s::bigint[]) and status = 'open' and kind = 'task' returning {COLUMNS}""",
            (today, ids, ids)).fetchall()
    by_id = {r["id"]: r for r in rows}
    return [by_id[i] for i in ids if i in by_id]


def focus_today(today: date) -> tuple[list[dict[str, Any]], set[int]]:
    """Today's 3 (open or done, not cancelled) and which of them were done today (recurring ones roll on)."""
    rows = db.fetch_all(
        f"""select {COLUMNS}, exists (select 1 from task_events e where e.task_id = tasks.id and e.kind = 'done'
                                      and e.created_at >= %s) as done_today
            from tasks where focus_date = %s and status <> 'cancelled' order by focus_rank nulls last, id""",
        (datetime.combine(today, datetime.min.time(), get_settings().tz), today),
    )
    done = {r["id"] for r in rows if r.pop("done_today") or r["status"] == "done"}
    return rows, done


def done_since(start: datetime) -> list[dict[str, Any]]:
    """
    Everything finished since `start`, from the done events: steps and recurring tasks (which roll on
    instead of closing) count too. The wins strip and the evening review use it. Oldest first.
    """
    return db.fetch_all(
        """select t.id, t.title, t.parent_id, max(e.created_at) as done_at
           from task_events e join tasks t on t.id = e.task_id
           where e.kind = 'done' and e.created_at >= %s and t.status <> 'cancelled'
           group by t.id, t.title, t.parent_id order by done_at""",
        (start,),
    )


# ---------------------------------------------------------------- lists

def day_bounds(days_ahead: int = 0) -> tuple[datetime, datetime]:
    """Start and end of a local calendar day, as aware datetimes."""
    tz = get_settings().tz
    start = datetime.now(tz).replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=days_ahead)
    return start, start + timedelta(days=1)


def list_tasks(scope: str = "open", days: int = 7) -> list[dict[str, Any]]:
    start, end = day_bounds()
    if scope == "today":
        return db.fetch_all(
            f"""select {COLUMNS} from tasks where status = 'open'
                and (due_at < %s or remind_at < %s) order by coalesce(due_at, remind_at)""",
            (end, end),
        )
    if scope == "overdue":
        return db.fetch_all(f"select {COLUMNS} from tasks where status = 'open' and due_at < now() order by due_at")
    if scope == "upcoming":
        return db.fetch_all(
            f"""select {COLUMNS} from tasks where status = 'open' and due_at >= %s and due_at < %s
                order by due_at""",
            (end, end + timedelta(days=days)),
        )
    if scope == "done_today":
        return db.fetch_all(
            f"select {COLUMNS} from tasks where status = 'done' and completed_at >= %s order by completed_at",
            (start,),
        )
    if scope == "blocked":
        return db.fetch_all(
            f"select {COLUMNS} from tasks where status = 'open' and blocked_reason is not null order by blocked_until"
        )
    # "open": everything not finished
    return db.fetch_all(
        f"select {COLUMNS} from tasks where status = 'open' order by due_at nulls last, priority desc, id"
    )
