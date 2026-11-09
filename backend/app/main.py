"""
Annoying Reminder API.

Public:   GET  /health, GET /config/push
Cron:     POST /tick                (X-Cron-Secret header)
Buttons:  POST /actions             (signed token from the notification)
Owner:    everything else           (Supabase login + OWNER_USER_ID check), incl. GET/PATCH /settings

Writing in the chat or acting on a task stops any repeating Pushover alarm (you've seen it).
"""

import logging
import time
from collections import deque

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from . import actions, agent, db, notify, prompting, scheduler, tasks, thread, user_settings
from .auth import require_cron, require_owner
from .config import SPECS, get_settings
from .llm import LLMLimitReached, LLMUnavailable
from .logic import ALARM, NORMAL, URGENT
from .tokens import verify_action_token

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

settings = get_settings()  # fail fast at startup if env vars are missing (env-only values below)
prompting.check_all()      # ... or a prompt file is missing
user_settings.load()       # behaviour settings changed in the app (logs and keeps defaults on error)
app = FastAPI(title="Annoying Reminder", docs_url=None, redoc_url=None, openapi_url=None)
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
    return {"vapidPublicKey": settings.vapid_public_key}


# ---------------------------------------------------------------- cron

@app.post("/tick", dependencies=[Depends(require_cron)])
async def tick(background: BackgroundTasks):
    # Deciding WHEN is fast and synchronous; writing the message (LLM) runs after the response,
    # so cron-job.org never times out.
    await run_in_threadpool(user_settings.load)
    task_ids = await run_in_threadpool(scheduler.plan_tick)
    if task_ids:
        background.add_task(scheduler.deliver, task_ids)
    jobs = await run_in_threadpool(scheduler.due_daily_jobs)
    for job in jobs:
        background.add_task(scheduler.run_daily_job, job)
    return {"contacting": task_ids, "jobs": jobs}


# ---------------------------------------------------------------- notification buttons

class ActionIn(BaseModel):
    token: str
    action: str = Field(pattern="^(done|snooze)$")
    minutes: int = Field(default=60, ge=5, le=24 * 60)


@app.post("/actions")
def action(body: ActionIn, background: BackgroundTasks):
    task_id = verify_action_token(body.token, settings.action_secret)
    if task_id is None:
        raise HTTPException(status_code=403, detail="Invalid or expired link")
    background.add_task(notify.cancel_alarms)
    task = actions.apply(task_id, body.action, body.minutes)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return {"ok": True, "task": tasks.to_public(task)}


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


@app.post("/chat", dependencies=[Depends(require_owner)])
async def chat(body: ChatIn, background: BackgroundTasks):
    _rate_limit()
    background.add_task(notify.cancel_alarms)
    try:
        r = await agent.handle_user_message(body.message.strip())
    except LLMLimitReached:
        raise HTTPException(status_code=429, detail="Daily LLM limit reached. Reminders still work.")
    except LLMUnavailable:
        raise HTTPException(status_code=503, detail="The assistant is unavailable right now. Try again in a minute.")
    return {"reply": r.reply, "tasksChanged": r.changed, "userMessageId": r.user_message_id,
            "replyMessageId": r.reply_message_id, "taskIds": r.task_ids}


@app.get("/messages", dependencies=[Depends(require_owner)])
def messages(limit: int = 50, before: int | None = None):
    rows = thread.page(before, min(max(limit, 1), 200))
    tz = get_settings().tz
    for r in rows:
        r["created_at"] = r["created_at"].astimezone(tz).isoformat(timespec="minutes")
    return rows


@app.get("/tasks", dependencies=[Depends(require_owner)])
def list_tasks(scope: str = "open", days: int = 7):
    return [tasks.to_public(t) for t in tasks.list_tasks(scope, days)]


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
    action: str = Field(pattern="^(done|snooze|cancel)$")
    minutes: int = Field(default=60, ge=5, le=7 * 24 * 60)


@app.post("/tasks/{task_id}/action", dependencies=[Depends(require_owner)])
def task_action(task_id: int, body: TaskAction, background: BackgroundTasks):
    background.add_task(notify.cancel_alarms)
    task = actions.apply(task_id, body.action, body.minutes)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return tasks.to_public(task)


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
    sent = notify.send_push("🔔 Test", "Notifications work.", url="/")
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
