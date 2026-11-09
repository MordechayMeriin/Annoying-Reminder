"""OpenRouter client with model fallback, a daily call cap and a strict timeout.

Anything that means "no usable answer right now" (cap reached, 429, timeout, network error,
server error, malformed response) raises LLMUnavailable, so callers can fall back to templates.
"""

import logging
from datetime import date, datetime
from typing import Any

import httpx

from .config import get_settings

log = logging.getLogger("llm")

_calls_today: dict[date, int] = {}


class LLMUnavailable(RuntimeError):
    """No answer from the LLM right now. Use a fallback; this is not a bug."""


class LLMLimitReached(LLMUnavailable):
    """Our own daily cap (LLM_CALLS_PER_DAY) is used up."""


def _count_call() -> None:
    s = get_settings()
    today = datetime.now(s.tz).date()
    for d in list(_calls_today):
        if d != today:
            del _calls_today[d]
    if _calls_today.get(today, 0) >= s.llm_calls_per_day:
        raise LLMLimitReached("Daily LLM call limit reached")
    _calls_today[today] = _calls_today.get(today, 0) + 1


def calls_today() -> int:
    return _calls_today.get(datetime.now(get_settings().tz).date(), 0)


async def chat_completion(messages: list[dict[str, Any]], tools: list[dict] | None = None,
                          temperature: float = 0.3, max_tokens: int | None = None,
                          timeout: float | None = None) -> dict[str, Any]:
    """Returns the assistant message dict ({role, content, tool_calls?})."""
    _count_call()
    s = get_settings()
    body: dict[str, Any] = {
        "model": s.openrouter_models[0],
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens or s.llm_max_tokens,
    }
    if len(s.openrouter_models) > 1:
        body["models"] = s.openrouter_models  # automatic fallback, in order
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
        body["parallel_tool_calls"] = False  # a hint; we still handle several calls if they come

    try:
        async with httpx.AsyncClient(timeout=timeout or s.llm_timeout_s) as client:
            r = await client.post(
                "https://openrouter.ai/api/v1/chat/completions",
                headers={"Authorization": f"Bearer {s.openrouter_api_key}", "X-Title": "Annoying Reminder"},
                json=body,
            )
    except httpx.HTTPError as e:  # timeouts and network errors
        raise LLMUnavailable(f"OpenRouter unreachable: {type(e).__name__}") from e

    if r.status_code == 429:
        raise LLMUnavailable("OpenRouter rate limit (429)")
    if r.status_code != 200:
        log.error("OpenRouter error %s: %s", r.status_code, r.text[:500])
        raise LLMUnavailable(f"OpenRouter HTTP {r.status_code}")
    try:
        data = r.json()
        if "error" in data:
            raise LLMUnavailable(f"OpenRouter error: {str(data['error'])[:300]}")
        return data["choices"][0]["message"]
    except (ValueError, KeyError, IndexError, TypeError) as e:
        raise LLMUnavailable(f"Malformed OpenRouter response: {e}") from e
