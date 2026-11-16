"""
One-off: copy everything from the old Supabase Postgres database into the DynamoDB table.

Ids stay the same (old links in notifications keep working), and the id counters continue after the
highest ones. It only reads from Postgres. It refuses to write into a table that already has tasks or
messages, unless --force (then items with the same ids are overwritten).

    cd backend
    pip install -r requirements.txt "psycopg[binary]"
    export DATABASE_URL=...                                  # the old Supabase connection string
    export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...   # the keys aws_setup.py printed
    export AWS_REGION=us-west-2 DYNAMODB_TABLE=prominder     # as on Render
    python scripts/migrate_from_postgres.py --dry-run        # counts only
    python scripts/migrate_from_postgres.py
"""

import argparse
import json
import os
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import db  # noqa: E402
from app.db import pad  # noqa: E402

TABLES = ("tasks", "task_events", "messages", "sessions", "preferences", "app_settings", "push_subscriptions",
          "job_runs")


def read_postgres(url: str) -> dict[str, list[dict[str, Any]]]:
    import psycopg
    from psycopg.rows import dict_row

    out: dict[str, list[dict[str, Any]]] = {}
    with psycopg.connect(url, row_factory=dict_row, prepare_threshold=None) as conn:
        conn.execute("set transaction read only")
        for table in TABLES:
            exists = conn.execute(
                "select 1 from information_schema.tables where table_schema = 'public' and table_name = %s",
                (table,)).fetchone()
            order = "order by id" if table not in ("app_settings", "job_runs", "push_subscriptions") else ""
            out[table] = list(conn.execute(f"select * from {table} {order}").fetchall()) if exists else []
    return out


def build_items(data: dict[str, list[dict[str, Any]]]) -> tuple[list[dict[str, Any]], dict[str, int]]:
    """Every typed DynamoDB item to write, and the counters' new values."""
    from app import notify, preferences, scheduler, sessions, tasks, thread

    items: list[dict[str, Any]] = []
    children: dict[int, list[int]] = defaultdict(list)
    focus: dict[Any, list[tuple[int, int]]] = defaultdict(list)
    for r in data["tasks"]:
        row = {k: r.get(k) for k in tasks.TASK.fields}
        items.append(tasks.TASK.item(row, version=1))
        if r["parent_id"] is not None:
            children[r["parent_id"]].append(r["id"])
        if r.get("focus_date") is not None:
            focus[r["focus_date"]].append((r.get("focus_rank") or 99, r["id"]))
    for parent, ids in children.items():
        items.append({"pk": {"S": "children"}, "sk": {"S": pad(parent)}, "ids": {"NS": [str(i) for i in ids]}})
    for day, ranked in focus.items():
        items.append(db.to_attr({"pk": "focus", "sk": day.isoformat(), "ids": [i for _, i in sorted(ranked)]}))

    for r in data["task_events"]:
        row = {k: r.get(k) for k in tasks.EVENT.fields if k != "rid"}
        items.append(tasks.EVENT.item({**row, "rid": f"m{r['id']:07d}"}, version=1))

    for r in data["messages"]:
        ids = list(dict.fromkeys(r.get("task_ids") or []))
        row = {k: r.get(k) for k in thread.MESSAGE.fields}
        row.update(task_ids=ids, chips=json.dumps(r.get("chips") or [], ensure_ascii=False))
        items.append(thread.MESSAGE.item(row, version=1))
        items += [db.to_attr({"pk": f"mtask#{t}", "sk": pad(r["id"])}) for t in ids]

    for r in data["sessions"]:
        items.append(sessions.SESSION.item({k: r.get(k) for k in sessions.SESSION.fields}, version=1))
    for r in data["preferences"]:
        items.append(preferences.PREFERENCE.item({k: r.get(k) for k in preferences.PREFERENCE.fields}, version=1))
    for r in data["app_settings"]:
        items.append(db.to_attr({"pk": "setting", "sk": r["key"], "value": r["value"],
                                 "updated_at": db.ts(r["updated_at"])}))
    for r in data["push_subscriptions"]:
        items.append(notify.PUSH_SUBSCRIPTION.item({k: r.get(k) for k in notify.PUSH_SUBSCRIPTION.fields}, version=1))
    for r in data["job_runs"]:
        items.append(scheduler.JOB_RUN.item({k: r.get(k) for k in scheduler.JOB_RUN.fields}, version=1))

    counters = {name: max((r["id"] for r in data[table]), default=0)
                for name, table in (("task", "tasks"), ("message", "messages"), ("session", "sessions"),
                                    ("preference", "preferences"))}
    return items, counters


def table_has_data() -> bool:
    return any(True for _ in db.query("msg", limit=1)) or any(True for _ in db.query("task", limit=1))


def main() -> None:
    ap = argparse.ArgumentParser(description="Copy the Supabase Postgres data into DynamoDB.")
    ap.add_argument("--dry-run", action="store_true", help="read and count, write nothing")
    ap.add_argument("--force", action="store_true", help="write even if the table already has data")
    a = ap.parse_args()
    url = os.environ.get("DATABASE_URL", "").strip()
    if not url:
        sys.exit("Set DATABASE_URL (the old Supabase connection string).")
    data = read_postgres(url)
    for table in TABLES:
        print(f"{table:20} {len(data[table]):6} rows")
    items, counters = build_items(data)
    print(f"→ {len(items)} DynamoDB items into {db._table()} ({os.environ.get('AWS_REGION', 'us-west-2')}); "
          f"counters {counters}")
    if a.dry_run:
        print("Dry run: nothing written.")
        return
    if table_has_data() and not a.force:
        sys.exit("The table already has tasks or messages. Use --force to write anyway (same ids are overwritten).")
    db.write_batch(items)
    for name, value in counters.items():
        db.set_counter_at_least(name, value)
    print("Done. Check the app, then you can pause or delete the Supabase project.")


if __name__ == "__main__":
    main()
