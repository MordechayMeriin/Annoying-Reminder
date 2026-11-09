"""
The tool-calling loop shared by the chat agent and the check-in coach.

Robust for a mid-size free model:
- tool calls are executed one after another (never relies on parallel calls)
- malformed arguments go back to the model as a clear error, with ONE retry per turn
- a tool call written as text instead of `tool_calls` is parsed once
- LLMUnavailable propagates so the caller can fall back
- optional progress callback (on_event) so the chat UI can show "typing…" / "writing a new task"
"""

import json
import logging
from typing import Any, Callable

from .llm import chat_completion
from .tools import ToolArgError, ToolContext, FENCE, execute, parse_text_tool_call

log = logging.getLogger("llm_loop")


async def run(messages: list[dict[str, Any]], tool_defs: list[dict[str, Any]], ctx: ToolContext, *,
              max_rounds: int = 5, temperature: float = 0.3, timeout: float | None = None,
              on_event: Callable[[dict[str, Any]], None] | None = None) -> str:
    """
    Returns the model's final text ('' if it produced none or gave up after errors).

    on_event, if given, gets {"type": "thinking"} before each LLM call and
    {"type": "tool", "name": ..., "args": {...}} before each tool runs. It must not raise.
    """
    emit = on_event or (lambda event: None)
    names = {t["function"]["name"] for t in tool_defs}
    errors = 0
    parsed_text_call = False
    leftover = ""  # text the model wrote next to a text-form tool call

    for round_no in range(max_rounds):
        emit({"type": "thinking"})
        msg = await chat_completion(messages, tools=tool_defs, temperature=temperature, timeout=timeout)
        content = (msg.get("content") or "").strip()
        calls = msg.get("tool_calls") or []

        if not calls:
            parsed = None if parsed_text_call else parse_text_tool_call(content, names)
            if not parsed:
                return content or leftover
            parsed_text_call = True
            leftover = FENCE.sub("", content).strip() if "```" in content else ""
            calls = [{"id": f"text_call_{round_no}", "type": "function",
                      "function": {"name": parsed[0], "arguments": json.dumps(parsed[1], ensure_ascii=False)}}]
            content = ""

        messages.append({"role": "assistant", "content": content, "tool_calls": calls})
        for call in calls:
            name = call.get("function", {}).get("name", "")
            try:
                raw = call["function"].get("arguments") or "{}"
                args = json.loads(raw) if isinstance(raw, str) else raw
                if isinstance(args, dict):
                    emit({"type": "tool", "name": name, "args": args})
                result = execute(name, args, ctx)
            except (ToolArgError, json.JSONDecodeError) as e:
                errors += 1
                log.info("Tool %s rejected: %s", name, e)
                result = {"error": f"{e} Fix the arguments and try again." if errors == 1 else str(e)}
            except Exception:  # a bug or DB problem: don't let the model loop on it
                errors += 1
                log.exception("Tool %s failed", name)
                result = {"error": "Internal error while running this tool."}
            messages.append({
                "role": "tool",
                "tool_call_id": call.get("id") or f"call_{round_no}",
                "content": json.dumps(result, ensure_ascii=False, default=str),
            })
        if errors > 1:
            log.warning("Giving up after %s tool errors", errors)
            return leftover
    return leftover
