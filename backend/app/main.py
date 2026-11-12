"""
Annoying Reminder API.

Public:   GET  /health, GET /config/push
Cron:     POST /tick                (X-Cron-Secret header)
Buttons:  POST /actions             (signed token from the notification: ▶ Start / ✓ Done / ▶ Another round)
Owner:    everything else           (Supabase login + OWNER_USER_ID check), incl. GET/PATCH /settings,
          POST /what-now, POST /tasks/{id}/start, POST /sessions/{id}/end, PATCH /sessions/{id},
          POST /sessions/{id}/note, POST /tasks/bulk-action (Move overdue to next week · Drop them · Keep),
          GET /preferences, DELETE /preferences/{id}

Writing in the chat or acting on a task stops any repeating Pushover alarm (you've seen it).

POST /chat answers with a short Server-Sent Events stream of progress, so the app can show what's
happening: received → thinking → tool … → thinking → done (or error). The reply itself arrives whole
in `done`, with both saved messages (`messages`) so the app can show them without another request. The agent runs as its own task, so a dropped connection (screen locked, app closed) never
cuts a turn short: the reply is still saved and shows up on the next refresh.
"""

import asyncio
import json
import logging
import time
from collections import deque
from contextlib import asynccontextmanager

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from . import (actions, agent, db, httpclient, notify, preferences, prompting, scheduler, sessions, tasks, thread,
               user_settings)
from .auth import require_cron, require_owner
from .config import SPECS, get_settings
from .llm import LLMLimitReached, LLMUnavailable
from .logic import (ALARM, NORMAL, SESSION_CHOICES, SESSION_MAX_MIN, URGENT, WHAT_NOW_CHIPS, day_start, focus_day,
                    wants_breadcrumb)
from .tokens import verify_action_token

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("main")

settings = get_settings()  # fail fast at startup if env vars are missing (env-only values below)
prompting.check_all()      # ... or a prompt file is missing
user_settings.load()       # behaviour settings changed in the app (logs and keeps defaults on error)
preferences.load()         # what the owner asked of the assistant in chat (same)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    yield
    await httpclient.aclose()
    await run_in_threadpool(db.close_pool)


app = FastAPI(title="Annoying Reminder", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.web_origin],
    allow_methods=["GET", "POST", "PATCH", "DELETE"],
    allow_headers=["Authorization", "Content-Type"],
)


# ---------------------------------------------------------------- public

@app.get("/health")
def health():
    return {"ok": True}


@app.get("/config/push")
def push_config():
    """Whether the app should offer browser notifications (and the key to subscribe with)."""
    s = get_settings()
    enabled = notify.browser_push_on() or (s.browser_push_available and not s.pushover_enabled)
    return {"enabled": enabled, "vapidPublicKey": s.vapid_public_key if enabled else None}


# ---------------------------------------------------------------- cron

@app.post("/tick", dependencies=[Depends(require_cron)])
async def tick(background: BackgroundTasks):
    # Deciding WHEN is fast and synchronous; writing the message (LLM) runs after the response,
    # so cron-job.org never times out.
    await run_in_threadpool(user_settings.load)
    await run_in_threadpool(preferences.load)
    task_ids = await run_in_threadpool(scheduler.plan_tick)
    if task_ids:
        background.add_task(scheduler.deliver, task_ids)
    session_ids = await run_in_threadpool(scheduler.plan_sessions)
    if session_ids:
        background.add_task(scheduler.deliver_session_ends, session_ids)
    jobs = await run_in_threadpool(scheduler.due_daily_jobs)
    for job in jobs:
        background.add_task(scheduler.run_daily_job, job)
    return {"contacting": task_ids, "sessionEnds": session_ids, "jobs": jobs}


# ---------------------------------------------------------------- notification buttons

class ActionIn(BaseModel):
    token: str
    # snooze: notifications sent before ▶ Start existed; another: the session end check-in
    action: str = Field(pattern="^(done|start|snooze|another)$")
    minutes: int | None = Field(default=None, ge=5, le=24 * 60)


def _session_now() -> dict | None:
    """The open session for the app's session screen (with its task), or None."""
    session, task = actions.current_session()
    return sessions.to_public(session, task)


@app.post("/actions")
def action(body: ActionIn, background: BackgroundTasks):
    task_id = verify_action_token(body.token, settings.action_secret)
    if task_id is None:
        raise HTTPException(status_code=403, detail="Invalid or expired link")
    background.add_task(notify.cancel_alarms)
    task = actions.apply(task_id, body.action, body.minutes)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    out = {"ok": True, "task": tasks.to_public(task)}
    if body.action in ("start", "another"):
        out["session"] = _session_now()   # the service worker opens the session screen
    return out


# ---------------------------------------------------------------- owner only

_chat_times: deque[float] = deque()


def _rate_limit() -> None:
    now = time.time()
    while _chat_times and now - _chat_times[0] > 60:
        _chat_times.popleft()
    if len(_chat_times) >= settings.chat_per_minute:
        raise HTTPException(status_code=429, detail="Slow down")
    _chat_times.append(now)


class ChatIn(BaseModel):
    message: str = Field(min_length=1, max_length=2000)


_running: set[asyncio.Task] = set()  # chat turns in progress (keeps a reference until they finish)
_KEEPALIVE_S = 15


def _sse(event: dict) -> str:
    return f"data: {json.dumps(event, ensure_ascii=False)}\n\n"


def _public_messages(rows: list[dict]) -> list[dict]:
    s = get_settings()
    out = []
    for r in rows:
        r = dict(r)
        r["created_at"] = r["created_at"].astimezone(s.tz).isoformat(timespec="minutes")
        if not s.show_model:
            r["model"] = None  # ⚙ "Show which AI model replied" is off: the app shows nothing
        out.append(r)
    return out


async def _chat_turn(text: str, emit) -> None:
    try:
        r = await agent.handle_user_message(text, emit)
        try:
            saved = await run_in_threadpool(thread.by_ids, [r.user_message_id, r.reply_message_id])
        except Exception:  # the app falls back to reloading the chat
            log.exception("Loading the saved messages failed")
            saved = []
        emit({"type": "done", "reply": r.reply, "tasksChanged": r.changed, "userMessageId": r.user_message_id,
              "replyMessageId": r.reply_message_id, "taskIds": r.task_ids, "messages": _public_messages(saved)})
    except LLMLimitReached:
        emit({"type": "error", "status": 429, "detail": "Daily LLM limit reached. Reminders still work."})
    except LLMUnavailable:
        emit({"type": "error", "status": 503,
              "detail": "The assistant is unavailable right now. Try again in a minute."})
    except Exception:
        log.exception("Chat turn failed")
        emit({"type": "error", "status": 500, "detail": "Something went wrong. Try again."})
    finally:
        try:
            await run_in_threadpool(notify.cancel_alarms)
        except Exception:
            log.exception("Cancelling alarms failed")


@app.post("/chat", dependencies=[Depends(require_owner)])
async def chat(body: ChatIn):
    _rate_limit()
    queue: asyncio.Queue[dict] = asyncio.Queue()
    loop = asyncio.get_running_loop()

    def emit(event: dict) -> None:
        # Progress comes from worker threads too (database work, tools); asyncio.Queue isn't
        # thread-safe, so every event is handed to the event loop.
        loop.call_soon_threadsafe(queue.put_nowait, event)

    turn = asyncio.create_task(_chat_turn(body.message.strip(), emit))
    _running.add(turn)
    turn.add_done_callback(_running.discard)

    async def events():
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), _KEEPALIVE_S)
            except asyncio.TimeoutError:
                yield ": keepalive\n\n"
                continue
            yield _sse(event)
            if event["type"] in ("done", "error"):
                return

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.get("/messages", dependencies=[Depends(require_owner)])
def messages(limit: int = 50, before: int | None = None, after: int | None = None):
    """The newest `limit` messages, or those older than `before` / newer than `after`."""
    return _public_messages(thread.page(before, min(max(limit, 1), 200), after))


@app.get("/tasks", dependencies=[Depends(require_owner)])
def list_tasks(scope: str = "open", days: int = 7):
    return [tasks.to_public(t) for t in tasks.list_tasks(scope, days)]


@app.get("/tasks/overview", dependencies=[Depends(require_owner)])
def tasks_overview(days: int = 7):
    """Everything the tasks drawer shows, in one request."""
    return {
        "today": [tasks.to_public(t) for t in tasks.list_tasks("today")],
        "upcoming": [tasks.to_public(t) for t in tasks.list_tasks("upcoming", days)],
        "open": [tasks.to_public(t) for t in tasks.list_tasks("open")],
    }


@app.get("/focus", dependencies=[Depends(require_owner)])
def focus():
    """
    Everything the top of the chat shows, display only: Today's 3 (each with its next action, done
    ones ticked), the wins of the day, the running session (the timer) and, after a stopped or
    unanswered session, the optional "Where did you stop?" field.
    """
    s = get_settings()
    now = tasks.now_utc()
    now_local = now.astimezone(s.tz)
    day = focus_day(now_local, s.day_reset_time)
    rows, done = tasks.focus_today(day)
    since = day_start(now_local, s.day_reset_time)
    finished = tasks.done_since(since)
    n_sessions, minutes = sessions.stats_since(since, now)
    # At most one session is open, and it's the latest one (starting a session closes the others).
    latest = sessions.latest()
    session = latest if latest and latest["ended_at"] is None else None
    offer = session is None and wants_breadcrumb(latest, now)
    task = tasks.get(latest["task_id"]) if session or offer else None
    resume = None
    if offer and task and task["status"] == "open":
        resume = {"session_id": latest["id"], "task_id": task["id"], "title": task["title"]}
    return {"date": day.isoformat(),
            "tasks": [{**tasks.to_public(t), "done": t["id"] in done} for t in rows],
            "doneToday": len(finished),
            "wins": {"done": len(finished), "sessions": n_sessions, "minutes": minutes},
            "session": sessions.to_public(session, task) if session else None,
            "resume": resume,
            "now": now_local.isoformat(timespec="seconds")}


class WhatNowIn(BaseModel):
    low_energy: bool = False
    exclude: list[int] = Field(default_factory=list, max_length=50)   # already offered (Something else)
    chip: str = Field(default="what_now", pattern="^(" + "|".join(WHAT_NOW_CHIPS) + ")$")


@app.post("/what-now", dependencies=[Depends(require_owner)])
def what_now(body: WhatNowIn):
    """One thing to do now, decided by code (no LLM): the tap's event and the answer, as chat messages."""
    rows = actions.what_now(body.low_energy, body.exclude, body.chip)
    return {"messages": _public_messages(thread.by_ids([m["id"] for m in rows]))}


class StartIn(BaseModel):
    minutes: int | None = Field(default=None, ge=5, le=SESSION_MAX_MIN)


@app.post("/tasks/{task_id}/start", dependencies=[Depends(require_owner)])
def start_task(task_id: int, body: StartIn, background: BackgroundTasks):
    """▶ Start: a focus session (25 minutes from the app unless given) on the task's next action."""
    background.add_task(notify.cancel_alarms)
    session = actions.start_session(task_id, body.minutes or SESSION_CHOICES[2])
    if session is None:
        raise HTTPException(status_code=404, detail="No open task with that id")
    return {"session": sessions.to_public(session, tasks.get(task_id))}


class SessionEnd(BaseModel):
    outcome: str = Field(pattern="^(done|another|stopped)$")


@app.post("/sessions/{session_id}/end", dependencies=[Depends(require_owner)])
def end_session(session_id: int, body: SessionEnd, background: BackgroundTasks):
    background.add_task(notify.cancel_alarms)
    result = actions.end_session(session_id, body.outcome)
    if result is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return {"session": _session_now()}


class SessionPatch(BaseModel):
    planned_min: int = Field(ge=5, le=SESSION_MAX_MIN, multiple_of=5)


@app.patch("/sessions/{session_id}", dependencies=[Depends(require_owner)])
def patch_session(session_id: int, body: SessionPatch):
    """A new length for the running session (5 / 15 / 25 / 45, or +5)."""
    if actions.set_session_length(session_id, body.planned_min) is None:
        raise HTTPException(status_code=409, detail="That session has already ended")
    return {"session": _session_now()}


class NoteIn(BaseModel):
    text: str | None = Field(default=None, max_length=tasks.RESUME_NOTE_MAX)   # null = dismiss


@app.post("/sessions/{session_id}/note", dependencies=[Depends(require_owner)])
def session_note(session_id: int, body: NoteIn):
    """ "Where did you stop?" (optional): saved as the task's resume note; null just closes the field."""
    task = actions.save_breadcrumb(session_id, body.text)
    if task is None:
        raise HTTPException(status_code=404, detail="Session not found")
    return tasks.to_public(task)


@app.get("/tasks/{task_id}", dependencies=[Depends(require_owner)])
def get_task(task_id: int):
    task = tasks.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    events = tasks.recent_events(task_id, 20)
    for e in events:
        e["created_at"] = e["created_at"].astimezone(get_settings().tz).isoformat(timespec="minutes")
    return {**tasks.to_public(task),
            "subtasks": [tasks.to_public(s) for s in tasks.subtasks(task_id)],
            "events": events}


class TaskPatch(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    description: str | None = Field(default=None, max_length=tasks.DESCRIPTION_MAX)


@app.patch("/tasks/{task_id}", dependencies=[Depends(require_owner)])
def patch_task(task_id: int, body: TaskPatch):
    fields = body.model_dump(exclude_none=True)
    task = tasks.update(task_id, **fields)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return tasks.to_public(task)


class TaskAction(BaseModel):
    action: str = Field(pattern="^(" + "|".join(actions.ACTIONS) + ")$")
    minutes: int | None = Field(default=None, ge=5, le=7 * 24 * 60)


@app.post("/tasks/{task_id}/action", dependencies=[Depends(require_owner)])
def task_action(task_id: int, body: TaskAction, background: BackgroundTasks):
    background.add_task(notify.cancel_alarms)
    task = actions.apply(task_id, body.action, body.minutes)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    out = tasks.to_public(task)
    if body.action in ("start", "another"):
        out["session"] = _session_now()
    return out


class BulkAction(BaseModel):
    action: str = Field(pattern="^(" + "|".join(actions.BULK_ACTIONS) + ")$")
    task_ids: list[int] = Field(default_factory=list, max_length=20)   # drop / keep: exactly the listed tasks


@app.post("/tasks/bulk-action", dependencies=[Depends(require_owner)])
def bulk_action(body: BulkAction, background: BackgroundTasks):
    """The chips that act on several tasks with one tap, no LLM: Move overdue to next week, Drop them, Keep."""
    background.add_task(notify.cancel_alarms)
    rows = actions.bulk(body.action, body.task_ids)
    return {"ok": True, "tasks": [tasks.to_public(t) for t in rows]}


@app.get("/preferences", dependencies=[Depends(require_owner)])
def list_preferences():
    """What the owner asked of the assistant in chat ("shorter messages"): read-only, each can be deleted."""
    preferences.load()
    return preferences.to_public()


@app.delete("/preferences/{pref_id}", dependencies=[Depends(require_owner)])
def delete_preference(pref_id: int):
    if not preferences.delete(pref_id):
        raise HTTPException(status_code=404, detail="Preference not found")
    return preferences.to_public()


@app.get("/settings", dependencies=[Depends(require_owner)])
def get_app_settings():
    return user_settings.describe(tasks.now_utc())


class SettingsPatch(BaseModel):
    # key → new value as text, or null to reset to the default
    values: dict[str, str | None] = Field(max_length=100)


@app.patch("/settings", dependencies=[Depends(require_owner)])
def patch_app_settings(body: SettingsPatch):
    errors = user_settings.save({k: (v[:200] if v is not None else None) for k, v in body.values.items()})
    if errors:
        message = "; ".join(f"{SPECS[k].label if k in SPECS else k}: {msg}" for k, msg in errors.items())
        raise HTTPException(status_code=422, detail={"message": message, "errors": errors})
    return user_settings.describe(tasks.now_utc())


class SubscriptionKeys(BaseModel):
    p256dh: str
    auth: str


class SubscriptionIn(BaseModel):
    endpoint: str
    keys: SubscriptionKeys


@app.post("/push/subscribe", dependencies=[Depends(require_owner)])
def subscribe(sub: SubscriptionIn):
    db.execute(
        """insert into push_subscriptions (endpoint, p256dh, auth) values (%s, %s, %s)
           on conflict (endpoint) do update set p256dh = excluded.p256dh, auth = excluded.auth""",
        (sub.endpoint, sub.keys.p256dh, sub.keys.auth),
    )
    return {"ok": True}


@app.post("/push/test", dependencies=[Depends(require_owner)])
def push_test():
    s = get_settings()
    if not s.browser_push_available:
        raise HTTPException(status_code=400, detail="Browser notifications aren't set up (no VAPID keys).")
    sent = notify.send_push("🔔 Test", "Notifications work.", url="/", force=not s.pushover_enabled)
    return {"sent": sent}


class PushoverTest(BaseModel):
    level: str = Field(default="normal", pattern="^(normal|urgent|alarm)$")


_TESTS = {
    "normal": (NORMAL, "🔔 Test", "This is how a normal message sounds."),
    "urgent": (URGENT, "❗ Test: urgent", "This is how meetings, your own reminders and ignored check-ins sound."),
    "alarm": (ALARM, "🚨 Test: alarm",
              "This repeats until you acknowledge it (or for 5 minutes). Write in the chat to stop it too."),
}


@app.post("/pushover/test", dependencies=[Depends(require_owner)])
def pushover_test(body: PushoverTest):
    if not settings.pushover_enabled:
        raise HTTPException(status_code=400, detail="Pushover isn't set up: add PUSHOVER_TOKEN and PUSHOVER_USER on Render.")
    level, title, text = _TESTS[body.level]
    if not notify.send_pushover(title, text, level=level, url=settings.web_origin + "/", expire_s=300):
        raise HTTPException(status_code=502, detail="Pushover didn't accept the message. Check the token and user key.")
    return {"ok": True}
