import pytest

from app.config import _reasoning_effort


def test_reasoning_effort_values():
    assert _reasoning_effort("") == ""
    assert _reasoning_effort("LOW") == "low"
    for v in ("minimal", "low", "medium", "high"):
        assert _reasoning_effort(v) == v


def test_reasoning_effort_rejects_typos():
    with pytest.raises(RuntimeError):
        _reasoning_effort("lwo")
