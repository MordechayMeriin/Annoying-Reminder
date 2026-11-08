"""OpenRouter client with model fallback and a daily spend guard."""

import logging
from datetime import date
from typing import Any

import httpx

from .config import get_settings

log = logging.getLogger("llm")

_calls_today: dict[date, int] = {}


class LLMLimitReached(RuntimeError):
    pass


def _count_call() -> None:
    s = get_settings()
    today = date.today()
    for d in list(_calls_today):
        if d != today:
            del _calls_today[d]
    _calls_today[today] = _calls_today.get(today, 0) + 1
    if _calls_today[today] > s.llm_calls_per_day:
        raise LLMLimitReached("Daily LLM call limit reached")


async def chat_completion(messages: list[dict[str, Any]], tools: list[dict] | None = None,
                          temperature: float = 0.3) -> dict[str, Any]:
    """Returns the assistant message dict ({role, content, tool_calls?})."""
    _count_call()
    s = get_settings()
    body: dict[str, Any] = {
        "model": s.openrouter_models[0],
        "messages": messages,
        "temperature": temperature,
    }
    if len(s.openrouter_models) > 1:
        body["models"] = s.openrouter_models  # automatic fallback, in order
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"

    async with httpx.AsyncClient(timeout=90) as client:
        r = await client.post(
            "https://openrouter.ai/api/v1/chat/completions",
            headers={
                "Authorization": f"Bearer {s.openrouter_api_key}",
                "X-Title": "Annoying Reminder",
            },
            json=body,
        )
    if r.status_code != 200:
        log.error("OpenRouter error %s: %s", r.status_code, r.text[:500])
        r.raise_for_status()
    data = r.json()
    if "error" in data:
        raise RuntimeError(f"OpenRouter error: {data['error']}")
    return data["choices"][0]["message"]
