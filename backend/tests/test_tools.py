"""Tool argument validation, text tool-call parsing and follow-up resolution (pure)."""

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from app.logic import Guardrails
from app.tools import ToolArgError, parse_text_tool_call, resolve_followup, validate

TZ = ZoneInfo("Asia/Jerusalem")
NAMES = {"add_task", "schedule_followup", "log_interaction"}
G = Guardrails(quiet_start=time(23), quiet_end=time(7))


def test_validate_coerces_forgiving_inputs():
    args = validate("schedule_followup", {"task_id": "12", "minutes_from_now": 30.0, "reason": " later ",
                                          "requested_by": "user", "at_local_iso": "", "extra": 1})
    assert args == {"task_id": 12, "minutes_from_now": 30, "reason": "later", "requested_by": "user"}


def test_validate_booleans_and_enums():
    assert validate("update_task", {"id": 1, "unblock": "true"})["unblock"] is True
    with pytest.raises(ToolArgError, match="one of"):
        validate("log_interaction", {"task_id": 1, "kind": "angry", "note": "x"})
    with pytest.raises(ToolArgError, match="integer"):
        validate("complete_task", {"id": "abc"})
    with pytest.raises(ToolArgError, match="Missing"):
        validate("add_subtask", {"task_id": 3})
    with pytest.raises(ToolArgError, match="Unknown tool"):
        validate("delete_everything", {})


def test_parse_json_tool_call_variants():
    assert parse_text_tool_call('{"name": "add_task", "arguments": {"title": "x"}}', NAMES) == ("add_task", {"title": "x"})
    assert parse_text_tool_call('```json\n{"tool_call": {"name": "add_task", "parameters": {"title": "y"}}}\n```',
                                NAMES) == ("add_task", {"title": "y"})
    assert parse_text_tool_call('{"name": "add_task", "arguments": "{\\"title\\": \\"z\\"}"}', NAMES) == \
        ("add_task", {"title": "z"})


def test_parse_python_style_tool_call():
    text = 'Sure.\n```tool_code\nprint(default_api.schedule_followup(task_id=4, minutes_from_now=90, reason="after lunch"))\n```'
    assert parse_text_tool_call(text, NAMES) == \
        ("schedule_followup", {"task_id": 4, "minutes_from_now": 90, "reason": "after lunch"})


def test_parse_ignores_plain_text_and_unknown_tools():
    assert parse_text_tool_call("How is the report going? {just braces}", NAMES) is None
    assert parse_text_tool_call('{"name": "rm_rf", "arguments": {}}', NAMES) is None
    assert parse_text_tool_call("", NAMES) is None


def test_resolve_followup_clamps_and_requires_exactly_one():
    now = datetime(2026, 11, 9, 10, tzinfo=TZ)
    assert resolve_followup(now, 3, None, G) == (now + timedelta(minutes=10), True)
    assert resolve_followup(now, None, "2026-11-09T15:00", G) == (datetime(2026, 11, 9, 15, tzinfo=TZ), False)
    assert resolve_followup(now, 60 * 24 * 30, None, G)[0] == now + timedelta(days=7)
    with pytest.raises(ToolArgError):
        resolve_followup(now, 30, "2026-11-09T15:00", G)
    with pytest.raises(ToolArgError):
        resolve_followup(now, None, None, G)
    with pytest.raises(ToolArgError, match="ISO"):
        resolve_followup(now, None, "tomorrow-ish", G)
