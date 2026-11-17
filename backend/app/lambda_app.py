"""
The backend on AWS Lambda (Android Phase C): everything the phone still needs from a server, moved off Render.

    GET  /health
    POST /auth/google                                       Google sign-in → the app's own login token (auth.py)
    GET  /sync/status, POST /sync/restore, POST /sync/upload  the phone's backup (sync.py)
    (EventBridge, once a day)                               "is the phone still alive?" (alive.py)

It's the same code as on Render (auth.py, sync.py and the DynamoDB layer under them), in a small FastAPI app
behind a Lambda function URL (Mangum turns the URL's events into ASGI requests). The web app's endpoints, /tick
and the LLM aren't here: after the handover the web app is switched off and the phone reminds you itself; the
LLM comes back in Phase D.

AWS credentials come from the function's role, so there are no keys to keep. The settings it needs, as the
function's environment variables: DYNAMODB_TABLE, GOOGLE_CLIENT_ID, OWNER_GOOGLE_EMAIL, ACTION_SECRET (the same
value as on Render, so the phone stays logged in), ALERT_EMAIL (optional). scripts/aws_lambda_setup.py sets up
the function, its role, the URL and the daily schedule; .github/workflows/backend.yml deploys new code.
"""

import json
import logging
import os
import time
from collections import deque

# config.py asks for the web app's and the LLM's values at startup; this function uses neither.
for _name in ("CRON_SECRET", "OPENROUTER_API_KEY", "OPENROUTER_MODELS", "WEB_ORIGIN", "API_URL"):
    os.environ.setdefault(_name, "unused-on-lambda")

from fastapi import Depends, FastAPI, HTTPException  # noqa: E402
from mangum import Mangum  # noqa: E402
from pydantic import BaseModel, Field  # noqa: E402

from . import alive, auth, sync  # noqa: E402
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
