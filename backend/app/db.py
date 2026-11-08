"""Thin Postgres helpers. One short-lived connection per call is plenty for a single user."""

from contextlib import contextmanager
from typing import Any, Iterator

import psycopg
from psycopg.rows import dict_row

from .config import get_settings


@contextmanager
def connect() -> Iterator[psycopg.Connection]:
    # prepare_threshold=None keeps psycopg compatible with Supabase's transaction pooler.
    conn = psycopg.connect(get_settings().database_url, row_factory=dict_row, prepare_threshold=None)
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def fetch_all(sql: str, params: tuple | dict = ()) -> list[dict[str, Any]]:
    with connect() as conn:
        return list(conn.execute(sql, params).fetchall())


def fetch_one(sql: str, params: tuple | dict = ()) -> dict[str, Any] | None:
    with connect() as conn:
        return conn.execute(sql, params).fetchone()


def execute(sql: str, params: tuple | dict = ()) -> int:
    with connect() as conn:
        return conn.execute(sql, params).rowcount
