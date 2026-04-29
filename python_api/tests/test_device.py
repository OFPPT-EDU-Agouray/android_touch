"""Unit tests for the sync Device client.

Uses httpx's MockTransport to stub the device-side server — no real HTTP
or Android device needed.
"""
from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from android_touch import (
    AsyncDevice,
    Device,
    ElementNotFoundError,
    GestureError,
    NoFocusedInputError,
    NotSupportedError,
    ServiceNotConnectedError,
)


def _node(text: str = "Send", view_id: str = "com.x:id/send") -> dict[str, Any]:
    return {
        "text": text,
        "view_id": view_id,
        "class": "android.widget.Button",
        "package": "com.x",
        "bounds": {
            "left": 100, "top": 200, "right": 300, "bottom": 260,
            "width": 200, "height": 60, "center_x": 200, "center_y": 230,
        },
        "clickable": True,
        "visible": True,
        "depth": 3,
    }


def _gesture_ok() -> dict[str, Any]:
    return {"status": "completed"}


def make_device(handler, *, retries: int = 0) -> Device:
    transport = httpx.MockTransport(handler)
    client = httpx.Client(transport=transport, base_url="http://testserver")
    return Device("http://testserver", client=client, retries=retries)


# --------------------------------------------------------------------------- #
# health / info                                                               #
# --------------------------------------------------------------------------- #

def test_health_ok():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/health"
        return httpx.Response(200, json={"status": "ok", "backend": "accessibility", "phase": 3})

    with make_device(handler) as d:
        assert d.health()["backend"] == "accessibility"


def test_display_typed():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/display"
        return httpx.Response(200, json={"width": 1080, "height": 2400, "density": 3.000})

    with make_device(handler) as d:
        info = d.display()
        assert (info.width, info.height) == (1080, 2400)
        assert info.density == 3.0


def test_foreground_typed():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"package": "com.a", "activity": "com.a.Main"})

    with make_device(handler) as d:
        fg = d.foreground()
        assert fg.package == "com.a"
        assert fg.activity == "com.a.Main"


# --------------------------------------------------------------------------- #
# primitives                                                                  #
# --------------------------------------------------------------------------- #

def test_tap_sends_coordinates_and_validates_status():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/tap"
        assert request.method == "POST"
        captured["body"] = json.loads(request.content.decode())
        return httpx.Response(200, json=_gesture_ok())

    with make_device(handler) as d:
        d.tap(123, 456)
    assert captured["body"] == {"x": 123, "y": 456}


def test_tap_raises_gesture_error_when_not_completed():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "cancelled", "error": "user_cancel"})

    with make_device(handler) as d, pytest.raises(GestureError):
        d.tap(1, 1)


def test_swipe_defaults_duration():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content.decode())
        return httpx.Response(200, json=_gesture_ok())

    with make_device(handler) as d:
        d.swipe(10, 20, 30, 40)
    assert captured["body"]["duration_ms"] == 300


def test_text_maps_no_focused_input_to_exception():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "no_focused_input"})

    with make_device(handler) as d, pytest.raises(NoFocusedInputError):
        d.text("hi")


def test_launch_omits_activity_when_none():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content.decode())
        return httpx.Response(200, json={"status": "ok"})

    with make_device(handler) as d:
        d.launch("com.x")
    assert captured["body"] == {"package": "com.x"}


# --------------------------------------------------------------------------- #
# perception                                                                  #
# --------------------------------------------------------------------------- #

def test_ui_tree_parses_and_walks():
    tree_json = {
        "windows": [
            {
                "id": 0, "type": "application", "active": True, "focused": True,
                "bounds": {"left": 0, "top": 0, "right": 10, "bottom": 10,
                           "width": 10, "height": 10, "center_x": 5, "center_y": 5},
                "root": {
                    "depth": 0, "class": "F", "package": "com.x",
                    "children": [_node("A"), _node("B")],
                },
            }
        ],
        "node_count": 3,
        "truncated": False,
    }

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/ui_tree"
        return httpx.Response(200, json=tree_json)

    with make_device(handler) as d:
        tree = d.ui_tree(max_depth=8, visible_only=True)
    nodes = list(tree.nodes())
    assert len(nodes) == 3
    assert {n.text for n in nodes if n.text} == {"A", "B"}


def test_find_returns_typed_nodes():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/find"
        assert json.loads(request.content.decode()) == {
            "by": "text", "value": "OK", "regex": False,
            "timeout_ms": 1000, "visible_only": True, "max_depth": 32,
        }
        return httpx.Response(200, json={"matches": [_node("OK")], "count": 1})

    with make_device(handler) as d:
        matches = d.find("text", "OK", timeout_ms=1000)
    assert len(matches) == 1
    assert matches[0].text == "OK"
    assert matches[0].bounds is not None
    assert matches[0].bounds.center_x == 200


def test_click_returns_node():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/click_node"
        return httpx.Response(200, json={"status": "completed", "action": "ACTION_CLICK",
                                          "node": _node()})

    with make_device(handler) as d:
        node = d.click("text", "Send")
    assert node.text == "Send"
    assert node.clickable


def test_click_maps_404_to_element_not_found():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"status": "error", "error": "element_not_found"})

    with make_device(handler) as d, pytest.raises(ElementNotFoundError):
        d.click("text", "Missing")


def test_scroll_to_result_is_typed():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/scroll_to"
        return httpx.Response(200, json={"status": "completed", "scrolls": 3, "node": _node("Bluetooth")})

    with make_device(handler) as d:
        r = d.scroll_to("text", "Bluetooth")
    assert r.status == "completed"
    assert r.scrolls == 3
    assert r.node is not None
    assert r.node.text == "Bluetooth"


def test_find_rejects_unknown_by():
    with make_device(lambda _req: httpx.Response(200, json={"matches": []})) as d, pytest.raises(ValueError):
        d.find("id", "x")  # type: ignore[arg-type]


# --------------------------------------------------------------------------- #
# vision                                                                      #
# --------------------------------------------------------------------------- #

def test_screenshot_returns_binary_response():
    body = b"\x89PNG\r\n\x1a\n" + b"stub"

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/screenshot"
        params = dict(request.url.params)
        assert params["format"] == "png"
        assert params["annotated"] == "true"
        return httpx.Response(
            200,
            content=body,
            headers={
                "Content-Type": "image/png",
                "X-Image-Width": "540",
                "X-Image-Height": "1200",
            },
        )

    with make_device(handler) as d:
        shot = d.screenshot(annotated=True)
    assert shot.data == body
    assert shot.format == "png"
    assert (shot.width, shot.height) == (540, 1200)


def test_screenshot_not_supported_on_old_android():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(501, json={"error": "not_supported"})

    with make_device(handler) as d, pytest.raises(NotSupportedError):
        d.screenshot()


# --------------------------------------------------------------------------- #
# error mapping                                                               #
# --------------------------------------------------------------------------- #

def test_503_maps_to_service_not_connected():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"error": "accessibility_service_not_connected"})

    with make_device(handler) as d, pytest.raises(ServiceNotConnectedError):
        d.ui_tree()


# --------------------------------------------------------------------------- #
# async                                                                       #
# --------------------------------------------------------------------------- #

@pytest.mark.asyncio
async def test_async_device_click_roundtrip():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "completed", "node": _node()})

    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(transport=transport, base_url="http://testserver")
    async with AsyncDevice("http://testserver", client=client) as d:
        node = await d.click("text", "Send")
    assert node.text == "Send"
