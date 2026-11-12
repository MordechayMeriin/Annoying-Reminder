"""
The success measures of the ADHD-assistant work, for a date range, from the existing tables.
Read-only: it only runs SELECTs.

    cd backend
    export DATABASE_URL=...            # the same value as on Render
    python scripts/metrics.py                          # the last 28 days
    python scripts/metrics.py --from 2026-10-01 --to 2026-11-01

Run it once before Phase 1 (the baseline) and again four weeks after Phase 2, and compare.

Measures (see the implementation brief, "Success measures"):
  1. Nudge → action time    proactive message about a task → the next done / progress / session start on it
  2. Focus tasks finished   per day: Today's 3 done (before Phase 1: due today and done today)
  3. Ignored check-ins      `ignored` events per week
  4. Repeatedly postponed   tasks with 3+ postponements in a week
  5. Overdue pile           open tasks past due_at at each week's end (approximate: cancelled tasks
                            are left out, since there's no cancelled_at)
  6. Taps vs typed replies  user messages of kind 'event' vs 'chat'
  7. Owner's rating         the Sunday "Did this help?" taps (from Phase 3; n/a before)

The computation is pure (compute()), so it's unit-tested without a database.
"""

import argparse
import statistics
import sys
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from typing import Any
from zoneinfo import ZoneInfo

PROACTIVE_TASK_KINDS = ("nudge", "reminder")    # check-ins about tasks (meetings/daily jobs excluded)
ACTION_EVENTS = ("done", "progress", "session_started")
ACTION_WINDOW = timedelta(hours=24)             # an action later than this isn't a response to the nudge


@dataclass
class Report:
    start: date
    end: date
    nudges: int
    nudges_acted_on: int
    action_minutes_median: float | None
    focus_days: list[tuple[date, int, int]]       # (day, done, planned)
    focus_source: str                             # "focus_date" | "due today"
    ignored_per_week: list[tuple[date, int]]
    postponed_tasks_per_week: list[tuple[date, int]]
    overdue_per_week: list[tuple[date, int]]
    taps: int
    typed: int
    ratings: list[int]

    def lines(self) -> list[str]:
        out = [f"Success measures {self.start} → {self.end} (end exclusive)", ""]
        acted = f"{self.nudges_acted_on}/{self.nudges}" if self.nudges else "0/0"
        med = f"{self.action_minutes_median:.0f} min" if self.action_minutes_median is not None else "n/a"
        out.append(f"1. Nudge → action time: median {med} (acted on within 24 h: {acted})")
        good = sum(1 for _, done, planned in self.focus_days if planned and done >= min(2, planned))
        planned_days = sum(1 for _, _, planned in self.focus_days if planned)
        out.append(f"2. Focus tasks finished ({self.focus_source}): {good}/{planned_days} days with 2 of 3 or better")
        for d, done, planned in self.focus_days:
            if planned:
                out.append(f"     {d:%a %d/%m}: {done}/{planned}")
        out.append("3. Ignored check-ins per week: " + _weeks(self.ignored_per_week))
        out.append("4. Tasks postponed 3+ times in a week: " + _weeks(self.postponed_tasks_per_week))
        out.append("5. Overdue pile at week end: " + _weeks(self.overdue_per_week))
        total = self.taps + self.typed
        share = f"{100 * self.taps / total:.0f}% taps" if total else "n/a"
        out.append(f"6. Taps vs typed replies: {self.taps} taps, {self.typed} typed ({share})")
        rating = f"avg {statistics.mean(self.ratings):.1f} over {len(self.ratings)}" if self.ratings else "n/a"
        out.append(f"7. Owner's rating: {rating}")
        return out


def _weeks(rows: list[tuple[date, int]]) -> str:
    return ", ".join(f"wk of {d:%d/%m}: {n}" for d, n in rows) or "n/a"


def _local_day(dt: datetime, tz: ZoneInfo) -> date:
    return dt.astimezone(tz).date()


def week_starts(start: date, end: date) -> list[date]:
    """Sunday-based weeks (the Israeli work week) overlapping [start, end)."""
    first = start - timedelta(days=(start.weekday() + 1) % 7)
    out, d = [], first
    while d < end:
        out.append(d)
        d += timedelta(days=7)
    return out


def compute(*, messages: list[dict[str, Any]], events: list[dict[str, Any]], tasks: list[dict[str, Any]],
            start: date, end: date, tz: ZoneInfo, sessions: list[dict[str, Any]] = ()) -> Report:
    """
    messages: id, role, kind, task_ids, created_at, content
    events:   task_id, kind, note, created_at
    tasks:    id, kind, status, due_at, created_at, completed_at, focus_date (optional key)
    sessions: task_id, started_at (Phase 2; counts as an action)
    """
    t0 = datetime.combine(start, time(), tz)
    t1 = datetime.combine(end, time(), tz)
    in_range = lambda dt: dt is not None and t0 <= dt < t1  # noqa: E731

    # 1. nudge → action
    actions_by_task: dict[int, list[datetime]] = {}
    for e in events:
        if e["kind"] in ACTION_EVENTS:
            actions_by_task.setdefault(e["task_id"], []).append(e["created_at"])
    for s in sessions:
        actions_by_task.setdefault(s["task_id"], []).append(s["started_at"])
    for v in actions_by_task.values():
        v.sort()
    nudges = [m for m in messages if m["kind"] in PROACTIVE_TASK_KINDS and in_range(m["created_at"])]
    minutes: list[float] = []
    for m in nudges:
        best = None
        for task_id in m["task_ids"] or []:
            nxt = next((a for a in actions_by_task.get(task_id, []) if a >= m["created_at"]), None)
            if nxt is not None and nxt - m["created_at"] <= ACTION_WINDOW and (best is None or nxt < best):
                best = nxt
        if best is not None:
            minutes.append((best - m["created_at"]).total_seconds() / 60)

    # 2. focus tasks finished, per day: Today's 3 on days that had one (Phase 1 on), else "due today"
    focus_dates = {t.get("focus_date") for t in tasks if t.get("focus_date")}
    focus_days = []
    d = start
    while d < end:
        if d in focus_dates:
            planned = [t for t in tasks if t.get("focus_date") == d]
        else:
            planned = [t for t in tasks if t["kind"] == "task" and t.get("due_at") is not None
                       and _local_day(t["due_at"], tz) == d and t["status"] != "cancelled"]
        done = [t for t in planned if t["status"] == "done" and t.get("completed_at")
                and _local_day(t["completed_at"], tz) <= d]
        focus_days.append((d, len(done), len(planned)))
        d += timedelta(days=1)

    # 3–5. per week
    weeks = week_starts(start, end)
    ignored, postponed, overdue = [], [], []
    for w in weeks:
        w0, w1 = datetime.combine(w, time(), tz), datetime.combine(w + timedelta(days=7), time(), tz)
        week_events = [e for e in events if w0 <= e["created_at"] < w1]
        ignored.append((w, sum(1 for e in week_events if e["kind"] == "ignored")))
        per_task: dict[int, int] = {}
        for e in week_events:
            if e["kind"] == "postponed":
                per_task[e["task_id"]] = per_task.get(e["task_id"], 0) + 1
        postponed.append((w, sum(1 for n in per_task.values() if n >= 3)))
        at = min(w1, t1)
        overdue.append((w, sum(
            1 for t in tasks
            if t["kind"] == "task" and t["status"] != "cancelled" and t.get("due_at") is not None
            and t["due_at"] < at and t["created_at"] < at
            and (t["status"] == "open" or (t.get("completed_at") and t["completed_at"] > at)))))

    # 6. taps vs typed
    users = [m for m in messages if m["role"] == "user" and in_range(m["created_at"])]
    taps = sum(1 for m in users if m["kind"] == "event")
    typed = sum(1 for m in users if m["kind"] == "chat")

    # 7. rating events, written from Phase 3 as "[Rated this week: 4]"
    ratings = []
    for m in users:
        if m["kind"] == "event" and m.get("content", "").startswith("[Rated this week: "):
            try:
                ratings.append(int(m["content"].split(":")[1].strip(" ]")))
            except ValueError:
                pass

    return Report(start=start, end=end, nudges=len(nudges), nudges_acted_on=len(minutes),
                  action_minutes_median=statistics.median(minutes) if minutes else None,
                  focus_days=focus_days,
                  focus_source=("Today's 3" if all(d in focus_dates for d, _, p in focus_days if p) else
                                "due today" if not focus_dates & {d for d, _, _ in focus_days} else
                                "Today's 3 where set, else due today"),
                  ignored_per_week=ignored, postponed_tasks_per_week=postponed, overdue_per_week=overdue,
                  taps=taps, typed=typed, ratings=ratings)


# ---------------------------------------------------------------- database

class _DB:
    """Plain read-only queries on DATABASE_URL (no app settings needed)."""

    def __init__(self, url: str):
        import psycopg
        from psycopg.rows import dict_row
        self.conn = psycopg.connect(url, row_factory=dict_row, prepare_threshold=None, autocommit=True)
        self.conn.execute("set default_transaction_read_only = on")

    def fetch_all(self, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
        return list(self.conn.execute(sql, params).fetchall())

    def fetch_one(self, sql: str, params: tuple = ()) -> dict[str, Any] | None:
        return self.conn.execute(sql, params).fetchone()


def _column_exists(db: _DB, table: str, column: str) -> bool:
    return db.fetch_one(
        """select 1 as x from information_schema.columns
           where table_schema = 'public' and table_name = %s and column_name = %s""", (table, column)) is not None


def _table_exists(db: _DB, table: str) -> bool:
    return db.fetch_one(
        "select 1 as x from information_schema.tables where table_schema = 'public' and table_name = %s",
        (table,)) is not None


def load(db: _DB, start: date, end: date, tz: ZoneInfo) -> dict[str, Any]:
    t0 = datetime.combine(start, time(), tz)
    t1 = datetime.combine(end, time(), tz) + ACTION_WINDOW
    focus = ", focus_date" if _column_exists(db, "tasks", "focus_date") else ""
    sessions = []
    if _table_exists(db, "sessions"):
        sessions = db.fetch_all("select task_id, started_at from sessions where started_at >= %s and started_at < %s",
                                (t0, t1))
    return {
        "messages": db.fetch_all(
            """select id, role, kind, task_ids, created_at, content from messages
               where created_at >= %s and created_at < %s order by created_at""", (t0, t1)),
        "events": db.fetch_all(
            """select task_id, kind, note, created_at from task_events
               where created_at >= %s and created_at < %s order by created_at""", (t0 - timedelta(days=7), t1)),
        "tasks": db.fetch_all(
            f"select id, kind, status, due_at, created_at, completed_at{focus} from tasks where parent_id is null"),
        "sessions": sessions,
    }


def main(argv: list[str] | None = None) -> None:
    import os
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[1], formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--from", dest="start", type=date.fromisoformat)
    ap.add_argument("--to", dest="end", type=date.fromisoformat, help="exclusive (default: today)")
    ap.add_argument("--tz", default=os.environ.get("TIMEZONE", "Asia/Jerusalem"))
    a = ap.parse_args(argv)
    tz = ZoneInfo(a.tz)
    end = a.end or datetime.now(tz).date()
    start = a.start or end - timedelta(days=28)
    url = os.environ.get("DATABASE_URL", "").strip()
    if not url:
        sys.exit("Set DATABASE_URL (the same value as on Render).")
    data = load(_DB(url), start, end, tz)
    print("\n".join(compute(start=start, end=end, tz=tz, **data).lines()))


if __name__ == "__main__":
    main()
