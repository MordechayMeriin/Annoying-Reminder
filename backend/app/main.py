"""
Annoying Reminder API.

Public:   GET  /health, GET /config/push
Cron:     POST /tick                (X-Cron-Secret header)
Buttons:  POST /actions             (signed token from the notification)
Owner:    everything else           (Supabase login + OWNER_USER_ID check)
"""

import logging
import time
from collections import deque

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException
from fastapi.concurrency import run_in_threadpool
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from . import agent, db, notify, scheduler, tasks
from .auth import require_cron, require_owner
from .config import get_settings
from .llm import LLMLimitReached
from .tokens import verify_action_token

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

settings = get_settings()  # fail fast at startup if env vars are missing
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
    sent = await run_in_threadpool(scheduler.process_due_reminders)
    jobs = await run_in_threadpool(scheduler.due_daily_jobs)
    for job in jobs:
        # LLM jobs run after the response, so cron-job.org doesn't time out.
        background.add_task(scheduler.run_daily_job, job)
    return {"reminders_sent": sent, "jobs": jobs}


# ---------------------------------------------------------------- notification buttons

class ActionIn(BaseModel):
    token: str
    action: str = Field(pattern="^(done|snooze)$")
    minutes: int = Field(default=60, ge=5, le=24 * 60)


@app.post("/actions")
def action(body: ActionIn):
    task_id = verify_action_token(body.token, settings.action_secret)
    if task_id is None:
        raise HTTPException(status_code=403, detail="Invalid or expired link")
    task = tasks.complete(task_id) if body.action == "done" else tasks.snooze(task_id, body.minutes)
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
async def chat(body: ChatIn):
    _rate_limit()
    try:
        reply, changed = await agent.handle_user_message(body.message.strip())
    except LLMLimitReached:
        raise HTTPException(status_code=429, detail="Daily LLM limit reached")
    return {"reply": reply, "tasksChanged": changed}


@app.get("/messages", dependencies=[Depends(require_owner)])
def messages(limit: int = 50):
    rows = db.fetch_all(
        "select id, role, content, kind, created_at from messages order by created_at desc limit %s",
        (min(limit, 200),),
    )
    tz = settings.tz
    for r in rows:
        r["created_at"] = r["created_at"].astimezone(tz).isoformat(timespec="minutes")
    return rows[::-1]


@app.get("/tasks", dependencies=[Depends(require_owner)])
def list_tasks(scope: str = "open", days: int = 7):
    return [tasks.to_public(t) for t in tasks.list_tasks(scope, days)]


class TaskAction(BaseModel):
    action: str = Field(pattern="^(done|snooze|cancel)$")
    minutes: int = Field(default=60, ge=5, le=7 * 24 * 60)


@app.post("/tasks/{task_id}/action", dependencies=[Depends(require_owner)])
def task_action(task_id: int, body: TaskAction):
    if body.action == "done":
        task = tasks.complete(task_id)
    elif body.action == "snooze":
        task = tasks.snooze(task_id, body.minutes)
    else:
        task = tasks.cancel(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return tasks.to_public(task)


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
