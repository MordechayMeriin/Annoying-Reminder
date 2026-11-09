"""
Thin Postgres helpers on a small connection pool.

Each database round trip is slow enough to matter (and opening a connection to the Supabase pooler,
TCP + TLS + login, costs several of them), so:
* connections are reused (a pool);
* they run in autocommit mode: every helper below is a single statement, and autocommit saves the
  separate BEGIN and COMMIT round trips. Code that needs several statements together uses
  `with db.connect() as conn, conn.transaction():`;
* a connection is checked before reuse only after sitting idle for a while: the pooler drops idle
  connections, but checking on every query would add a round trip each time.

These functions block. Async code calls them through asyncio.to_thread so the event loop stays free.
"""

import threading
import time
from contextlib import contextmanager
from typing import Any, Iterator

import psycopg
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from .config import get_settings

CHECK_AFTER_IDLE_S = 30

_pool: ConnectionPool | None = None
_pool_lock = threading.Lock()
_returned_at: dict[int, float] = {}  # id(connection) → when it went back to the pool


def _mark_returned(conn: psycopg.Connection) -> None:
    _returned_at[id(conn)] = time.monotonic()


def _check_if_idle(conn: psycopg.Connection) -> None:
    returned = _returned_at.pop(id(conn), None)
    if returned is None or time.monotonic() - returned > CHECK_AFTER_IDLE_S:
        ConnectionPool.check_connection(conn)  # raises if it's dead; the pool then replaces it


def _get_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        with _pool_lock:
            if _pool is None:
                _pool = ConnectionPool(
                    get_settings().database_url,
                    min_size=1,
                    max_size=5,
                    # prepare_threshold=None keeps psycopg compatible with Supabase's transaction pooler.
                    kwargs={"row_factory": dict_row, "prepare_threshold": None, "autocommit": True},
                    check=_check_if_idle,
                    reset=_mark_returned,
                    max_idle=300,
                    timeout=15,
                    name="db",
                    open=True,
                )
    return _pool


def close_pool() -> None:
    global _pool
    with _pool_lock:
        if _pool is not None:
            _pool.close()
            _pool = None
            _returned_at.clear()


@contextmanager
def connect() -> Iterator[psycopg.Connection]:
    """A pooled connection in autocommit mode (see the top of this file)."""
    with _get_pool().connection() as conn:
        yield conn


def fetch_all(sql: str, params: tuple | dict = ()) -> list[dict[str, Any]]:
    with connect() as conn:
        return list(conn.execute(sql, params).fetchall())


def fetch_one(sql: str, params: tuple | dict = ()) -> dict[str, Any] | None:
    with connect() as conn:
        return conn.execute(sql, params).fetchone()


def execute(sql: str, params: tuple | dict = ()) -> int:
    with connect() as conn:
        return conn.execute(sql, params).rowcount
