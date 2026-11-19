"""
The phone's way to the LLM (Android Phase D): POST /llm on the Lambda.

The phone writes the prompts and runs the agent itself; this only holds what must not be on the phone and the
limits that protect the OpenRouter account:

- the OpenRouter key and the model fallback list (OPENROUTER_API_KEY, OPENROUTER_MODELS), via llm.chat_completion;
- a daily cap (LLM_CALLS_PER_DAY, default 300), counted in DynamoDB so every Lambda instance sees the same count;
- a rate limit (LLM_PER_MINUTE, default 30), per instance, like the login's;
- a timeout per call (LLM_TIMEOUT_S, default 25; the phone may ask for less, never more).

One request is one model call: the phone's tool loop (llm_loop.py, ported) makes several. Anything that means
"no usable answer right now" is a 503 the phone answers with its templates, the cap is a 429 with code "limit".

GET /prompts hands out the prompt files of this deployment, so a prompt can change without a new app release: the
phone downloads them at its daily backup and prefers them to the copies it shipped with.
"""

import hashlib
import logging
import os
import time
from collections import deque
from datetime import datetime
from typing import Any

from botocore.exceptions import ClientError

from . import db
from .config import get_settings
from .llm import LLMLimitReached, LLMUnavailable, chat_completion, last_model, track_models
from .prompting import PROMPT_DIR

log = logging.getLogger("llm_proxy")

NOT_SET = "unused-on-lambda"   # lambda_app.py's stand-in for a variable the function doesn't have yet


class ProxyError(Exception):
    """An answer for the phone: HTTP status, a sentence, and a short code it can act on."""

    def __init__(self, status: int, detail: str, code: str):
        super().__init__(detail)
        self.status, self.detail, self.code = status, detail, code


def _int_env(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "") or default)
    except ValueError:
        return default


def calls_per_day() -> int:
    return _int_env("LLM_CALLS_PER_DAY", 300)


def calls_per_minute() -> int:
    return _int_env("LLM_PER_MINUTE", 30)


def configured() -> bool:
    key = os.environ.get("OPENROUTER_API_KEY", "")
    models = os.environ.get("OPENROUTER_MODELS", "")
    return bool(key) and key != NOT_SET and bool(models) and models != NOT_SET


_times: deque[float] = deque()


def _rate_limit() -> None:
    now = time.time()
    while _times and now - _times[0] > 60:
        _times.popleft()
    if len(_times) >= calls_per_minute():
        raise ProxyError(429, "Too many requests to the assistant. Wait a minute.", "rate")
    _times.append(now)


def _count_today() -> int:
    """One more call today, in DynamoDB (atomic, shared by every instance). ProxyError when the cap is used up."""
    s = get_settings()
    day = datetime.now(s.tz).date().isoformat()
    cap = calls_per_day()
    try:
        r = db.client().update_item(
            TableName=db._table(), Key=db.to_attr({"pk": "llm", "sk": f"day#{day}"}),
            UpdateExpression="ADD n :one",
            ConditionExpression="attribute_not_exists(n) OR n < :cap",
            ExpressionAttributeValues={":one": {"N": "1"}, ":cap": {"N": str(cap)}},
            ReturnValues="UPDATED_NEW",
        )
    except ClientError as e:
        if e.response["Error"]["Code"] == "ConditionalCheckFailedException":
            raise ProxyError(429, "Daily LLM limit reached. Reminders still work.", "limit") from None
        raise
    return int(r["Attributes"]["n"]["N"])


def used_today() -> int:
    s = get_settings()
    raw = db.get_raw("llm", f"day#{datetime.now(s.tz).date().isoformat()}")
    return int(raw.get("n", 0)) if raw else 0


async def complete(messages: list[dict[str, Any]], tools: list[dict[str, Any]] | None, temperature: float,
                   max_tokens: int | None, timeout_s: float | None) -> dict[str, Any]:
    """One model call for the phone: {"message": {...}, "model": "..."}. Raises ProxyError."""
    if not configured():
        raise ProxyError(503, "The assistant isn't set up on the server yet (OPENROUTER_API_KEY). "
                              "Run scripts/aws_lambda_setup.py again.", "not_configured")
    _rate_limit()
    _count_today()
    s = get_settings()
    timeout = min(timeout_s or s.llm_timeout_s, s.llm_timeout_s)
    try:
        with track_models() as models:
            message = await chat_completion(messages, tools=tools or None, temperature=temperature,
                                            max_tokens=max_tokens, timeout=timeout)
    except LLMLimitReached:
        raise ProxyError(429, "Daily LLM limit reached. Reminders still work.", "limit") from None
    except LLMUnavailable as e:
        log.warning("LLM unavailable: %s", e)
        raise ProxyError(503, "The assistant is unavailable right now. Try again in a minute.", "unavailable") from None
    out = {"role": "assistant", "content": message.get("content") or ""}
    if message.get("tool_calls"):
        out["tool_calls"] = message["tool_calls"]
    return {"message": out, "model": last_model(models)}


def prompt_files() -> dict[str, Any]:
    """Every prompt file of this deployment, by name ("system", "playbooks/stuck"), and a version for the set."""
    files = {}
    for path in sorted(PROMPT_DIR.rglob("*.md")):
        name = path.relative_to(PROMPT_DIR).with_suffix("").as_posix()
        files[name] = path.read_text(encoding="utf-8").strip()
    digest = hashlib.sha256("\n\0".join(f"{k}\n{v}" for k, v in files.items()).encode()).hexdigest()[:16]
    return {"version": digest, "prompts": files}
