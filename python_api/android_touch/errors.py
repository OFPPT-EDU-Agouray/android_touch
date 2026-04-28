"""Error hierarchy for the android_touch Python SDK."""
from __future__ import annotations

from typing import Any


class AndroidTouchError(Exception):
    """Base class for all android_touch SDK errors."""


class ConnectionError(AndroidTouchError):
    """The device HTTP server could not be reached."""


class HTTPError(AndroidTouchError):
    """Server returned a non-2xx HTTP status."""

    def __init__(self, status_code: int, payload: Any, message: str | None = None):
        self.status_code = status_code
        self.payload = payload
        super().__init__(message or f"HTTP {status_code}: {payload}")


class InvalidRequestError(HTTPError):
    """HTTP 400 — invalid parameters."""


class ServiceNotConnectedError(HTTPError):
    """HTTP 503 — accessibility service is not bound yet."""


class ElementNotFoundError(HTTPError):
    """HTTP 404 on a node-matching endpoint — no element matched the matcher."""


class NoFocusedInputError(AndroidTouchError):
    """The text endpoint was called but no focused editable field exists."""


class NotSupportedError(HTTPError):
    """HTTP 501 — feature not supported on this device (e.g. screenshot on pre-Android-11)."""


class CaptureTimeoutError(HTTPError):
    """HTTP 504 — the system did not deliver a screenshot frame in time."""


class GestureError(AndroidTouchError):
    """A gesture dispatch failed (cancelled, rejected, or timed out)."""

    def __init__(self, result: dict[str, Any]):
        self.result = result
        super().__init__(
            f"gesture {result.get('status', 'failed')}: {result.get('error') or result}"
        )
