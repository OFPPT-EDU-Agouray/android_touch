"""android_touch — Python SDK for the android_touch accessibility server.

The SDK wraps the HTTP endpoints exposed by the on-device
``accessibility-service`` app and exposes them as ergonomic sync / async
clients for agent runtimes and scripts.

Quick start::

    from android_touch import Device

    with Device() as d:
        d.tap(500, 1200)
        d.click("text", "Send", timeout_ms=2000)
        screen = d.screenshot(annotated=True)
        screen.save("screen.png")
"""
from __future__ import annotations

from .device import AsyncDevice, Device
from .errors import (
    AndroidTouchError,
    CaptureTimeoutError,
    ConnectionError,
    ElementNotFoundError,
    GestureError,
    HTTPError,
    InvalidRequestError,
    NoFocusedInputError,
    NotSupportedError,
    ServiceNotConnectedError,
)
from .types import (
    Bounds,
    DisplayInfo,
    ForegroundInfo,
    Screenshot,
    ScrollResult,
    UiNode,
    UiTree,
    UiWindow,
)

__all__ = [
    "Device",
    "AsyncDevice",
    # Types
    "Bounds",
    "DisplayInfo",
    "ForegroundInfo",
    "Screenshot",
    "ScrollResult",
    "UiNode",
    "UiTree",
    "UiWindow",
    # Errors
    "AndroidTouchError",
    "CaptureTimeoutError",
    "ConnectionError",
    "ElementNotFoundError",
    "GestureError",
    "HTTPError",
    "InvalidRequestError",
    "NoFocusedInputError",
    "NotSupportedError",
    "ServiceNotConnectedError",
]

__version__ = "0.4.0"
