# android-touch — Python SDK

Typed Python client for the [android_touch](../) accessibility HTTP server.
Wraps every Phase 1–3 endpoint as a sync or async method with dataclass
return types and an exception hierarchy designed for agent runtimes.

> **Legacy helpers** — the older `touch.py`, `perception.py`, and
> `touch_test.py` scripts live in this directory for backwards
> compatibility. New code should use `android_touch.Device` /
> `android_touch.AsyncDevice` instead.

## Install

```bash
pip install -e python_api            # from a repo checkout
# or once published:
pip install android-touch
```

Requires Python 3.10+.

## Prerequisites

The on-device accessibility service must be running and the HTTP port
forwarded to the host. See [`../accessibility-service/README.md`](../accessibility-service/README.md):

```bash
./gradlew :accessibility-service:assembleDebug
adb install -r accessibility-service/build/outputs/apk/debug/accessibility-service-debug.apk
adb shell am start -n io.github.androidtouch.accessibility/.MainActivity
adb forward tcp:9889 tcp:9889
```

## Quick start

```python
from android_touch import Device

with Device() as d:
    print(d.health())                              # phase / backend
    d.launch("com.android.settings")
    d.click("text", "Bluetooth", timeout_ms=2000)  # no pixel guessing
    d.scroll_to("text", "Advanced")
    d.screenshot(annotated=True).save("bt.png")
```

Async is identical:

```python
import asyncio
from android_touch import AsyncDevice

async def main():
    async with AsyncDevice() as d:
        node = await d.click("viewId", "com.x:id/send")
        print(node.bounds)

asyncio.run(main())
```

## CLI

Installs as `android-touch`:

```bash
android-touch health
android-touch display
android-touch tap 500 1200
android-touch swipe 100 100 100 900 --duration-ms 400
android-touch tree --max-depth 6 > tree.json
android-touch click text "Send" --timeout-ms 2000
android-touch screenshot --annotated --out screen.png
```

## MCP server

An [MCP](https://modelcontextprotocol.io) server that exposes each SDK method
as an LLM tool ships as a sibling package — see [`../mcp_server/`](../mcp_server/).

## Errors

Every server error is mapped to a typed exception you can catch:

| Exception                    | When                                              |
|------------------------------|---------------------------------------------------|
| `ConnectionError`            | Server unreachable after retries                  |
| `InvalidRequestError`        | HTTP 400 — bad params                             |
| `ElementNotFoundError`       | HTTP 404 — no matching UI element                 |
| `NotSupportedError`          | HTTP 501 — feature not available (e.g. pre-11 screenshot) |
| `ServiceNotConnectedError`   | HTTP 503 — accessibility service not bound yet    |
| `CaptureTimeoutError`        | HTTP 504 — screenshot frame not delivered in time |
| `NoFocusedInputError`        | `text()` called with no focused editable field    |
| `GestureError`               | A gesture dispatch was cancelled / rejected       |
| `HTTPError`                  | Any other non-2xx status                          |

## Tests

```bash
cd python_api
pip install -e ".[dev]"
pytest
```
