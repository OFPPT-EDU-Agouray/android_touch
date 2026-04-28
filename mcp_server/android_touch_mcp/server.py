"""FastMCP server wrapping the android_touch SDK.

Each tool maps to one SDK method, with descriptions tuned for LLM
consumption — concrete verbs, shape-preserving return values, and
matcher semantics documented once on the module.

Matcher notation (used by every element-targeting tool):

* ``by`` is one of ``"text"``, ``"viewId"``, ``"contentDesc"``, ``"class"``.
* ``value`` is the literal to match (or a Java ``Pattern``-compatible
  regex if ``regex=True``).
* ``timeout_ms`` lets the server poll until a matching element appears,
  which replaces the common "screenshot / inspect / retry" loop.
"""
from __future__ import annotations

import argparse
import dataclasses
import os
import sys
from collections.abc import Callable
from typing import Any

from android_touch import AndroidTouchError, AsyncDevice, ElementNotFoundError
from android_touch.types import Screenshot
from mcp.server.fastmcp import Context, FastMCP, Image

from .tool_schemas import BY_LITERAL

# --------------------------------------------------------------------------- #
# Helpers                                                                     #
# --------------------------------------------------------------------------- #

def _to_dict(obj: Any) -> Any:
    """Convert dataclasses (and lists / dicts of them) to plain dicts."""
    if dataclasses.is_dataclass(obj) and not isinstance(obj, type):
        raw = getattr(obj, "raw", None)
        if isinstance(raw, dict) and raw:
            return raw
        return {k: _to_dict(v) for k, v in dataclasses.asdict(obj).items()}
    if isinstance(obj, list):
        return [_to_dict(x) for x in obj]
    if isinstance(obj, dict):
        return {k: _to_dict(v) for k, v in obj.items()}
    return obj


def _short_error(exc: AndroidTouchError) -> dict[str, Any]:
    """Return a shape the LLM can reason over — never raw tracebacks."""
    return {"ok": False, "error": type(exc).__name__, "message": str(exc)}


# --------------------------------------------------------------------------- #
# Server factory                                                              #
# --------------------------------------------------------------------------- #

def build_server(
    base_url: str = "http://localhost:9889",
    *,
    timeout: float = 30.0,
) -> FastMCP:
    """Build the FastMCP server bound to a specific device base URL."""

    app = FastMCP(
        name="android-touch",
        instructions=(
            "Tools to drive an Android device via the android_touch accessibility "
            "service. Prefer element-based tools (click_element, type_text, "
            "scroll_to_element) over coordinate-based ones (tap_coordinate, swipe) — "
            "coordinate actions break across screen sizes and layouts. Use "
            "get_ui_tree or find_elements first to discover what is on screen. "
            "Matchers: by is one of 'text', 'viewId', 'contentDesc', 'class'. "
            "Set timeout_ms to poll server-side for appearing elements instead of "
            "spinning client-side."
        ),
    )

    # A single, reusable async client for the lifetime of the server. We
    # don't hold it as a context manager because FastMCP doesn't expose a
    # lifespan hook in this shape — the OS will reap sockets on exit.
    device = AsyncDevice(base_url, timeout=timeout)

    def _wrap(fn: Callable[..., Any]) -> Callable[..., Any]:
        """Catch SDK errors and surface them as structured tool output."""
        import functools

        @functools.wraps(fn)
        async def inner(*args: Any, **kwargs: Any) -> Any:
            try:
                return await fn(*args, **kwargs)
            except ElementNotFoundError as exc:
                return {"ok": False, "error": "ElementNotFoundError", "message": str(exc)}
            except AndroidTouchError as exc:
                return _short_error(exc)

        return inner

    # ------------------------------------------------------------------ info

    @app.tool(
        name="get_health",
        description="Check whether the android_touch HTTP server is reachable and which phase of the API it supports.",
    )
    @_wrap
    async def get_health() -> dict[str, Any]:
        return await device.health()

    @app.tool(
        name="get_display_info",
        description="Return the device screen width, height, and density. Useful for translating coordinate inputs.",
    )
    @_wrap
    async def get_display_info() -> dict[str, Any]:
        return _to_dict(await device.display())

    @app.tool(
        name="get_foreground_app",
        description="Return the package/activity of the app currently in the foreground.",
    )
    @_wrap
    async def get_foreground_app() -> dict[str, Any]:
        return _to_dict(await device.foreground())

    # ----------------------------------------------------------- perception

    @app.tool(
        name="get_ui_tree",
        description=(
            "Return the current on-screen UI as a JSON tree of accessibility nodes "
            "(text, view_id, bounds, clickable, etc). Call this first when deciding "
            "what to tap. Prefer visible_only=true and max_depth<=8 to keep the "
            "response small."
        ),
    )
    @_wrap
    async def get_ui_tree(
        max_depth: int = 8,
        visible_only: bool = True,
        package: str | None = None,
    ) -> dict[str, Any]:
        tree = await device.ui_tree(
            max_depth=max_depth, visible_only=visible_only, package=package,
        )
        return tree.raw or _to_dict(tree)

    @app.tool(
        name="get_focused_element",
        description="Return the currently input-focused element (e.g., the active text field), or null if none.",
    )
    @_wrap
    async def get_focused_element() -> dict[str, Any] | None:
        node = await device.focused()
        return _to_dict(node) if node is not None else None

    @app.tool(
        name="find_elements",
        description=(
            "Return every UI element matching the matcher. Does not perform any "
            "action. Use timeout_ms>0 to wait for elements that appear after an "
            "animation or network fetch."
        ),
    )
    @_wrap
    async def find_elements(
        by: BY_LITERAL,
        value: str,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> list[dict[str, Any]]:
        matches = await device.find(
            by, value, regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        return [m.raw or _to_dict(m) for m in matches]

    @app.tool(
        name="click_element",
        description=(
            "Click the first UI element matching the matcher. Far more reliable "
            "than tap_coordinate because it is independent of screen size and "
            "layout. Returns the matched node's summary. Raises "
            "ElementNotFoundError (as a structured error) if nothing matched."
        ),
    )
    @_wrap
    async def click_element(
        by: BY_LITERAL,
        value: str,
        regex: bool = False,
        timeout_ms: int = 2000,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> dict[str, Any]:
        node = await device.click(
            by, value, regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        return node.raw or _to_dict(node)

    @app.tool(
        name="long_click_element",
        description="Long-click the first UI element matching the matcher.",
    )
    @_wrap
    async def long_click_element(
        by: BY_LITERAL,
        value: str,
        regex: bool = False,
        timeout_ms: int = 2000,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> dict[str, Any]:
        node = await device.long_click(
            by, value, regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        return node.raw or _to_dict(node)

    @app.tool(
        name="scroll_to_element",
        description=(
            "Scroll the first visible scrollable container until the matched element "
            "is on screen, then return its bounds. Use this before click_element when "
            "the element is in a long list."
        ),
    )
    @_wrap
    async def scroll_to_element(
        by: BY_LITERAL,
        value: str,
        regex: bool = False,
        timeout_ms: int = 0,
        max_scrolls: int = 20,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> dict[str, Any]:
        res = await device.scroll_to(
            by, value, regex=regex, timeout_ms=timeout_ms,
            max_scrolls=max_scrolls,
            visible_only=visible_only, max_depth=max_depth,
        )
        return res.raw or _to_dict(res)

    # ----------------------------------------------------------- primitives

    @app.tool(
        name="tap_coordinate",
        description=(
            "Tap a raw pixel coordinate. Prefer click_element when possible — "
            "coordinate taps break on layout/font/size changes."
        ),
    )
    @_wrap
    async def tap_coordinate(x: float, y: float) -> dict[str, Any]:
        return await device.tap(x, y)

    @app.tool(
        name="swipe",
        description=(
            "Swipe from (x1,y1) to (x2,y2) over duration_ms milliseconds. "
            "Useful for scrolling containers that the accessibility API cannot "
            "detect as scrollable."
        ),
    )
    @_wrap
    async def swipe(
        x1: float, y1: float, x2: float, y2: float, duration_ms: int = 300,
    ) -> dict[str, Any]:
        return await device.swipe(x1, y1, x2, y2, duration_ms=duration_ms)

    @app.tool(
        name="type_text",
        description=(
            "Set the contents of the currently focused editable field. Fails with "
            "NoFocusedInputError if no text field is focused — click_element on the "
            "field first."
        ),
    )
    @_wrap
    async def type_text(text: str) -> dict[str, Any]:
        return await device.text(text)

    @app.tool(
        name="press_key",
        description=(
            "Send a global hardware/system key. Accepted keys include "
            "'back', 'home', 'recents', 'power', 'notifications', 'quick_settings'."
        ),
    )
    @_wrap
    async def press_key(key: str) -> dict[str, Any]:
        return await device.key(key)

    @app.tool(
        name="launch_app",
        description=(
            "Launch an installed app by package (e.g. 'com.android.settings'). "
            "Optionally target a specific activity."
        ),
    )
    @_wrap
    async def launch_app(package: str, activity: str | None = None) -> dict[str, Any]:
        return await device.launch(package, activity=activity)

    # --------------------------------------------------------------- vision

    @app.tool(
        name="take_screenshot",
        description=(
            "Capture the current screen. Returns the encoded image (PNG by default) "
            "as an MCP image content block. Set annotated=true to overlay element "
            "bounds and view IDs — useful for grounding vision-LLMs."
        ),
    )
    async def take_screenshot(
        ctx: Context,
        format: str = "png",
        scale: float = 1.0,
        quality: int = 90,
        annotated: bool = False,
    ) -> Image:
        try:
            shot: Screenshot = await device.screenshot(
                format=format, scale=scale, quality=quality, annotated=annotated,
            )
        except AndroidTouchError as exc:
            await ctx.error(f"screenshot failed: {exc}")
            raise
        fmt = "jpeg" if shot.format == "jpeg" else "png"
        return Image(data=shot.data, format=fmt)

    return app


# --------------------------------------------------------------------------- #
# Entry point                                                                 #
# --------------------------------------------------------------------------- #

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="android-touch-mcp",
        description="MCP stdio server exposing the android_touch SDK as tools.",
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("ANDROID_TOUCH_BASE_URL", "http://localhost:9889"),
        help="device HTTP base URL (default: env ANDROID_TOUCH_BASE_URL or http://localhost:9889)",
    )
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument(
        "--transport",
        choices=["stdio", "sse", "streamable-http"],
        default="stdio",
        help="MCP transport. stdio is the default for local embedding; sse and "
             "streamable-http are useful for debugging.",
    )
    args = parser.parse_args(argv)

    app = build_server(args.base_url, timeout=args.timeout)
    try:
        app.run(transport=args.transport)
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
