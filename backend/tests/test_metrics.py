"""scripts/metrics.py: the success measures, computed from plain rows (no database)."""

import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import metrics  # noqa: E402

TZ = ZoneInfo("Asia/Jerusalem")


def at(d, h, mi=0):
    return datetime(2026, 11, d, h, mi, tzinfo=TZ)


def task(i, **kw):
    t = {"id": i, "kind": "task", "status": "open", "due_at": None, "created_at": at(1, 8), "completed_at": None}
    t.update(kw)
    return t


def test_measures_from_rows():
    messages = [
        {"id": 1, "role": "assistant", "kind": "nudge", "task_ids": [1], "created_at": at(8, 10), "content": "x"},
        {"id": 2, "role": "assistant", "kind": "nudge", "task_ids": [2], "created_at": at(8, 11), "content": "x"},
        {"id": 3, "role": "user", "kind": "chat", "task_ids": [], "created_at": at(8, 12), "content": "hi"},
        {"id": 4, "role": "user", "kind": "event", "task_ids": [1], "created_at": at(8, 12), "content": "[Marked done: A]"},
        {"id": 5, "role": "user", "kind": "event", "task_ids": [], "created_at": at(9, 9), "content": "[Rated this week: 4]"},
    ]
    events = [
        {"task_id": 1, "kind": "done", "note": None, "created_at": at(8, 10, 30)},
        {"task_id": 2, "kind": "ignored", "note": None, "created_at": at(8, 11, 30)},
        *[{"task_id": 3, "kind": "postponed", "note": None, "created_at": at(9, 9 + i)} for i in range(3)],
    ]
    tasks = [
        task(1, due_at=at(8, 9), status="done", completed_at=at(8, 10, 30)),
        task(2, due_at=at(8, 9)),
        task(3, due_at=at(2, 9)),                                  # overdue, still open
        task(4, kind="meeting", due_at=at(8, 9)),                  # meetings never count
    ]
    r = metrics.compute(messages=messages, events=events, tasks=tasks, start=date(2026, 11, 8),
                        end=date(2026, 11, 15), tz=TZ)
    assert (r.nudges, r.nudges_acted_on, r.action_minutes_median) == (2, 1, 30)
    assert r.focus_source == "due today"
    assert (date(2026, 11, 8), 1, 2) in r.focus_days
    assert r.ignored_per_week == [(date(2026, 11, 8), 1)]
    assert r.postponed_tasks_per_week == [(date(2026, 11, 8), 1)]
    assert r.overdue_per_week == [(date(2026, 11, 8), 2)]
    assert (r.taps, r.typed, r.ratings) == (2, 1, [4])
    assert any("Nudge → action time: median 30 min" in line for line in r.lines())


def test_focus_date_is_used_once_it_exists():
    tasks = [task(1, focus_date=date(2026, 11, 8), status="done", completed_at=at(8, 15)),
             task(2, focus_date=date(2026, 11, 8)), task(3, focus_date=date(2026, 11, 8), status="done",
                                                         completed_at=at(8, 16))]
    r = metrics.compute(messages=[], events=[], tasks=tasks, start=date(2026, 11, 8), end=date(2026, 11, 9), tz=TZ)
    assert r.focus_source == "Today's 3" and r.focus_days == [(date(2026, 11, 8), 2, 3)]


def test_late_action_does_not_count():
    messages = [{"id": 1, "role": "assistant", "kind": "nudge", "task_ids": [1], "created_at": at(8, 10), "content": ""}]
    events = [{"task_id": 1, "kind": "progress", "note": None, "created_at": at(8, 10) + timedelta(hours=25)}]
    r = metrics.compute(messages=messages, events=events, tasks=[], start=date(2026, 11, 8),
                        end=date(2026, 11, 9), tz=TZ)
    assert r.nudges_acted_on == 0 and r.action_minutes_median is None
