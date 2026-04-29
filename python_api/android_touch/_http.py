"""Shared HTTP plumbing for the sync and async Device clients.

Kept intentionally small: build URLs, map server errors onto the SDK
exception hierarchy, and provide a retry-with-backoff wrapper around a
callable that returns an httpx.Response.
"""
from __future__ import annotations

import random
import time
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

import httpx

from .errors import (
    CaptureTimeoutError,
    ConnectionError,
    ElementNotFoundError,
    HTTPError,
    InvalidRequestError,
    NotSupportedError,
    ServiceNotConnectedError,
)

T = TypeVar("T")


DEFAULT_BASE_URL = "http://localhost:9889"
DEFAULT_TIMEOUT = 30.0
DEFAULT_RETRIES = 2


def build_url(base_url: str, path: str) -> str:
    return base_url.rstrip("/") + path


def _parse_error_body(response: httpx.Response) -> Any:
    ctype = response.headers.get("content-type", "")
    if "application/json" in ctype:
        try:
            return response.json()
        except ValueError:
            pass
    try:
        return response.text
    except Exception:  # pragma: no cover — truly defensive
        return response.content


def raise_for_status(response: httpx.Response) -> None:
    """Translate a server error response into the SDK's typed exceptions.

    Successful (2xx) responses return None. For non-2xx responses, the
    payload (JSON if possible, else text) is attached to the exception.
    """
    if 200 <= response.status_code < 300:
        return

    payload = _parse_error_body(response)
    code = response.status_code

    if code == 400:
        raise InvalidRequestError(code, payload)
    if code == 404:
        raise ElementNotFoundError(code, payload)
    if code == 501:
        raise NotSupportedError(code, payload)
    if code == 503:
        raise ServiceNotConnectedError(code, payload)
    if code == 504:
        raise CaptureTimeoutError(code, payload)
    raise HTTPError(code, payload)


def _should_retry(exc: BaseException) -> bool:
    # Retry on connect failures and read timeouts — not on HTTP errors;
    # those have already been classified by raise_for_status.
    return isinstance(exc, (httpx.ConnectError, httpx.ReadTimeout, httpx.RemoteProtocolError))


def retry_sync(fn: Callable[[], T], retries: int) -> T:
    last_exc: BaseException | None = None
    for attempt in range(retries + 1):
        try:
            return fn()
        except httpx.ConnectError as exc:
            last_exc = exc
            if attempt >= retries:
                raise ConnectionError(
                    f"could not connect to android_touch server: {exc}"
                ) from exc
        except (httpx.ReadTimeout, httpx.RemoteProtocolError) as exc:
            last_exc = exc
            if attempt >= retries:
                raise
        # exponential backoff with a little jitter; capped to keep tests fast
        time.sleep(min(0.25 * (2 ** attempt), 2.0) * (0.8 + 0.4 * random.random()))
    # Unreachable — the loop either returns or raises.
    raise AndroidTouchUnreachable(last_exc)  # type: ignore[name-defined]  # pragma: no cover


async def retry_async(fn: Callable[[], Awaitable[T]], retries: int) -> T:
    import asyncio

    last_exc: BaseException | None = None
    for attempt in range(retries + 1):
        try:
            return await fn()
        except httpx.ConnectError as exc:
            last_exc = exc
            if attempt >= retries:
                raise ConnectionError(
                    f"could not connect to android_touch server: {exc}"
                ) from exc
        except (httpx.ReadTimeout, httpx.RemoteProtocolError) as exc:
            last_exc = exc
            if attempt >= retries:
                raise
        await asyncio.sleep(min(0.25 * (2 ** attempt), 2.0) * (0.8 + 0.4 * random.random()))
    raise AndroidTouchUnreachable(last_exc)  # type: ignore[name-defined]  # pragma: no cover


class AndroidTouchUnreachable(ConnectionError):
    """Never-reached sentinel used by the retry loops; kept for clarity."""


__all__ = [
    "DEFAULT_BASE_URL",
    "DEFAULT_TIMEOUT",
    "DEFAULT_RETRIES",
    "build_url",
    "raise_for_status",
    "retry_sync",
    "retry_async",
]
