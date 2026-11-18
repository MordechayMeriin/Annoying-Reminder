"""
The backend on AWS Lambda (Android Phase C): everything the phone still needs from a server, moved off Render.

    GET  /health
    POST /auth/google                                       Google sign-in → the app's own login token (auth.py)
    GET  /sync/status, POST /sync/restore, POST /sync/upload  the phone's backup (sync.py)
    POST /llm, GET /prompts                                 the assistant: one model call, the prompt files (llm_proxy.py)
    (EventBridge, once a day)                               "is the phone still alive?" (alive.py)

It's the same code as on Render (auth.py, sync.py and the DynamoDB layer under them), in a small FastAPI app
behind a Lambda function URL (Mangum turns the URL's events into ASGI requests). The web app's endpoints and /tick
aren't here: after the handover the web app is switched off and the phone reminds you itself. Since Phase D the
phone runs the assistant too, and comes here only for the model call (the key stays on the server).

AWS credentials come from the function's role, so there are no keys to keep. The settings it needs, as the
function's environment variables: DYNAMODB_TABLE, GOOGLE_CLIENT_ID, OWNER_GOOGLE_EMAIL, ACTION_SECRET (the same
value as on Render, so the phone stays logged in), ALERT_EMAIL (optional), OPENROUTER_API_KEY and OPENROUTER_MODELS
(Phase D: the assistant; optional LLM_CALLS_PER_DAY, LLM_PER_MINUTE, LLM_TIMEOUT_S, LLM_REASONING_EFFORT). scripts/aws_lambda_setup.py sets up
the function, its role, the URL and the daily schedule; .github/workflows/backend.yml deploys new code.
"""

import json
import logging
import os
import time
from collections import deque
from typing import Any

# config.py asks for the web app's values at startup, which this function doesn't use. The LLM's two may be missing
# until the setup script has run again for Phase D: then /llm says so (llm_proxy.configured).
for _name in ("CRON_SECRET", "OPENROUTER_API_KEY", "OPENROUTER_MODELS", "WEB_ORIGIN", "API_URL"):
    os.environ.setdefault(_name, "unused-on-lambda")

from fastapi import Depends, FastAPI, HTTPException  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from mangum import Mangum  # noqa: E402
from pydantic import BaseModel, Field  # noqa: E402

from . import alive, auth, llm_proxy, sync  # noqa: E402
from .auth import require_owner  # noqa: E402

logging.getLogger().setLevel(logging.INFO)
log = logging.getLogger("lambda")

app = FastAPI(title="ProMinder", docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health():
    return {"ok": True}


class GoogleSignInIn(BaseModel):
    idToken: str = Field(min_length=1, max_length=8192)


_login_times: deque[float] = deque()
_LOGINS_PER_MINUTE = 10


@app.post("/auth/google")
async def auth_google(body: GoogleSignInIn):
    """Trades the owner's Google ID token for the app's own login token (see auth.py)."""
    now = time.time()
    while _login_times and now - _login_times[0] > 60:
        _login_times.popleft()
    if len(_login_times) >= _LOGINS_PER_MINUTE:
        raise HTTPException(status_code=429, detail="Too many attempts. Wait a minute.")
    _login_times.append(now)
    email, google_id = await auth.verify_google(body.idToken)
    token, expires = auth.make_session_token(email, google_id)
    log.info("Logged in with Google")
    return {"token": token, "expiresAt": expires, "email": email}


class RestoreIn(BaseModel):
    handover: bool = False
    device: str = Field(default="", max_length=200)


class UploadIn(BaseModel):
    device: str = Field(default="", max_length=200)
    tables: dict[str, list[dict]] = Field(default_factory=dict)
    deletes: dict[str, list] = Field(default_factory=dict)


@app.get("/sync/status", dependencies=[Depends(require_owner)])
def sync_status():
    return sync.status()


@app.post("/sync/restore", dependencies=[Depends(require_owner)])
def sync_restore(body: RestoreIn):
    """Everything the phone keeps, for an empty phone. handover=true: from now on the phone owns the data."""
    return sync.restore(handover=body.handover, device=body.device)


@app.post("/sync/upload", dependencies=[Depends(require_owner)])
def sync_upload(body: UploadIn):
    """What the phone changed since its last upload. The phone's copy wins."""
    try:
        return sync.upload(body.tables, body.deletes, device=body.device)
    except sync.SyncError as e:
        raise HTTPException(status_code=422, detail=str(e)) from None


class LlmIn(BaseModel):
    messages: list[dict[str, Any]] = Field(min_length=1, max_length=120)
    tools: list[dict[str, Any]] = Field(default_factory=list, max_length=30)
    temperature: float = Field(default=0.3, ge=0, le=2)
    max_tokens: int | None = Field(default=None, ge=1, le=4000)
    timeout_s: float | None = Field(default=None, gt=0, le=120)


@app.post("/llm", dependencies=[Depends(require_owner)])
async def llm(body: LlmIn):
    """One model call for the phone's assistant (llm_proxy.py): the key, the fallback models, the cap and the limits."""
    try:
        return await llm_proxy.complete(body.messages, body.tools, body.temperature, body.max_tokens, body.timeout_s)
    except llm_proxy.ProxyError as e:
        return JSONResponse(status_code=e.status, content={"detail": e.detail, "code": e.code})


@app.get("/prompts", dependencies=[Depends(require_owner)])
def prompts():
    """The prompt files of this deployment: the phone prefers them to the ones it shipped with."""
    return llm_proxy.prompt_files()


_http = Mangum(app, lifespan="off")


def is_schedule(event: dict) -> bool:
    """The daily EventBridge event (not a request through the function URL)."""
    return event.get("source") == "aws.events" or event.get("detail-type") == "Scheduled Event"


def handler(event: dict, context=None):
    """The Lambda entry point: the daily check, or an HTTP request."""
    if is_schedule(event):
        result = alive.check()
        log.info("Alive check: %s", json.dumps(result))
        return result
    return _http(event, context)
