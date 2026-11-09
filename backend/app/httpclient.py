"""
One shared httpx.AsyncClient, so calls to OpenRouter and Supabase reuse their TLS connections
instead of opening a new one each time. Timeouts are passed per request.

An AsyncClient belongs to the event loop it was created in, so a new one is made if the loop
changed (tests run each case in its own loop).
"""

import asyncio

import httpx

_client: httpx.AsyncClient | None = None
_loop: asyncio.AbstractEventLoop | None = None


def client() -> httpx.AsyncClient:
    global _client, _loop
    loop = asyncio.get_running_loop()
    if _client is None or _client.is_closed or _loop is not loop:
        _client = httpx.AsyncClient(timeout=20, limits=httpx.Limits(max_keepalive_connections=5))
        _loop = loop
    return _client


async def aclose() -> None:
    global _client, _loop
    if _client is not None and not _client.is_closed and _loop is asyncio.get_running_loop():
        await _client.aclose()
    _client, _loop = None, None
