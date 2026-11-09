"""
In-memory stand-ins for the database layer (tasks.*, thread.*) and notifications, plus a scripted
fake LLM. Used by the end-to-end tests and scripts/simulate_tick.py: the real scheduler, coach,
agent, tools and guardrails run unchanged on top of them.
"""

import json
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

from app import llm, llm_loop, coach, notify, tasks, thread
from app.config import get_settings
from app.logic import ContactState, next_occurrence


class FakeStore:
    def __init__(self, now: datetime):
        self.now = now.astimezone(timezone.utc)
        self.tasks: dict[int, dict[str, Any]] = {}
        self.events: list[dict[str, Any]] = []
        self.messages: list[dict[str, Any]] = []
        self.pushes: list[dict[str, Any]] = []
        self.pushovers: list[dict[str, Any]] = []
        self._ids = {"task": 0, "msg": 0}

    # ------------------------------------------------------------ setup helpers

    def add_task(self, title: str, **kw: Any) -> dict[str, Any]:
        self._ids["task"] += 1
        t = {"id": self._ids["task"], "parent_id": None, "title": title, "description": None, "kind": "task",
             "priority": 1, "status": "open", "due_at": None, "remind_at": None, "remind_requested": False,
             "recurrence": None, "nag_count": 0, "escalated": False, "blocked_reason": None,
             "blocked_until": None, "last_notified_at": None, "created_at": self.now, "completed_at": None}
        t.update(kw)
        self.tasks[t["id"]] = t
        return dict(t)

    def advance(self, minutes: int) -> None:
        self.now += timedelta(minutes=minutes)

    # ------------------------------------------------------------ install

    def install(self, setattr_: Callable[[Any, str, Any], None]) -> None:
        for name in ("now_utc", "get", "get_many", "subtasks", "add_event", "recent_events", "event_counts",
                     "create", "update", "complete", "set_followup", "snooze", "block", "unblock", "cancel",
                     "close_finished_meetings", "due_for_contact", "defer", "lease", "finish_contact",
                     "list_tasks"):
            setattr_(tasks, name, getattr(self, "t_" + name))
        for name in ("add", "add_event", "link", "since", "for_tasks", "last_proactive", "recent_task_ids",
                     "user_replied_since", "contact_state", "page"):
            setattr_(thread, name, getattr(self, "m_" + name))
        setattr_(notify, "send_push", self.send_push)
        setattr_(notify, "send_pushover", self.send_pushover)

    # ------------------------------------------------------------ notify

    def send_push(self, title, body, *, task_id=None, message_id=None, priority=1, url=None) -> int:
        self.pushes.append({"title": title, "body": notify.push_body(body), "task_id": task_id,
                            "message_id": message_id, "url": url or (f"/?m={message_id}" if message_id else "/")})
        return 1

    def send_pushover(self, title, message, *, emergency=False, url=None) -> bool:
        self.pushovers.append({"title": title, "message": message, "emergency": emergency})
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

    def t_add_event(self, task_id, kind, note=None):
        self.events.append({"task_id": task_id, "kind": kind, "note": note, "created_at": self.now})

    def t_recent_events(self, task_id, limit=8):
        return [e for e in reversed(self.events) if e["task_id"] == task_id][:limit]

    def t_event_counts(self, task_id, days=7):
        out: dict[str, int] = {}
        for e in self.events:
            if e["task_id"] == task_id and e["created_at"] > self.now - timedelta(days=days):
                out[e["kind"]] = out.get(e["kind"], 0) + 1
        return out

    def t_create(self, title, *, kind="task", priority=1, description=None, due_at=None, remind_at=None,
                 recurrence=None, parent_id=None):
        if remind_at is None and due_at is not None:
            remind_at = due_at - timedelta(minutes=get_settings().meeting_lead_min if kind == "meeting" else 0)
        return self.add_task(title, kind=kind, priority=priority, description=description, due_at=due_at,
                             remind_at=remind_at, remind_requested=remind_at is not None,
                             recurrence=recurrence, parent_id=parent_id)

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
        return dict(t)

    def t_set_followup(self, task_id, when, requested):
        t = self.tasks.get(task_id)
        if t is None or t["status"] != "open":
            return None
        t.update(remind_at=when, remind_requested=requested, nag_count=0, escalated=False)
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

    def t_due_for_contact(self, limit=20):
        due = [t for t in self.tasks.values() if t["status"] == "open" and t["remind_at"] and t["remind_at"] <= self.now]
        return [dict(t) for t in sorted(due, key=lambda t: t["remind_at"])][:limit]

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

    def m_add(self, role, content, kind="chat", task_ids=()):
        self._ids["msg"] += 1
        ids = list(dict.fromkeys(task_ids))
        m = {"id": self._ids["msg"], "role": role, "content": content, "kind": kind,
             "task_id": ids[0] if ids else None, "task_ids": ids, "created_at": self.now}
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
        return any(m["role"] == "user" and m["kind"] == "chat" and m["created_at"] > t for m in self.messages)

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

    def m_page(self, before_id=None, limit=50):
        rows = [m for m in self.messages if before_id is None or m["id"] < before_id]
        return [dict(m) for m in rows[-limit:]]


class ScriptedLLM:
    """
    Replaces chat_completion. Give it a list of steps; each step is either
      - a str (assistant text),
      - a dict {"tool": name, "args": {...}} (a proper tool call),
      - an Exception instance (raised, e.g. llm.LLMUnavailable("429")).
    Records every request it received.
    """

    def __init__(self, steps: list[Any]):
        self.steps = list(steps)
        self.requests: list[dict[str, Any]] = []

    async def __call__(self, messages, tools=None, temperature=0.3, max_tokens=None, timeout=None):
        self.requests.append({"messages": [dict(m) for m in messages], "tools": tools})
        if not self.steps:
            return {"role": "assistant", "content": ""}
        step = self.steps.pop(0)
        if isinstance(step, BaseException):
            raise step
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
