"""Task storage and the operations shared by the agent, the coach, the UI and notification buttons.

Stored in DynamoDB (see db.py for the layout). Open tasks are read from the status index and filtered
in Python: there are only ever a few dozen, and the rules are the same ones logic.py uses. Two small
helper items keep reads that follow a write consistent: a parent's list of steps (children#<id>) and
each day's Today's 3 (focus#<date>).
"""

from datetime import date, datetime, timedelta, timezone
from typing import Any, Callable

from . import db
from .config import get_settings
from .db import pad, ts
from .logic import (AVOID_REASONS, FOCUS_MAX, STALE_COOLDOWN_DAYS, STALE_DAYS, STALE_MAX,
                    may_check_in, next_occurrence)

TIME_KEYS = ("due_at", "remind_at", "blocked_until", "last_notified_at", "created_at", "completed_at",
             "parked_until", "stale_offered_at", "avoid_asked_at", "avoid_reset_at")
DESCRIPTION_MAX = 800
NEXT_ACTION_MAX = 200
RESUME_NOTE_MAX = 300
EVENT_KINDS = {"postponed", "committed", "progress", "stuck", "blocked", "unblocked", "ignored", "done",
               "description_changed", "session_started", "session_ended", "paused", "parked", "avoided"}
# Events that show the task is moving: they end "avoided", and a task with one isn't stale.
PROGRESS_KINDS = ("progress", "done", "committed", "session_started", "session_ended")
KINDS = ("task", "meeting", "fence")
STATUSES = ("open", "done", "cancelled")
RECURRENCES = ("daily", "weekdays", "weekly", "monthly")


def _task_keys(t: dict[str, Any]) -> dict[str, str]:
    done_at = t.get("completed_at") if t["status"] == "done" else None
    return {"pk": "task", "sk": pad(t["id"]),
            # Open tasks by id; done ones by when they were finished (list_tasks("done_today")).
            "g1pk": f"task#{t['status']}", "g1sk": ts(done_at) if done_at else pad(t["id"])}


TASK = db.Entity(
    {"id": "int", "parent_id": "int", "title": "str", "description": "str", "kind": "str", "priority": "int",
     "status": "str", "due_at": "dt", "remind_at": "dt", "remind_requested": "bool", "recurrence": "str",
     "nag_count": "int", "escalated": "bool", "blocked_reason": "str", "blocked_until": "dt",
     "last_notified_at": "dt", "created_at": "dt", "completed_at": "dt", "next_action": "str",
     "estimate_min": "int", "focus_date": "date", "focus_rank": "int", "resume_note": "str",
     "parked_until": "dt", "stale_offered_at": "dt", "avoid_asked_at": "dt", "avoid_asks": "int",
     "avoid_reset_at": "dt"},
    _task_keys,
    defaults={"kind": "task", "priority": 1, "status": "open", "remind_requested": False, "nag_count": 0,
              "escalated": False, "avoid_asks": 0},
)

EVENT = db.Entity(
    {"task_id": "int", "kind": "str", "note": "str", "avoid_reason": "str", "created_at": "dt", "rid": "str"},
    lambda e: {"pk": f"ev#{e['task_id']}", "sk": f"{ts(e['created_at'])}#{e['rid']}",
               "g1pk": f"ev#{e['kind']}", "g1sk": ts(e["created_at"])},
)


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
    return db.get(TASK, "task", pad(task_id))


def get_many(ids: list[int]) -> list[dict[str, Any]]:
    if not ids:
        return []
    return db.get_many(TASK, [("task", pad(i)) for i in ids])


def _mutate(task_id: int, change: Callable[[dict[str, Any]], dict[str, Any] | None]) -> dict[str, Any] | None:
    return db.mutate(TASK, "task", pad(task_id), change)


def _with(**fields: Any) -> Callable[[dict[str, Any]], dict[str, Any]]:
    return lambda t: {**t, **fields}


def _if_open(**fields: Any) -> Callable[[dict[str, Any]], dict[str, Any] | None]:
    return lambda t: {**t, **fields} if t["status"] == "open" else None


def _open() -> list[dict[str, Any]]:
    return db.query_rows(TASK, "task#open", index="GSI1")


def _child_ids(task_id: int) -> list[int]:
    raw = db.get_raw("children", pad(task_id))
    return sorted(int(i) for i in (raw or {}).get("ids", ()))


def subtasks(task_id: int) -> list[dict[str, Any]]:
    rows = [t for t in get_many(_child_ids(task_id)) if t["status"] != "cancelled"]
    return sorted(rows, key=lambda t: (t["created_at"], t["id"]))


# ---------------------------------------------------------------- task events (interaction log)

def add_event(task_id: int, kind: str, note: str | None = None, avoid_reason: str | None = None) -> None:
    """avoid_reason: why the user avoids the task (a reason button, or noticed by the agent)."""
    if kind not in EVENT_KINDS:
        raise ValueError(f"Unknown event kind: {kind}")
    if avoid_reason is not None and avoid_reason not in AVOID_REASONS:
        raise ValueError(f"Unknown avoid reason: {avoid_reason}")
    db.put(EVENT, {"task_id": task_id, "kind": kind, "note": (note or "")[:500] or None,
                   "avoid_reason": avoid_reason, "created_at": now_utc(), "rid": db.rand()})


def _events(task_id: int, *, since: datetime | None = None, limit: int | None = None) -> list[dict[str, Any]]:
    """A task's events, newest first."""
    kw: dict[str, Any] = {"forward": False, "limit": limit}
    if since is not None:
        kw.update(sk_op=">=", sk=ts(since))
    return db.query_rows(EVENT, f"ev#{task_id}", **kw)


def recent_events(task_id: int, limit: int = 8) -> list[dict[str, Any]]:
    return [{k: e[k] for k in ("kind", "note", "created_at")} for e in _events(task_id, limit=limit)]


def event_counts(task_id: int, days: int = 7) -> dict[str, int]:
    return event_counts_many([task_id], days)[task_id]


# Batch versions for several tasks at once.

def subtasks_many(ids: list[int]) -> dict[int, list[dict[str, Any]]]:
    return {i: subtasks(i) for i in ids}


def recent_events_many(ids: list[int], limit: int = 8) -> dict[int, list[dict[str, Any]]]:
    return {i: recent_events(i, limit) for i in ids}


def event_counts_many(ids: list[int], days: int = 7) -> dict[int, dict[str, int]]:
    """Events per kind in the last `days`, counted from the task's last fresh start (reset_avoidance)."""
    out: dict[int, dict[str, int]] = {i: {} for i in ids}
    if not ids:
        return out
    resets = {t["id"]: t["avoid_reset_at"] for t in get_many(ids)}
    cutoff = now_utc() - timedelta(days=days)
    for i in ids:
        reset = resets.get(i)
        start = max(cutoff, reset) if reset else cutoff
        for e in _events(i, since=start):
            if e["created_at"] > start:
                out[i][e["kind"]] = out[i].get(e["kind"], 0) + 1
    return out


# ---------------------------------------------------------------- create / update

def create(title: str, *, kind: str = "task", priority: int = 1, description: str | None = None,
           due_at: datetime | None = None, remind_at: datetime | None = None,
           recurrence: str | None = None, parent_id: int | None = None,
           next_action: str | None = None, estimate_min: int | None = None) -> dict[str, Any]:
    s = get_settings()
    _check(kind=kind, priority=priority, recurrence=recurrence, estimate_min=estimate_min)
    if remind_at is None and due_at is not None:
        lead = s.meeting_lead_min if kind == "meeting" else 0   # a fence fires at its time
        remind_at = due_at - timedelta(minutes=lead)
    row = TASK.row({})
    row.update(id=db.next_id("task"), title=title, kind=kind, priority=priority, description=_clip(description),
               due_at=due_at, remind_at=remind_at, remind_requested=remind_at is not None, recurrence=recurrence,
               parent_id=parent_id, next_action=_clip(next_action, NEXT_ACTION_MAX), estimate_min=estimate_min,
               created_at=now_utc())
    db.put(TASK, row, new=True)
    if parent_id is not None:
        db.client().update_item(
            TableName=db._table(), Key=db.to_attr({"pk": "children", "sk": pad(parent_id)}),
            UpdateExpression="ADD ids :id", ExpressionAttributeValues={":id": {"NS": [str(row["id"])]}})
    return row


def _check(**fields: Any) -> None:
    """The checks the old table constraints made."""
    if fields.get("kind", "task") not in KINDS:
        raise ValueError(f"Unknown kind: {fields['kind']}")
    if fields.get("status", "open") not in STATUSES:
        raise ValueError(f"Unknown status: {fields['status']}")
    if fields.get("recurrence") is not None and fields["recurrence"] not in RECURRENCES:
        raise ValueError(f"Unknown recurrence: {fields['recurrence']}")
    if "priority" in fields and not 1 <= int(fields["priority"]) <= 3:
        raise ValueError("priority must be 1-3")
    est = fields.get("estimate_min")
    if est is not None and not 1 <= int(est) <= 10000:
        raise ValueError("estimate_min must be 1-10000")


UPDATABLE = {"title", "description", "kind", "priority", "due_at", "remind_at", "recurrence", "status",
             "blocked_reason", "blocked_until", "next_action", "estimate_min", "resume_note"}


def update(task_id: int, **fields: Any) -> dict[str, Any] | None:
    fields = {k: v for k, v in fields.items() if k in UPDATABLE}
    if not fields:
        return get(task_id)
    _check(**fields)
    if "next_action" in fields:
        fields["next_action"] = _clip(fields["next_action"], NEXT_ACTION_MAX)
    if "resume_note" in fields:
        fields["resume_note"] = _clip(fields["resume_note"], RESUME_NOTE_MAX) or None
    if "description" in fields:
        fields["description"] = _clip(fields["description"])
    lead_min = get_settings().meeting_lead_min
    old_description: list[str | None] = [None]

    def change(t: dict[str, Any]) -> dict[str, Any]:
        new = dict(fields)
        old_description[0] = t["description"]
        # A new time from the user resets the nagging state and counts as a requested reminder.
        if "remind_at" in new or "due_at" in new:
            new.update(nag_count=0, escalated=False)
            if "remind_at" not in new and new.get("due_at") is not None:
                lead = lead_min if t["kind"] == "meeting" else 0
                new["remind_at"] = new["due_at"] - timedelta(minutes=lead)
            new["remind_requested"] = new.get("remind_at") is not None
            new["parked_until"] = None  # a new time from the user ends a park
        return {**t, **new}

    row = _mutate(task_id, change)
    old = old_description[0]
    if row and "description" in fields and old and old != fields["description"]:
        add_event(task_id, "description_changed", old)  # keep the old text
    return row


def _clip(text: str | None, limit: int = DESCRIPTION_MAX) -> str | None:
    if text is None:
        return None
    text = text.strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


# ---------------------------------------------------------------- lifecycle

def _next_time(t: dict[str, Any]) -> datetime:
    """A recurring task's next occurrence that's still in the future (catching up if it was overdue)."""
    tz = get_settings().tz
    nxt = next_occurrence(t["due_at"], t["recurrence"], tz)
    while nxt <= now_utc():
        nxt = next_occurrence(nxt, t["recurrence"], tz)
    return nxt


def complete(task_id: int) -> dict[str, Any] | None:
    """Mark done (the resume note is cleared). Recurring tasks roll forward to the next occurrence instead."""
    task = get(task_id)
    if task is None or task["status"] != "open":
        return task
    add_event(task_id, "done")
    if task["recurrence"] and task["due_at"]:
        def roll(t: dict[str, Any]) -> dict[str, Any]:
            nxt = _next_time(t)
            lead = (t["due_at"] - t["remind_at"]) if t["remind_at"] else timedelta(0)
            return {**t, "due_at": nxt, "remind_at": nxt - lead, "remind_requested": True, "nag_count": 0,
                    "escalated": False, "last_notified_at": None, "blocked_reason": None, "blocked_until": None,
                    "resume_note": None}
        return _mutate(task_id, roll)
    now = now_utc()
    for child in get_many(_child_ids(task_id)):
        if child["status"] == "open":
            _mutate(child["id"], _if_open(status="done", completed_at=now, remind_at=None))
    return _mutate(task_id, _with(status="done", completed_at=now, remind_at=None, resume_note=None))


def set_followup(task_id: int, when: datetime, requested: bool) -> dict[str, Any] | None:
    """Next contact time. requested=True: the user asked for it (doesn't use up the per-task cap)."""
    return _mutate(task_id, _if_open(remind_at=when, remind_requested=requested, nag_count=0, escalated=False,
                                     parked_until=None))


def snooze(task_id: int, minutes: int = 60) -> dict[str, Any] | None:
    task = set_followup(task_id, now_utc() + timedelta(minutes=minutes), requested=True)
    if task:
        add_event(task_id, "postponed", f"snoozed {minutes} min")
    return task


def block(task_id: int, reason: str, until: datetime) -> dict[str, Any] | None:
    task = _mutate(task_id, _if_open(blocked_reason=reason, blocked_until=until, remind_at=until,
                                     remind_requested=False, nag_count=0))
    if task:
        add_event(task_id, "blocked", reason)
    return task


def unblock(task_id: int) -> dict[str, Any] | None:
    task = _mutate(task_id, _with(blocked_reason=None, blocked_until=None))
    if task:
        add_event(task_id, "unblocked")
    return task


def cancel(task_id: int) -> dict[str, Any] | None:
    for child in get_many(_child_ids(task_id)):
        if child["status"] == "open":
            _mutate(child["id"], _if_open(status="cancelled", remind_at=None))
    return _mutate(task_id, _with(status="cancelled", remind_at=None))


# ---------------------------------------------------------------- scheduler support

def close_finished_meetings() -> None:
    """Meetings that ended long ago don't need to stay open."""
    now = now_utc()
    for t in _open():
        if t["kind"] == "meeting" and not t["recurrence"] and t["due_at"] and t["due_at"] < now - timedelta(hours=2):
            _mutate(t["id"], _if_open(status="done", completed_at=now, remind_at=None))


def due_for_contact(today: date, lapsed: bool = False, limit: int = 20) -> list[dict[str, Any]]:
    """
    Tasks due for a check-in, with the focus filter (logic.may_check_in, so quiet tasks can't crowd out
    the rest): meetings and fences, reminders the user asked for, Today's 3 (today = logic.focus_day),
    tasks due within 48 hours, and an avoided task waiting for its last reason message.
    Undated tasks outside Today's 3 stay quiet; parked tasks too. lapsed (the user has been away for 48
    hours): only meetings, fences and reminders the user asked for.
    """
    now = now_utc()
    due = [t for t in _open() if t["remind_at"] and t["remind_at"] <= now and may_check_in(t, now, today, lapsed)]
    return sorted(due, key=lambda t: (t["remind_at"], t["id"]))[:limit]


def meetings_between(start: datetime, end: datetime, kinds: tuple[str, ...] = ("meeting",)) -> list[dict[str, Any]]:
    """Open meetings that may start in [start, end): one-off ones in the window, and every recurring
    one that started before its end (logic.meeting_occurrences expands those).
    kinds=("meeting", "fence"): every fixed event (What now? never picks something longer)."""
    rows = [t for t in _open() if t["kind"] in kinds and t["due_at"] and t["due_at"] < end
            and (t["due_at"] >= start or t["recurrence"])]
    return sorted(rows, key=lambda t: (t["due_at"], t["id"]))


def _due_now(t: dict[str, Any]) -> bool:
    return t["status"] == "open" and t["remind_at"] is not None and t["remind_at"] <= now_utc()


def defer(task_id: int, until: datetime) -> None:
    """Postpone because of a guardrail. Keeps remind_requested as it was."""
    _mutate(task_id, lambda t: {**t, "remind_at": until} if _due_now(t) else None)


def lease(ids: list[int], minutes: int = 15) -> list[int]:
    """Claim due tasks for one delivery, so an overlapping tick can't contact about them twice."""
    out = []
    for i in ids:
        until = now_utc() + timedelta(minutes=minutes)
        if _mutate(i, lambda t: {**t, "remind_at": until} if _due_now(t) else None):
            out.append(i)
    return out


def finish_contact(task_id: int, nag_count: int, next_at: datetime | None, requested: bool,
                   escalate: bool) -> None:
    now = now_utc()
    _mutate(task_id, lambda t: {**t, "nag_count": nag_count, "last_notified_at": now, "remind_at": next_at,
                                "remind_requested": requested, "escalated": t["escalated"] or escalate})


def fence_fired(task_id: int) -> dict[str, Any] | None:
    """
    A fence (hard stop) was sent, or its time passed while nothing could be sent: a recurring one moves
    to its next time, a one-off is closed. No done event: a hard stop isn't a win.
    """
    task = get(task_id)
    if task is None or task["status"] != "open" or task["kind"] != "fence":
        return task
    now = now_utc()

    def change(t: dict[str, Any]) -> dict[str, Any]:
        if t["recurrence"] and t["due_at"]:
            nxt = _next_time(t)
            return {**t, "due_at": nxt, "remind_at": nxt, "remind_requested": True, "nag_count": 0,
                    "last_notified_at": now}
        return {**t, "status": "done", "completed_at": now, "remind_at": None, "last_notified_at": now}
    return _mutate(task_id, change)


# ---------------------------------------------------------------- avoidance, parking, cleanup (Phase 3)

def avoid_asked(task_id: int, next_at: datetime) -> None:
    """The reason message ("what's in the way?") went out: count it, and look again at next_at."""
    now = now_utc()
    _mutate(task_id, lambda t: {**t, "avoid_asks": t["avoid_asks"] + 1, "avoid_asked_at": now, "remind_at": next_at,
                                "remind_requested": False, "last_notified_at": now})


def reset_avoidance(task_id: int) -> None:
    """
    A fresh start for a task: the user gave a reason, started a session or made progress. Earlier
    postponed / ignored events no longer count toward "avoided", and a park ends.
    """
    _mutate(task_id, _with(avoid_reset_at=now_utc(), avoid_asks=0, avoid_asked_at=None, parked_until=None))


def park(task_id: int, until: datetime) -> dict[str, Any] | None:
    """
    Not now: no check-ins until `until`, and out of Today's 3. When the park ends the task is simply
    open again (counting toward "avoided" starts over from now).
    """
    task = _mutate(task_id, _if_open(parked_until=until, remind_at=until, remind_requested=False, nag_count=0,
                                     escalated=False, focus_date=None, focus_rank=None,
                                     avoid_reset_at=now_utc(), avoid_asks=0, avoid_asked_at=None))
    if task:
        add_event(task_id, "parked", f"until {until.astimezone(get_settings().tz):%Y-%m-%d}")
    return task


def stale_tasks(limit: int = STALE_MAX) -> list[dict[str, Any]]:
    """
    Sunday cleanup candidates, oldest first: top-level tasks open 21+ days with nothing moving in that
    time, not waiting on a future date, a blocker or a park, and not offered in the last 4 weeks.
    """
    now = now_utc()
    old = now - timedelta(days=STALE_DAYS)
    cooldown = now - timedelta(days=STALE_COOLDOWN_DAYS)
    candidates = sorted(
        (t for t in _open()
         if t["kind"] == "task" and t["parent_id"] is None and t["recurrence"] is None and t["created_at"] < old
         and (t["due_at"] is None or t["due_at"] < now)
         and (t["blocked_until"] is None or t["blocked_until"] <= now)
         and (t["parked_until"] is None or t["parked_until"] <= now)
         and (t["stale_offered_at"] is None or t["stale_offered_at"] < cooldown)),
        key=lambda t: (t["created_at"], t["id"]))
    out = []
    for t in candidates:
        if not any(e["kind"] in PROGRESS_KINDS and e["created_at"] > old for e in _events(t["id"], since=old)):
            out.append(t)
            if len(out) >= limit:
                break
    return out


def mark_stale_offered(ids: list[int]) -> None:
    """Offered in the Sunday cleanup: whatever the answer (silence keeps them), not again for 4 weeks."""
    now = now_utc()
    for i in ids:
        _mutate(i, _with(stale_offered_at=now))


# ---------------------------------------------------------------- Today's 3

def _focus_ids(today: date) -> list[int]:
    raw = db.get_raw("focus", today.isoformat())
    return [int(i) for i in (raw or {}).get("ids", [])]


def set_focus(task_ids: list[int], today: date) -> list[dict[str, Any]]:
    """
    Make these (at most 3, open, not meetings) Today's 3, replacing today's earlier pick. Returns them
    in the given order. Silence accepts it: nothing else is needed from the user.
    """
    ids = list(dict.fromkeys(task_ids))[:FOCUS_MAX]
    for i in _focus_ids(today):
        if i not in ids:
            _mutate(i, lambda t: {**t, "focus_date": None, "focus_rank": None} if t["focus_date"] == today else None)
    out = []
    for rank, i in enumerate(ids, 1):
        row = _mutate(i, lambda t, rank=rank: {**t, "focus_date": today, "focus_rank": rank}
                      if t["status"] == "open" and t["kind"] == "task" else None)
        if row:
            out.append(row)
    db.put_raw({"pk": "focus", "sk": today.isoformat(), "ids": [t["id"] for t in out]})
    return out


def focus_today(today: date) -> tuple[list[dict[str, Any]], set[int]]:
    """Today's 3 (open or done, not cancelled) and which of them were done today (recurring ones roll on)."""
    rows = [t for t in get_many(_focus_ids(today)) if t["focus_date"] == today and t["status"] != "cancelled"]
    rows.sort(key=lambda t: (t["focus_rank"] is None, t["focus_rank"] or 0, t["id"]))
    start = datetime.combine(today, datetime.min.time(), get_settings().tz)
    done = {t["id"] for t in rows
            if t["status"] == "done" or any(e["kind"] == "done" for e in _events(t["id"], since=start))}
    return rows, done


def done_since(start: datetime) -> list[dict[str, Any]]:
    """
    Everything finished since `start`, from the done events: steps and recurring tasks (which roll on
    instead of closing) count too. The wins strip and the evening review use it. Oldest first.
    """
    last: dict[int, datetime] = {}
    for e in db.query_rows(EVENT, "ev#done", index="GSI1", sk_op=">=", sk=ts(start)):
        if e["created_at"] >= start and e["created_at"] > last.get(e["task_id"], start - timedelta(seconds=1)):
            last[e["task_id"]] = e["created_at"]
    out = [{"id": t["id"], "title": t["title"], "parent_id": t["parent_id"], "done_at": last[t["id"]]}
           for t in get_many(list(last)) if t["status"] != "cancelled"]
    return sorted(out, key=lambda r: (r["done_at"], r["id"]))


# ---------------------------------------------------------------- lists

def day_bounds(days_ahead: int = 0) -> tuple[datetime, datetime]:
    """Start and end of a local calendar day, as aware datetimes."""
    tz = get_settings().tz
    start = datetime.now(tz).replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=days_ahead)
    return start, start + timedelta(days=1)


_FAR = datetime.max.replace(tzinfo=timezone.utc)


def list_tasks(scope: str = "open", days: int = 7) -> list[dict[str, Any]]:
    start, end = day_bounds()
    if scope == "done_today":
        rows = db.query_rows(TASK, "task#done", index="GSI1", sk_op=">=", sk=ts(start))
        return sorted((t for t in rows if t["completed_at"] and t["completed_at"] >= start),
                      key=lambda t: t["completed_at"])
    rows = _open()
    now = now_utc()
    if scope == "today":
        rows = [t for t in rows if (t["due_at"] and t["due_at"] < end) or (t["remind_at"] and t["remind_at"] < end)]
        return sorted(rows, key=lambda t: (t["due_at"] or t["remind_at"], t["id"]))
    if scope == "overdue":
        return sorted((t for t in rows if t["due_at"] and t["due_at"] < now), key=lambda t: (t["due_at"], t["id"]))
    if scope == "upcoming":
        rows = [t for t in rows if t["due_at"] and end <= t["due_at"] < end + timedelta(days=days)]
        return sorted(rows, key=lambda t: (t["due_at"], t["id"]))
    if scope == "blocked":
        rows = [t for t in rows if t["blocked_reason"] is not None]
        return sorted(rows, key=lambda t: (t["blocked_until"] or _FAR, t["id"]))
    # "open": everything not finished
    return sorted(rows, key=lambda t: (t["due_at"] or _FAR, -t["priority"], t["id"]))
