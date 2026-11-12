"""
Tools the LLM can call, shared by the chat agent and the check-in coach.

Built for a mid-size open model: flat parameters, enums, forgiving validation (numbers as strings,
empty optional strings, unknown keys) and a parser for tool calls written as text.
"""

import ast
import json
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Callable

from . import actions, sessions, tasks
from .config import get_settings
from .logic import FOCUS_MAX, Guardrails, clamp_followup, focus_day, parse_local_datetime


class ToolArgError(ValueError):
    """Bad arguments: the message goes back to the model so it can retry once."""


@dataclass
class ToolContext:
    mode: str                                   # "chat", "coach" (check-ins) or "morning" (the morning plan)
    now: datetime                               # aware
    acted_on: list[int] = field(default_factory=list)            # task ids touched (for linking)
    followups: dict[int, datetime] = field(default_factory=dict) # coach mode: chosen next check-ins
    changed: bool = False
    focus: list[int] | None = None              # set_focus was called: Today's 3, in order
    closed: set[int] = field(default_factory=set)  # tasks completed or cancelled in this turn
    created: dict[int, str] = field(default_factory=dict)  # tasks added in this turn: id → kind

    def touched(self, task_id: int) -> None:
        if task_id not in self.acted_on:
            self.acted_on.append(task_id)


def _fn(name: str, description: str, props: dict[str, Any], required: list[str]) -> dict[str, Any]:
    return {"type": "function", "function": {
        "name": name, "description": description,
        "parameters": {"type": "object", "properties": props, "required": required},
    }}


_ISO = "Local ISO datetime, e.g. 2026-11-09T14:00"
_ID = {"type": "integer"}
_NEXT_ACTION = {"type": "string", "description": (
    "REQUIRED for tasks: the very first physical step, verb first, 15 minutes or less, in the user's "
    "language (e.g. 'Find last year's PDF in email'). Infer it yourself; never ask. Over 45 minutes in "
    "total: the first 15-minute chunk.")}
_ESTIMATE = {"type": "integer", "description": "Your guess of the whole task's length in minutes. Infer it; never ask."}

SCHEDULE_FOLLOWUP_PROPS = {
    "task_id": _ID,
    "minutes_from_now": {"type": "integer", "description": "When to check in next, in minutes. Use this OR at_local_iso."},
    "at_local_iso": {"type": "string", "description": _ISO},
    "reason": {"type": "string", "description": "Short reason, e.g. 'after the user's meeting'"},
}

CHAT_TOOLS: list[dict[str, Any]] = [
    _fn("add_task", "Save a new task, meeting or deadline.", {
        "title": {"type": "string", "description": "Short title in the user's language"},
        "kind": {"type": "string", "enum": ["task", "meeting"]},
        "due_at": {"type": "string", "description": "Deadline / meeting start. " + _ISO},
        "remind_at": {"type": "string", "description": "Optional explicit reminder time. " + _ISO},
        "priority": {"type": "integer", "enum": [1, 2, 3]},
        "recurrence": {"type": "string", "enum": ["daily", "weekdays", "weekly", "monthly"]},
        "description": {"type": "string", "description": "What the task is about and anything useful the user said"},
        "next_action": _NEXT_ACTION,
        "estimate_min": _ESTIMATE,
    }, ["title"]),
    _fn("list_tasks", "List tasks. Use it to find the right id before changing a task.", {
        "scope": {"type": "string", "enum": ["today", "overdue", "upcoming", "open", "done_today", "blocked"]},
    }, ["scope"]),
    _fn("update_task", "Change a task. Rewrite `description` whenever the user tells you something new about "
        "the task (keep every fact that still matters). unblock=true clears a blocker.", {
        "id": _ID,
        "title": {"type": "string"},
        "description": {"type": "string", "description": "The full new description (max ~800 chars)"},
        "due_at": {"type": "string", "description": _ISO},
        "remind_at": {"type": "string", "description": _ISO},
        "priority": {"type": "integer", "enum": [1, 2, 3]},
        "recurrence": {"type": "string", "enum": ["daily", "weekdays", "weekly", "monthly", "none"]},
        "kind": {"type": "string", "enum": ["task", "meeting"]},
        "next_action": {"type": "string", "description": "A new first step (e.g. the user corrected yours)"},
        "estimate_min": _ESTIMATE,
        "resume_note": {"type": "string", "description": "Where the user stopped, in their words ('page 3', "
                        "'row 40'); shown next time they start. Cleared automatically when the task is done."},
        "unblock": {"type": "boolean"},
    }, ["id"]),
    _fn("complete_task", "Mark a task as done.", {"id": _ID}, ["id"]),
    _fn("cancel_task", "Cancel a task that is no longer relevant.", {"id": _ID}, ["id"]),
    _fn("schedule_followup", "Set when to check in with the user about a task next (also: snooze / 'remind me "
        "later'). requested_by=user when the user asked for that time.", {
        **SCHEDULE_FOLLOWUP_PROPS,
        "requested_by": {"type": "string", "enum": ["user", "assistant"]},
    }, ["task_id", "reason", "requested_by"]),
    _fn("add_subtask", "Add a small step under a task (to break a big task down).", {
        "task_id": _ID,
        "title": {"type": "string", "description": "A concrete step, 10-30 minutes"},
        "due_at": {"type": "string", "description": "Optional. " + _ISO},
    }, ["task_id", "title"]),
    _fn("log_interaction", "Record how the user relates to a task right now: postponed ('later'), committed "
        "('I'll do it at 5'), progress (did part of it), stuck (doesn't know how to continue).", {
        "task_id": _ID,
        "kind": {"type": "string", "enum": ["postponed", "committed", "progress", "stuck"]},
        "note": {"type": "string", "description": "Short, in the user's words"},
    }, ["task_id", "kind", "note"]),
    _fn("mark_blocked", "The task can't move until something external happens (waiting for someone/something).", {
        "task_id": _ID,
        "reason": {"type": "string"},
        "followup_minutes": {"type": "integer", "description": "When to check whether it's unblocked"},
    }, ["task_id", "reason", "followup_minutes"]),
]

SET_FOCUS = _fn("set_focus", "Set Today's 3: up to three task ids, most important first. Replaces today's "
                "earlier pick. No meetings.", {
    "task_ids": {"type": "array", "items": {"type": "integer"}, "description": "1 to 3 task ids, first = do first"},
}, ["task_ids"])

CHAT_TOOLS.append(SET_FOCUS)

LIST_TASKS = next(t for t in CHAT_TOOLS if t["function"]["name"] == "list_tasks")

# The morning plan picks Today's 3.
MORNING_TOOLS: list[dict[str, Any]] = [SET_FOCUS, LIST_TASKS]

COACH_TOOLS: list[dict[str, Any]] = [
    _fn("schedule_followup", "Optional: choose when to check in about this task next, if a different time than "
        "usual makes sense (e.g. after a meeting, tomorrow morning).", SCHEDULE_FOLLOWUP_PROPS,
        ["task_id", "reason"]),
]

_SCHEMAS = {t["function"]["name"]: t["function"]["parameters"] for t in CHAT_TOOLS}

# Tools allowed per mode (chat: all of CHAT_TOOLS).
_MODE_TOOLS = {"coach": {"schedule_followup"}, "morning": {"set_focus", "list_tasks"}}


# ================================================================ validation

def validate(name: str, args: Any, schema: dict[str, Any] | None = None) -> dict[str, Any]:
    """Coerce and check arguments against the (flat) schema. Raises ToolArgError."""
    schema = schema or _SCHEMAS.get(name)
    if schema is None:
        raise ToolArgError(f"Unknown tool '{name}'.")
    if not isinstance(args, dict):
        raise ToolArgError("Arguments must be a JSON object.")
    props, out = schema["properties"], {}
    for key, value in args.items():
        spec = props.get(key)
        if spec is None or value is None or (isinstance(value, str) and value.strip() == ""):
            continue  # ignore unknown keys and empty optionals
        out[key] = _coerce(key, value, spec)
    missing = [k for k in schema.get("required", []) if k not in out]
    if missing:
        raise ToolArgError(f"Missing required argument(s): {', '.join(missing)}.")
    return out


def _coerce(key: str, value: Any, spec: dict[str, Any]) -> Any:
    typ = spec.get("type")
    if typ == "integer":
        if isinstance(value, bool):
            raise ToolArgError(f"'{key}' must be an integer.")
        try:
            f = float(value)
        except (TypeError, ValueError):
            raise ToolArgError(f"'{key}' must be an integer, got {value!r}.") from None
        if f != int(f):
            raise ToolArgError(f"'{key}' must be a whole number.")
        value = int(f)
    elif typ == "boolean":
        if isinstance(value, str) and value.lower() in ("true", "false"):
            value = value.lower() == "true"
        if not isinstance(value, bool):
            raise ToolArgError(f"'{key}' must be true or false.")
    elif typ == "string":
        if isinstance(value, (dict, list)):
            raise ToolArgError(f"'{key}' must be a string.")
        value = str(value).strip()
    elif typ == "array":
        if isinstance(value, str):  # "3, 5, 7" or "[3,5,7]"
            value = [v for v in re.split(r"[\s,\[\]]+", value) if v]
        elif not isinstance(value, list):
            value = [value]
        item = spec.get("items", {})
        value = [_coerce(key, v, item) for v in value]
    if "enum" in spec and value not in spec["enum"]:
        raise ToolArgError(f"'{key}' must be one of {spec['enum']}, got {value!r}.")
    return value


# ================================================================ tool calls written as text

FENCE = re.compile(r"```(?:json|tool_code|python|tool_call)?\s*(.*?)```", re.S)


def parse_text_tool_call(content: str | None, names: set[str]) -> tuple[str, dict[str, Any]] | None:
    """
    If the model wrote a tool call as text instead of `tool_calls`, recover it once.
    Accepts JSON ({"name":..., "arguments":{...}} and common variants) and Python-style calls
    (`add_task(title="x")`, as Gemma writes in tool_code blocks).
    """
    if not content or not names:
        return None
    candidates = [m.group(1) for m in FENCE.finditer(content)] + [content]
    for text in candidates:
        text = text.strip()
        found = _from_json(text, names) or _from_python(text, names)
        if found:
            return found
    return None


def _from_json(text: str, names: set[str]) -> tuple[str, dict[str, Any]] | None:
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        obj = json.loads(text[start:end + 1])
    except ValueError:
        return None
    for _ in range(2):  # unwrap {"tool_call": {...}} / {"function": {...}}
        if isinstance(obj, dict) and len(obj) == 1 and isinstance(next(iter(obj.values())), dict) \
                and next(iter(obj)) in ("tool_call", "function", "call"):
            obj = next(iter(obj.values()))
    if not isinstance(obj, dict):
        return None
    name = obj.get("name") or obj.get("tool") or obj.get("function")
    args = obj.get("arguments", obj.get("parameters", obj.get("args", {})))
    if isinstance(args, str):
        try:
            args = json.loads(args)
        except ValueError:
            return None
    if isinstance(name, str) and name in names and isinstance(args, dict):
        return name, args
    return None


def _from_python(text: str, names: set[str]) -> tuple[str, dict[str, Any]] | None:
    pattern = re.compile(r"\b(" + "|".join(map(re.escape, sorted(names))) + r")\s*\(")
    m = pattern.search(text)
    if not m:
        return None
    # take the call up to its matching parenthesis
    depth, i = 0, m.end() - 1
    for i in range(m.end() - 1, len(text)):
        depth += {"(": 1, ")": -1}.get(text[i], 0)
        if depth == 0:
            break
    try:
        call = ast.parse(text[m.start():i + 1], mode="eval").body
        if not isinstance(call, ast.Call) or call.args:
            return None
        return m.group(1), {kw.arg: ast.literal_eval(kw.value) for kw in call.keywords if kw.arg}
    except (SyntaxError, ValueError):
        return None


# ================================================================ execution

def _guardrails() -> Guardrails:
    s = get_settings()
    return Guardrails(quiet_start=s.quiet_start, quiet_end=s.quiet_end, day_reset=s.day_reset_time,
                      followup_min_min=s.followup_min_min, followup_max_days=s.followup_max_days)


def _dt(value: str | None, key: str) -> datetime | None:
    try:
        return parse_local_datetime(value, get_settings().tz)
    except ValueError:
        raise ToolArgError(f"'{key}' must be a local ISO datetime like 2026-11-09T14:00, got {value!r}.") from None


def _task(task_id: int) -> dict[str, Any]:
    task = tasks.get(task_id)
    if task is None:
        raise ToolArgError(f"No task with id {task_id}. Use list_tasks to find the right id.")
    return task


def brief(task: dict[str, Any] | None) -> dict[str, Any] | None:
    if task is None:
        return None
    t = tasks.to_public(task)
    keys = ("id", "parent_id", "title", "kind", "priority", "status", "due_at", "remind_at", "recurrence",
            "blocked_reason", "blocked_until", "next_action", "estimate_min", "focus_date", "resume_note")
    return {k: t[k] for k in keys if t.get(k) is not None}


def execute(name: str, raw_args: Any, ctx: ToolContext) -> Any:
    """Validate and run one tool call. Raises ToolArgError for problems the model can fix."""
    schema = None
    allowed = _MODE_TOOLS.get(ctx.mode)
    if allowed is not None and name not in allowed:
        raise ToolArgError(f"Tool '{name}' is not available here.")
    if ctx.mode == "coach":
        schema = COACH_TOOLS[0]["function"]["parameters"]
    args = validate(name, raw_args, schema)
    return _HANDLERS[name](args, ctx)


def _estimate(a: dict[str, Any]) -> int | None:
    est = a.get("estimate_min")
    if est is not None and not 1 <= est <= 10000:
        raise ToolArgError("'estimate_min' must be between 1 and 10000 minutes.")
    return est


def _add_task(a: dict[str, Any], ctx: ToolContext) -> Any:
    kind = a.get("kind", "task")
    t = tasks.create(a["title"], kind=kind, priority=a.get("priority", 1),
                     description=a.get("description"), due_at=_dt(a.get("due_at"), "due_at"),
                     remind_at=_dt(a.get("remind_at"), "remind_at"), recurrence=a.get("recurrence"),
                     next_action=a.get("next_action"), estimate_min=_estimate(a))
    ctx.created[t["id"]] = t["kind"]
    ctx.touched(t["id"])
    ctx.changed = True
    out = brief(t)
    if kind == "task" and not t.get("next_action"):
        # Saved anyway (never lose what the user said), but every task needs its first step.
        out["note"] = ("Saved without a next_action. Now call update_task with next_action: the very first "
                       "physical step, verb first, 15 minutes or less. Infer it; don't ask the user.")
    return out


def _list_tasks(a: dict[str, Any], ctx: ToolContext) -> Any:
    return [brief(t) for t in tasks.list_tasks(a["scope"])][:30]


def _update_task(a: dict[str, Any], ctx: ToolContext) -> Any:
    task_id = a.pop("id")
    _task(task_id)
    unblock = a.pop("unblock", False)
    for key in ("due_at", "remind_at"):
        if key in a:
            a[key] = _dt(a[key], key)
    if a.get("recurrence") == "none":
        a["recurrence"] = None
    _estimate(a)
    t = tasks.update(task_id, **a) if a else tasks.get(task_id)
    if a.get("resume_note"):
        sessions.close_notes_for_task(task_id)  # saved from the chat: the app needn't ask "where did you stop?"
    if unblock:
        t = tasks.unblock(task_id)
    ctx.touched(task_id)
    ctx.changed = True
    return brief(t)


def _complete_task(a: dict[str, Any], ctx: ToolContext) -> Any:
    _task(a["id"])
    ctx.touched(a["id"])
    ctx.closed.add(a["id"])
    ctx.changed = True
    actions.close_task_sessions(a["id"], "done")
    return brief(tasks.complete(a["id"]))


def _cancel_task(a: dict[str, Any], ctx: ToolContext) -> Any:
    _task(a["id"])
    ctx.touched(a["id"])
    ctx.closed.add(a["id"])
    ctx.changed = True
    actions.close_task_sessions(a["id"], "stopped")
    return brief(tasks.cancel(a["id"]))


def _set_focus(a: dict[str, Any], ctx: ToolContext) -> Any:
    ids = list(dict.fromkeys(a["task_ids"]))
    if not ids:
        raise ToolArgError("Give 1 to 3 task ids.")
    if len(ids) > FOCUS_MAX:
        raise ToolArgError(f"At most {FOCUS_MAX} tasks: pick the most important ones.")
    found = {t["id"]: t for t in tasks.get_many(ids)}
    bad = [i for i in ids if i not in found or found[i]["status"] != "open" or found[i]["kind"] != "task"
           or found[i].get("parent_id")]
    if bad:
        raise ToolArgError(f"Not open top-level tasks (meetings can't be focus tasks): {bad}. "
                           "Use list_tasks to find the right ids.")
    s = get_settings()
    rows = tasks.set_focus(ids, focus_day(ctx.now.astimezone(s.tz), s.day_reset_time))
    ctx.focus = [t["id"] for t in rows]
    for i in ctx.focus:
        ctx.touched(i)
    ctx.changed = True
    return {"focus": [brief(t) for t in rows]}


def resolve_followup(now: datetime, minutes: int | None, at_iso: str | None, g: Guardrails) -> tuple[datetime, bool]:
    """Turn the tool arguments into a clamped time. Returns (time, was_clamped)."""
    if (minutes is None) == (at_iso is None):
        raise ToolArgError("Give exactly one of minutes_from_now or at_local_iso.")
    wanted = now + timedelta(minutes=minutes) if minutes is not None else _dt(at_iso, "at_local_iso")
    when = clamp_followup(now, wanted, g)
    return when, when != wanted


def _schedule_followup(a: dict[str, Any], ctx: ToolContext) -> Any:
    task = _task(a["task_id"])
    if task["kind"] == "meeting":
        raise ToolArgError("Meeting reminders are scheduled automatically; use update_task to change the time.")
    if task["status"] != "open":
        raise ToolArgError(f"Task {task['id']} is {task['status']}.")
    when, clamped = resolve_followup(ctx.now, a.get("minutes_from_now"), a.get("at_local_iso"), _guardrails())
    if ctx.mode == "coach":
        ctx.followups[task["id"]] = when  # the scheduler applies it after the message is sent
    else:
        requested = a.get("requested_by") == "user"
        tasks.set_followup(task["id"], when, requested)
        if requested:
            tasks.add_event(task["id"], "postponed", a.get("reason"))
        ctx.changed = True
    ctx.touched(task["id"])
    out = {"task_id": task["id"], "next_checkin": when.astimezone(get_settings().tz).isoformat(timespec="minutes")}
    if clamped:
        out["note"] = "Time adjusted to the allowed range (10 minutes to 7 days)."
    return out


def _add_subtask(a: dict[str, Any], ctx: ToolContext) -> Any:
    parent = _task(a["task_id"])
    t = tasks.create(a["title"], priority=parent["priority"], due_at=_dt(a.get("due_at"), "due_at"),
                     parent_id=parent["id"])
    ctx.touched(parent["id"])
    ctx.changed = True
    return brief(t)


def _log_interaction(a: dict[str, Any], ctx: ToolContext) -> Any:
    _task(a["task_id"])
    tasks.add_event(a["task_id"], a["kind"], a["note"])
    ctx.touched(a["task_id"])
    return {"ok": True}


def _mark_blocked(a: dict[str, Any], ctx: ToolContext) -> Any:
    _task(a["task_id"])
    until, _ = resolve_followup(ctx.now, a["followup_minutes"], None, _guardrails())
    t = tasks.block(a["task_id"], a["reason"], until)
    ctx.touched(a["task_id"])
    ctx.changed = True
    return brief(t)


_HANDLERS: dict[str, Callable[[dict[str, Any], ToolContext], Any]] = {
    "add_task": _add_task,
    "list_tasks": _list_tasks,
    "update_task": _update_task,
    "complete_task": _complete_task,
    "cancel_task": _cancel_task,
    "schedule_followup": _schedule_followup,
    "add_subtask": _add_subtask,
    "log_interaction": _log_interaction,
    "mark_blocked": _mark_blocked,
    "set_focus": _set_focus,
}
