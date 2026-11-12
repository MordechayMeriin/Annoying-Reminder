"""
In-memory stand-ins for the database layer (tasks.*, thread.*) and notifications, plus a scripted
fake LLM. Used by the end-to-end tests and scripts/simulate_tick.py: the real scheduler, coach,
agent, tools and guardrails run unchanged on top of them.
"""

import json
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from app import llm, llm_loop, coach, notify, preferences, sessions, tasks, thread
from app.config import get_settings
from app.logic import (FOCUS_MAX, STALE_COOLDOWN_DAYS, STALE_DAYS, STALE_MAX, ContactState, focus_day, is_parked,
                       may_check_in, next_occurrence)


class FakeStore:
    def __init__(self, now: datetime):
        self.now = now.astimezone(timezone.utc)
        self.tasks: dict[int, dict[str, Any]] = {}
        self.events: list[dict[str, Any]] = []
        self.messages: list[dict[str, Any]] = []
        self.pushes: list[dict[str, Any]] = []
        self.pushovers: list[dict[str, Any]] = []
        self.sessions: list[dict[str, Any]] = []
        self.preferences: list[dict[str, Any]] = []
        self.pushover_mode = "always"   # as if PUSHOVER_TOKEN / PUSHOVER_USER were set
        self._ids = {"task": 0, "msg": 0, "session": 0, "pref": 0}
        self.calls: list[str] = []      # every storage call, in order (for counting round trips)

    # ------------------------------------------------------------ setup helpers

    def focus_day(self):
        s = get_settings()
        return focus_day(self.now.astimezone(s.tz), s.day_reset_time)

    def add_task(self, title: str, *, focus: bool = False, **kw: Any) -> dict[str, Any]:
        """focus=True: in Today's 3 (undated tasks outside it get no check-ins)."""
        if focus:
            kw.setdefault("focus_date", self.focus_day())
        self._ids["task"] += 1
        t = {"id": self._ids["task"], "parent_id": None, "title": title, "description": None, "kind": "task",
             "priority": 1, "status": "open", "due_at": None, "remind_at": None, "remind_requested": False,
             "recurrence": None, "nag_count": 0, "escalated": False, "blocked_reason": None,
             "blocked_until": None, "last_notified_at": None, "created_at": self.now, "completed_at": None,
             "next_action": None, "estimate_min": None, "focus_date": None, "focus_rank": None,
             "resume_note": None, "parked_until": None, "stale_offered_at": None, "avoid_asked_at": None,
             "avoid_asks": 0, "avoid_reset_at": None}
        t.update(kw)
        self.tasks[t["id"]] = t
        return dict(t)

    def advance(self, minutes: int) -> None:
        self.now += timedelta(minutes=minutes)

    # ------------------------------------------------------------ install

    def install(self, setattr_: Callable[[Any, str, Any], None]) -> None:
        for name in ("now_utc", "get", "get_many", "subtasks", "add_event", "recent_events", "event_counts",
                     "subtasks_many", "recent_events_many", "event_counts_many",
                     "create", "update", "complete", "set_followup", "snooze", "block", "unblock", "cancel",
                     "close_finished_meetings", "due_for_contact", "meetings_between", "defer", "lease", "finish_contact",
                     "list_tasks", "set_focus", "focus_today", "done_since", "fence_fired", "avoid_asked",
                     "reset_avoidance", "park", "stale_tasks", "mark_stale_offered"):
            setattr_(tasks, name, self._counted("tasks." + name, getattr(self, "t_" + name), name != "now_utc"))
        for name in ("add", "add_event", "link", "since", "for_tasks", "last_proactive", "recent_task_ids",
                     "user_replied_since", "last_user_reply_at", "last_user_text", "contact_state", "page", "by_ids",
                     "last_kind_at"):
            setattr_(thread, name, self._counted("thread." + name, getattr(self, "m_" + name)))
        for name in ("start", "get", "latest", "open_sessions", "latest_for_task", "end", "set_planned", "claim_end",
                     "close_note", "close_notes_for_task", "stats_since"):
            setattr_(sessions, name, self._counted("sessions." + name, getattr(self, "s_" + name)))
        for name in ("load", "items", "add", "delete"):
            setattr_(preferences, name, getattr(self, "p_" + name))
        setattr_(notify, "send_push", self.send_push)
        setattr_(notify, "send_pushover", self.send_pushover)
        setattr_(notify, "pushover_mode", lambda: self.pushover_mode)

    def _counted(self, label: str, fn: Callable, count: bool = True) -> Callable:
        if not count:
            return fn

        def wrapper(*args, **kwargs):
            # Calls with an empty id list never reach the database in the real code.
            if not (args and isinstance(args[0], (list, tuple)) and not args[0]):
                self.calls.append(label)
            return fn(*args, **kwargs)
        return wrapper

    # ------------------------------------------------------------ notify

    def send_push(self, title, body, *, task_id=None, message_id=None, priority=1, url=None, force=False,
                  actions=None) -> int:
        self.pushes.append({"title": title, "body": notify.push_body(body), "task_id": task_id,
                            "actions": list(actions or []) if task_id is not None else [],
                            "message_id": message_id, "priority": priority,
                            "url": url or (f"/?m={message_id}" if message_id else "/")})
        return 1

    def send_pushover(self, title, message, *, level=0, url=None, expire_s=3600) -> bool:
        self.pushovers.append({"title": title, "message": message, "level": level, "url": url,
                               "expire_s": expire_s})
        return True

    # ------------------------------------------------------------ tasks.*

    def t_now_utc(self):
        return self.now

    def t_get(self, task_id):
        t = self.tasks.get(task_id)
        return dict(t) if t else None

    def t_get_many(self, ids):
        return [dict(self.tasks[i]) for i in ids if i in self.tasks]

    def t_subtasks(self, task_id):
        return [dict(t) for t in self.tasks.values() if t["parent_id"] == task_id and t["status"] != "cancelled"]

    def t_add_event(self, task_id, kind, note=None, avoid_reason=None):
        self.events.append({"task_id": task_id, "kind": kind, "note": note, "avoid_reason": avoid_reason,
                            "created_at": self.now})

    def t_recent_events(self, task_id, limit=8):
        return [e for e in reversed(self.events) if e["task_id"] == task_id][:limit]

    def t_event_counts(self, task_id, days=7):
        out: dict[str, int] = {}
        reset = (self.tasks.get(task_id) or {}).get("avoid_reset_at")
        for e in self.events:
            if (e["task_id"] == task_id and e["created_at"] > self.now - timedelta(days=days)
                    and (reset is None or e["created_at"] > reset)):
                out[e["kind"]] = out.get(e["kind"], 0) + 1
        return out

    def t_subtasks_many(self, ids):
        return {i: self.t_subtasks(i) for i in ids}

    def t_recent_events_many(self, ids, limit=8):
        return {i: [{k: e[k] for k in ("kind", "note", "created_at")} for e in self.t_recent_events(i, limit)]
                for i in ids}

    def t_event_counts_many(self, ids, days=7):
        return {i: self.t_event_counts(i, days) for i in ids}

    def t_create(self, title, *, kind="task", priority=1, description=None, due_at=None, remind_at=None,
                 recurrence=None, parent_id=None, next_action=None, estimate_min=None):
        if remind_at is None and due_at is not None:
            remind_at = due_at - timedelta(minutes=get_settings().meeting_lead_min if kind == "meeting" else 0)
        return self.add_task(title, kind=kind, priority=priority, description=description, due_at=due_at,
                             remind_at=remind_at, remind_requested=remind_at is not None,
                             recurrence=recurrence, parent_id=parent_id, next_action=next_action,
                             estimate_min=estimate_min)

    def t_update(self, task_id, **fields):
        t = self.tasks.get(task_id)
        if t is None:
            return None
        if "description" in fields and t["description"] and t["description"] != fields["description"]:
            self.t_add_event(task_id, "description_changed", t["description"])
        if "remind_at" in fields or "due_at" in fields:
            fields.update(nag_count=0, escalated=False)
            if "remind_at" not in fields and fields.get("due_at") is not None:
                fields["remind_at"] = fields["due_at"]
            fields["remind_requested"] = fields.get("remind_at") is not None
            fields["parked_until"] = None
        t.update(fields)
        return dict(t)

    def t_complete(self, task_id):
        t = self.tasks.get(task_id)
        if t is None or t["status"] != "open":
            return dict(t) if t else None
        self.t_add_event(task_id, "done")
        if t["recurrence"] and t["due_at"]:
            nxt = next_occurrence(t["due_at"], t["recurrence"], get_settings().tz)
            t.update(due_at=nxt, remind_at=nxt, remind_requested=True, nag_count=0)
        else:
            t.update(status="done", completed_at=self.now, remind_at=None)
        t["resume_note"] = None
        return dict(t)

    def t_set_followup(self, task_id, when, requested):
        t = self.tasks.get(task_id)
        if t is None or t["status"] != "open":
            return None
        t.update(remind_at=when, remind_requested=requested, nag_count=0, escalated=False, parked_until=None)
        return dict(t)

    def t_snooze(self, task_id, minutes=60):
        t = self.t_set_followup(task_id, self.now + timedelta(minutes=minutes), True)
        if t:
            self.t_add_event(task_id, "postponed", f"snoozed {minutes} min")
        return t

    def t_block(self, task_id, reason, until):
        t = self.tasks[task_id]
        t.update(blocked_reason=reason, blocked_until=until, remind_at=until, remind_requested=False, nag_count=0)
        self.t_add_event(task_id, "blocked", reason)
        return dict(t)

    def t_unblock(self, task_id):
        self.tasks[task_id].update(blocked_reason=None, blocked_until=None)
        self.t_add_event(task_id, "unblocked")
        return dict(self.tasks[task_id])

    def t_cancel(self, task_id):
        self.tasks[task_id].update(status="cancelled", remind_at=None)
        return dict(self.tasks[task_id])

    def t_close_finished_meetings(self):
        for t in self.tasks.values():
            if (t["status"] == "open" and t["kind"] == "meeting" and not t["recurrence"]
                    and t["due_at"] and t["due_at"] < self.now - timedelta(hours=2)):
                t.update(status="done", completed_at=self.now, remind_at=None)

    def t_due_for_contact(self, today, lapsed=False, limit=20):
        due = [t for t in self.tasks.values() if t["status"] == "open" and t["remind_at"] and t["remind_at"] <= self.now
               and may_check_in(t, self.now, today, lapsed)]
        return [dict(t) for t in sorted(due, key=lambda t: t["remind_at"])][:limit]

    def t_fence_fired(self, task_id):
        t = self.tasks.get(task_id)
        if t is None or t["status"] != "open" or t["kind"] != "fence":
            return dict(t) if t else None
        if t["recurrence"] and t["due_at"]:
            nxt = next_occurrence(t["due_at"], t["recurrence"], get_settings().tz)
            while nxt <= self.now:
                nxt = next_occurrence(nxt, t["recurrence"], get_settings().tz)
            t.update(due_at=nxt, remind_at=nxt, remind_requested=True, nag_count=0, last_notified_at=self.now)
        else:
            t.update(status="done", completed_at=self.now, remind_at=None, last_notified_at=self.now)
        return dict(t)

    def t_avoid_asked(self, task_id, next_at):
        t = self.tasks[task_id]
        t.update(avoid_asks=t["avoid_asks"] + 1, avoid_asked_at=self.now, remind_at=next_at,
                 remind_requested=False, last_notified_at=self.now)

    def t_reset_avoidance(self, task_id):
        self.tasks[task_id].update(avoid_reset_at=self.now, avoid_asks=0, avoid_asked_at=None, parked_until=None)

    def t_park(self, task_id, until):
        t = self.tasks.get(task_id)
        if t is None or t["status"] != "open":
            return None
        t.update(parked_until=until, remind_at=until, remind_requested=False, nag_count=0, escalated=False,
                 focus_date=None, focus_rank=None, avoid_reset_at=self.now, avoid_asks=0, avoid_asked_at=None)
        self.t_add_event(task_id, "parked", f"until {until.astimezone(get_settings().tz):%Y-%m-%d}")
        return dict(t)

    def t_stale_tasks(self, limit=STALE_MAX):
        old = self.now - timedelta(days=STALE_DAYS)
        moving = {e["task_id"] for e in self.events if e["kind"] in tasks.PROGRESS_KINDS and e["created_at"] > old}
        rows = [t for t in self.tasks.values()
                if t["status"] == "open" and t["kind"] == "task" and not t["parent_id"] and not t["recurrence"]
                and t["created_at"] < old and (t["due_at"] is None or t["due_at"] < self.now)
                and not (t["blocked_until"] and t["blocked_until"] > self.now) and not is_parked(t, self.now)
                and (t["stale_offered_at"] is None
                     or t["stale_offered_at"] < self.now - timedelta(days=STALE_COOLDOWN_DAYS))
                and t["id"] not in moving]
        return [dict(t) for t in sorted(rows, key=lambda t: (t["created_at"], t["id"]))][:limit]

    def t_mark_stale_offered(self, ids):
        for i in ids:
            self.tasks[i]["stale_offered_at"] = self.now

    # ------------------------------------------------------------ preferences.*

    def p_load(self):
        pass

    def p_items(self):
        return [dict(p) for p in self.preferences]

    def p_add(self, text):
        text = " ".join((text or "").split())[:preferences.MAX_CHARS]
        if not text:
            raise ValueError("The preference is empty.")
        if len(self.preferences) >= preferences.MAX_ITEMS:
            raise ValueError("There are already too many saved preferences.")
        self._ids["pref"] += 1
        row = {"id": self._ids["pref"], "text": text, "created_at": self.now}
        self.preferences.append(row)
        return dict(row)

    def p_delete(self, pref_id):
        before = len(self.preferences)
        self.preferences = [p for p in self.preferences if p["id"] != pref_id]
        return len(self.preferences) < before

    def t_set_focus(self, task_ids, today):
        ids = list(dict.fromkeys(task_ids))[:FOCUS_MAX]
        for t in self.tasks.values():
            if t["focus_date"] == today and t["id"] not in ids:
                t.update(focus_date=None, focus_rank=None)
        out = []
        for rank, i in enumerate(ids, 1):
            t = self.tasks.get(i)
            if t and t["status"] == "open" and t["kind"] == "task":
                t.update(focus_date=today, focus_rank=rank)
                out.append(dict(t))
        return out

    def t_focus_today(self, today):
        rows = sorted((t for t in self.tasks.values() if t["focus_date"] == today and t["status"] != "cancelled"),
                      key=lambda t: (t["focus_rank"] or 99, t["id"]))
        start = datetime.combine(today, datetime.min.time(), get_settings().tz)
        done = {t["id"] for t in rows if t["status"] == "done" or any(
            e["task_id"] == t["id"] and e["kind"] == "done" and e["created_at"] >= start for e in self.events)}
        return [dict(t) for t in rows], done

    def t_done_since(self, start):
        out: dict[int, dict] = {}
        for e in self.events:
            t = self.tasks.get(e["task_id"])
            if e["kind"] == "done" and e["created_at"] >= start and t and t["status"] != "cancelled":
                out[t["id"]] = {"id": t["id"], "title": t["title"], "parent_id": t["parent_id"],
                                "done_at": e["created_at"]}
        return sorted(out.values(), key=lambda r: r["done_at"])

    def t_meetings_between(self, start, end, kinds=("meeting",)):
        rows = [t for t in self.tasks.values() if t["status"] == "open" and t["kind"] in kinds
                and t["due_at"] and t["due_at"] < end and (t["due_at"] >= start or t["recurrence"])]
        return [dict(t) for t in sorted(rows, key=lambda t: t["due_at"])]

    def t_defer(self, task_id, until):
        t = self.tasks[task_id]
        if t["status"] == "open" and t["remind_at"] and t["remind_at"] <= self.now:
            t["remind_at"] = until

    def t_lease(self, ids, minutes=15):
        out = []
        for i in ids:
            t = self.tasks.get(i)
            if t and t["status"] == "open" and t["remind_at"] and t["remind_at"] <= self.now:
                t["remind_at"] = self.now + timedelta(minutes=minutes)
                out.append(i)
        return out

    def t_finish_contact(self, task_id, nag_count, next_at, requested, escalate):
        t = self.tasks[task_id]
        t.update(nag_count=nag_count, last_notified_at=self.now, remind_at=next_at, remind_requested=requested,
                 escalated=t["escalated"] or escalate)

    def t_list_tasks(self, scope="open", days=7):
        tz = get_settings().tz
        start = self.now.astimezone(tz).replace(hour=0, minute=0, second=0, microsecond=0)
        end = start + timedelta(days=1)
        open_ = [t for t in self.tasks.values() if t["status"] == "open"]
        if scope == "today":
            rows = [t for t in open_ if (t["due_at"] and t["due_at"] < end) or (t["remind_at"] and t["remind_at"] < end)]
        elif scope == "overdue":
            rows = [t for t in open_ if t["due_at"] and t["due_at"] < self.now]
        elif scope == "upcoming":
            rows = [t for t in open_ if t["due_at"] and end <= t["due_at"] < end + timedelta(days=days)]
        elif scope == "done_today":
            rows = [t for t in self.tasks.values() if t["status"] == "done" and t["completed_at"] and t["completed_at"] >= start]
        elif scope == "blocked":
            rows = [t for t in open_ if t["blocked_reason"]]
        else:
            rows = open_
        return [dict(t) for t in rows]

    # ------------------------------------------------------------ thread.*

    def m_add(self, role, content, kind="chat", task_ids=(), chips=None, model=None):
        self._ids["msg"] += 1
        ids = list(dict.fromkeys(task_ids))
        m = {"id": self._ids["msg"], "role": role, "content": content, "kind": kind,
             "task_id": ids[0] if ids else None, "task_ids": ids, "chips": list(chips or []), "model": model,
             "created_at": self.now}
        self.messages.append(m)
        return dict(m)

    def m_add_event(self, text, task_ids=()):
        return self.m_add("user", text, "event", task_ids)

    def m_link(self, message_ids, task_ids):
        ids = list(dict.fromkeys(task_ids))
        for m in self.messages:
            if m["id"] in message_ids:
                m.update(task_ids=ids, task_id=ids[0] if ids else None)

    def m_since(self, start, limit=40):
        return [dict(m) for m in self.messages if m["created_at"] >= start][-limit:]

    def m_for_tasks(self, task_ids, limit=6):
        return [dict(m) for m in self.messages if set(m["task_ids"]) & set(task_ids)][-limit:]

    def m_last_proactive(self):
        rows = [m for m in self.messages if m["kind"] in thread.PROACTIVE_KINDS]
        return dict(rows[-1]) if rows else None

    def m_recent_task_ids(self, start, last_n=6):
        out: list[int] = []
        for m in reversed(self.m_since(start, last_n)):
            for i in m["task_ids"]:
                if i not in out:
                    out.append(i)
        return out

    def m_user_replied_since(self, t):
        return any(m["role"] == "user" and m["kind"] in thread.ACTIVITY_KINDS and m["created_at"] > t
                   for m in self.messages)

    def m_last_user_reply_at(self):
        rows = [m for m in self.messages if m["role"] == "user" and m["kind"] in thread.ACTIVITY_KINDS]
        return rows[-1]["created_at"] if rows else None

    def m_last_kind_at(self, kind):
        rows = [m for m in self.messages if m["kind"] == kind]
        return rows[-1]["created_at"] if rows else None

    def m_last_user_text(self):
        rows = [m for m in self.messages if m["role"] == "user" and m["kind"] == "chat"]
        return rows[-1]["content"] if rows else None

    def m_contact_state(self, day_start):
        pro = [m for m in self.messages if m["kind"] in thread.PROACTIVE_KINDS]
        by_task: dict[int, int] = {}
        for m in pro:
            if m["kind"] == "nudge" and m["created_at"] >= day_start:
                for i in m["task_ids"]:
                    by_task[i] = by_task.get(i, 0) + 1
        return ContactState(
            last_proactive_at=pro[-1]["created_at"] if pro else None,
            sent_today=sum(1 for m in pro if m["created_at"] >= day_start),
            sent_today_by_task=by_task,
        )

    def _public(self, m):
        main = self.tasks.get(m["task_id"]) if m["task_id"] else None
        return {**m, "task_title": main["title"] if main else None, "task_status": main["status"] if main else None,
                "task_titles": {str(i): self.tasks[i]["title"] for i in m["task_ids"] if i in self.tasks}}

    def m_page(self, before_id=None, limit=50, after_id=None):
        rows = [m for m in self.messages if (before_id is None or m["id"] < before_id)
                and (after_id is None or m["id"] > after_id)]
        return [self._public(m) for m in rows[-limit:]]

    def m_by_ids(self, ids):
        return [self._public(m) for m in self.messages if m["id"] in ids]


# ------------------------------------------------------------ sessions.*  (on FakeStore)

def _s_start(self, task_id, planned_min):
    self._ids["session"] += 1
    row = {"id": self._ids["session"], "task_id": task_id, "started_at": self.now, "planned_min": planned_min,
           "ended_at": None, "outcome": None, "end_sent_at": None, "note_closed_at": None}
    self.sessions.append(row)
    return dict(row)


def _s_get(self, session_id):
    return next((dict(x) for x in self.sessions if x["id"] == session_id), None)


def _s_latest(self):
    rows = sorted(self.sessions, key=lambda x: (x["started_at"], x["id"]))
    return dict(rows[-1]) if rows else None


def _s_open_sessions(self):
    return [dict(x) for x in self.sessions if x["ended_at"] is None]


def _s_latest_for_task(self, task_id):
    rows = sorted((x for x in self.sessions if x["task_id"] == task_id), key=lambda x: (x["started_at"], x["id"]))
    return dict(rows[-1]) if rows else None


def _s_end(self, session_id, outcome, ended_at=None):
    row = next((x for x in self.sessions if x["id"] == session_id and x["ended_at"] is None), None)
    if row is None:
        return None
    row.update(ended_at=ended_at or self.now, outcome=outcome)
    return dict(row)


def _s_set_planned(self, session_id, planned_min):
    row = next((x for x in self.sessions if x["id"] == session_id and x["ended_at"] is None), None)
    if row is None:
        return None
    row.update(planned_min=planned_min, end_sent_at=None)
    return dict(row)


def _s_claim_end(self, session_id):
    row = next((x for x in self.sessions if x["id"] == session_id), None)
    if row is None or row["ended_at"] is not None or row["end_sent_at"] is not None:
        return False
    row["end_sent_at"] = self.now
    return True


def _s_close_note(self, session_id):
    for x in self.sessions:
        if x["id"] == session_id and x["note_closed_at"] is None:
            x["note_closed_at"] = self.now


def _s_close_notes_for_task(self, task_id):
    for x in self.sessions:
        if x["task_id"] == task_id and x["note_closed_at"] is None:
            x["note_closed_at"] = self.now


def _s_stats_since(self, start, now):
    from app.logic import actual_minutes
    rows = [x for x in self.sessions if x["started_at"] >= start]
    return len(rows), sum(actual_minutes(x, now) for x in rows)


for _name, _fn in list(globals().items()):
    if _name.startswith("_s_"):
        setattr(FakeStore, _name[1:], _fn)


class ScriptedLLM:
    """
    Replaces chat_completion. Give it a list of steps; each step is either
      - a str (assistant text),
      - a dict {"tool": name, "args": {...}} (a proper tool call),
      - an Exception instance (raised, e.g. llm.LLMUnavailable("429")).
    Records every request it received. Every answer reports `model` as the model that answered,
    like OpenRouter does (llm.track_models sees it).
    """

    def __init__(self, steps: list[Any], model: str = "test/model"):
        self.steps = list(steps)
        self.model = model
        self.requests: list[dict[str, Any]] = []

    async def __call__(self, messages, tools=None, temperature=0.3, max_tokens=None, timeout=None):
        self.requests.append({"messages": [dict(m) for m in messages], "tools": tools})
        if not self.steps:
            llm.record_model(self.model)
            return {"role": "assistant", "content": ""}
        step = self.steps.pop(0)
        if isinstance(step, BaseException):
            raise step
        llm.record_model(self.model)
        if isinstance(step, dict):
            return {"role": "assistant", "content": "", "tool_calls": [{
                "id": f"call_{len(self.requests)}", "type": "function",
                "function": {"name": step["tool"], "arguments": json.dumps(step["args"], ensure_ascii=False)},
            }]}
        return {"role": "assistant", "content": step}

    def install(self, setattr_: Callable[[Any, str, Any], None]) -> None:
        setattr_(llm_loop, "chat_completion", self)
        setattr_(coach, "chat_completion", self)
        setattr_(llm, "chat_completion", self)
