"""Sync + async HTTP client for the android_touch accessibility server.

Typical usage::

    from android_touch import Device

    with Device() as d:
        d.tap(500, 1200)
        node = d.click("text", "Send")
        png = d.screenshot(annotated=True)

An :class:`AsyncDevice` with the same surface is available for agent
runtimes that prefer async I/O.
"""
from __future__ import annotations

from collections.abc import Iterable
from typing import Any

import httpx

from ._http import (
    DEFAULT_BASE_URL,
    DEFAULT_RETRIES,
    DEFAULT_TIMEOUT,
    build_url,
    raise_for_status,
    retry_async,
    retry_sync,
)
from .errors import ElementNotFoundError, GestureError, NoFocusedInputError
from .types import (
    VALID_BY,
    DisplayInfo,
    ForegroundInfo,
    Screenshot,
    ScrollResult,
    UiNode,
    UiTree,
)


def _validate_by(by: str) -> None:
    if by not in VALID_BY:
        raise ValueError(f"by must be one of {VALID_BY}, got {by!r}")


def _matcher_body(
    by: str,
    value: str,
    *,
    regex: bool = False,
    timeout_ms: int = 0,
    visible_only: bool = True,
    max_depth: int = 32,
    **extra: Any,
) -> dict[str, Any]:
    _validate_by(by)
    body: dict[str, Any] = {
        "by": by,
        "value": value,
        "regex": bool(regex),
        "timeout_ms": int(timeout_ms),
        "visible_only": bool(visible_only),
        "max_depth": int(max_depth),
    }
    body.update(extra)
    return body


def _check_gesture(result: dict[str, Any]) -> dict[str, Any]:
    """Raise GestureError if the gesture status is not ``completed``."""
    if str(result.get("status", "")).lower() != "completed":
        raise GestureError(result)
    return result


class _BaseDevice:
    """Common initialization / config for sync + async clients."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        *,
        timeout: float = DEFAULT_TIMEOUT,
        retries: int = DEFAULT_RETRIES,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.retries = retries


# --------------------------------------------------------------------------- #
# Sync client                                                                 #
# --------------------------------------------------------------------------- #

class Device(_BaseDevice):
    """Synchronous client for the android_touch HTTP API."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        *,
        timeout: float = DEFAULT_TIMEOUT,
        retries: int = DEFAULT_RETRIES,
        client: httpx.Client | None = None,
    ) -> None:
        super().__init__(base_url, timeout=timeout, retries=retries)
        self._client = client or httpx.Client(timeout=timeout)
        self._owns_client = client is None

    # --- context manager ----------------------------------------------------

    def close(self) -> None:
        if self._owns_client:
            self._client.close()

    def __enter__(self) -> Device:
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    # --- low-level ----------------------------------------------------------

    def _get(self, path: str, params: dict[str, Any] | None = None) -> httpx.Response:
        url = build_url(self.base_url, path)

        def _call() -> httpx.Response:
            return self._client.get(url, params=params)

        return retry_sync(_call, self.retries)

    def _post(self, path: str, json: dict[str, Any] | None = None) -> httpx.Response:
        url = build_url(self.base_url, path)

        def _call() -> httpx.Response:
            return self._client.post(url, json=json)

        return retry_sync(_call, self.retries)

    def _get_json(self, path: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        resp = self._get(path, params=params)
        raise_for_status(resp)
        return resp.json()

    def _post_json(self, path: str, json: dict[str, Any] | None = None) -> dict[str, Any]:
        resp = self._post(path, json=json)
        raise_for_status(resp)
        return resp.json()

    # --- health / info ------------------------------------------------------

    def health(self) -> dict[str, Any]:
        return self._get_json("/health")

    def display(self) -> DisplayInfo:
        return DisplayInfo.from_json(self._get_json("/v1/display"))

    def foreground(self) -> ForegroundInfo:
        return ForegroundInfo.from_json(self._get_json("/v1/foreground"))

    # --- primitives ---------------------------------------------------------

    def tap(self, x: float, y: float) -> dict[str, Any]:
        return _check_gesture(self._post_json("/v1/tap", {"x": x, "y": y}))

    def swipe(
        self,
        x1: float,
        y1: float,
        x2: float,
        y2: float,
        *,
        duration_ms: int = 300,
    ) -> dict[str, Any]:
        return _check_gesture(
            self._post_json(
                "/v1/swipe",
                {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": duration_ms},
            )
        )

    def text(self, text: str) -> dict[str, Any]:
        """Set the contents of the currently focused editable field.

        Raises :class:`NoFocusedInputError` if no editable field is focused.
        """
        payload = self._post_json("/v1/text", {"text": text})
        if payload.get("status") == "no_focused_input":
            raise NoFocusedInputError("no focused editable input on screen")
        return payload

    def key(self, key: str) -> dict[str, Any]:
        """Send a global key action.

        ``key`` may be a symbolic name (``back``, ``home``, ``recents``,
        ``power``, ``notifications``, ``quick_settings``) accepted by the
        server, or any raw value the server understands.
        """
        return self._post_json("/v1/key", {"key": key})

    def launch(self, package: str, *, activity: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {"package": package}
        if activity is not None:
            body["activity"] = activity
        return self._post_json("/v1/launch", body)

    def touch(self, commands: Iterable[dict[str, Any]]) -> dict[str, Any]:
        """Send a raw gesture script (Phase 1 JSON array)."""
        # The server treats POST / and POST /v1/touch identically.
        return _check_gesture(self._post_json("/v1/touch", list(commands)))  # type: ignore[arg-type]

    # --- perception ---------------------------------------------------------

    def ui_tree(
        self,
        *,
        max_depth: int = 32,
        visible_only: bool = True,
        package: str | None = None,
    ) -> UiTree:
        params: dict[str, Any] = {
            "max_depth": max_depth,
            "visible_only": "true" if visible_only else "false",
        }
        if package:
            params["package"] = package
        return UiTree.from_json(self._get_json("/v1/ui_tree", params=params))

    def focused(self) -> UiNode | None:
        data = self._get_json("/v1/focused")
        node = data.get("node") if data.get("found") else None
        return UiNode.from_json(node) if isinstance(node, dict) else None

    def find(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> list[UiNode]:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        data = self._post_json("/v1/find", body)
        return [UiNode.from_json(m) for m in data.get("matches", []) if isinstance(m, dict)]

    def click(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> UiNode:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        data = self._post_json("/v1/click_node", body)
        node = data.get("node")
        if not isinstance(node, dict):
            raise ElementNotFoundError(404, data, "click response had no node")
        return UiNode.from_json(node)

    def long_click(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> UiNode:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        data = self._post_json("/v1/long_click_node", body)
        node = data.get("node")
        if not isinstance(node, dict):
            raise ElementNotFoundError(404, data, "long_click response had no node")
        return UiNode.from_json(node)

    def scroll_to(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
        max_scrolls: int = 20,
    ) -> ScrollResult:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
            max_scrolls=int(max_scrolls),
        )
        return ScrollResult.from_json(self._post_json("/v1/scroll_to", body))

    # --- vision -------------------------------------------------------------

    def screenshot(
        self,
        *,
        format: str = "png",
        scale: float = 1.0,
        quality: int = 90,
        annotated: bool = False,
    ) -> Screenshot:
        params: dict[str, Any] = {
            "format": format,
            "scale": scale,
            "quality": quality,
            "annotated": "true" if annotated else "false",
        }
        resp = self._get("/v1/screenshot", params=params)
        raise_for_status(resp)

        width = int(resp.headers.get("X-Image-Width", "0") or 0)
        height = int(resp.headers.get("X-Image-Height", "0") or 0)
        ctype = resp.headers.get("Content-Type", "image/png")
        fmt = "jpeg" if "jpeg" in ctype else "png"
        return Screenshot(data=resp.content, format=fmt, width=width, height=height)


# --------------------------------------------------------------------------- #
# Async client                                                                #
# --------------------------------------------------------------------------- #

class AsyncDevice(_BaseDevice):
    """Async variant of :class:`Device` backed by :class:`httpx.AsyncClient`."""

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        *,
        timeout: float = DEFAULT_TIMEOUT,
        retries: int = DEFAULT_RETRIES,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        super().__init__(base_url, timeout=timeout, retries=retries)
        self._client = client or httpx.AsyncClient(timeout=timeout)
        self._owns_client = client is None

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()

    async def __aenter__(self) -> AsyncDevice:
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        await self.aclose()

    # --- low-level ----------------------------------------------------------

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> httpx.Response:
        url = build_url(self.base_url, path)

        async def _call() -> httpx.Response:
            return await self._client.get(url, params=params)

        return await retry_async(_call, self.retries)

    async def _post(self, path: str, json: dict[str, Any] | None = None) -> httpx.Response:
        url = build_url(self.base_url, path)

        async def _call() -> httpx.Response:
            return await self._client.post(url, json=json)

        return await retry_async(_call, self.retries)

    async def _get_json(
        self, path: str, params: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        resp = await self._get(path, params=params)
        raise_for_status(resp)
        return resp.json()

    async def _post_json(
        self, path: str, json: dict[str, Any] | None = None
    ) -> dict[str, Any]:
        resp = await self._post(path, json=json)
        raise_for_status(resp)
        return resp.json()

    # --- methods mirror the sync surface ------------------------------------

    async def health(self) -> dict[str, Any]:
        return await self._get_json("/health")

    async def display(self) -> DisplayInfo:
        return DisplayInfo.from_json(await self._get_json("/v1/display"))

    async def foreground(self) -> ForegroundInfo:
        return ForegroundInfo.from_json(await self._get_json("/v1/foreground"))

    async def tap(self, x: float, y: float) -> dict[str, Any]:
        return _check_gesture(await self._post_json("/v1/tap", {"x": x, "y": y}))

    async def swipe(
        self, x1: float, y1: float, x2: float, y2: float, *, duration_ms: int = 300,
    ) -> dict[str, Any]:
        return _check_gesture(
            await self._post_json(
                "/v1/swipe",
                {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": duration_ms},
            )
        )

    async def text(self, text: str) -> dict[str, Any]:
        payload = await self._post_json("/v1/text", {"text": text})
        if payload.get("status") == "no_focused_input":
            raise NoFocusedInputError("no focused editable input on screen")
        return payload

    async def key(self, key: str) -> dict[str, Any]:
        return await self._post_json("/v1/key", {"key": key})

    async def launch(self, package: str, *, activity: str | None = None) -> dict[str, Any]:
        body: dict[str, Any] = {"package": package}
        if activity is not None:
            body["activity"] = activity
        return await self._post_json("/v1/launch", body)

    async def touch(self, commands: Iterable[dict[str, Any]]) -> dict[str, Any]:
        return _check_gesture(await self._post_json("/v1/touch", list(commands)))  # type: ignore[arg-type]

    async def ui_tree(
        self,
        *,
        max_depth: int = 32,
        visible_only: bool = True,
        package: str | None = None,
    ) -> UiTree:
        params: dict[str, Any] = {
            "max_depth": max_depth,
            "visible_only": "true" if visible_only else "false",
        }
        if package:
            params["package"] = package
        return UiTree.from_json(await self._get_json("/v1/ui_tree", params=params))

    async def focused(self) -> UiNode | None:
        data = await self._get_json("/v1/focused")
        node = data.get("node") if data.get("found") else None
        return UiNode.from_json(node) if isinstance(node, dict) else None

    async def find(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> list[UiNode]:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        data = await self._post_json("/v1/find", body)
        return [UiNode.from_json(m) for m in data.get("matches", []) if isinstance(m, dict)]

    async def click(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> UiNode:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        data = await self._post_json("/v1/click_node", body)
        node = data.get("node")
        if not isinstance(node, dict):
            raise ElementNotFoundError(404, data, "click response had no node")
        return UiNode.from_json(node)

    async def long_click(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> UiNode:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
        )
        data = await self._post_json("/v1/long_click_node", body)
        node = data.get("node")
        if not isinstance(node, dict):
            raise ElementNotFoundError(404, data, "long_click response had no node")
        return UiNode.from_json(node)

    async def scroll_to(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
        max_scrolls: int = 20,
    ) -> ScrollResult:
        body = _matcher_body(
            by, value,
            regex=regex, timeout_ms=timeout_ms,
            visible_only=visible_only, max_depth=max_depth,
            max_scrolls=int(max_scrolls),
        )
        return ScrollResult.from_json(await self._post_json("/v1/scroll_to", body))

    async def screenshot(
        self,
        *,
        format: str = "png",
        scale: float = 1.0,
        quality: int = 90,
        annotated: bool = False,
    ) -> Screenshot:
        params: dict[str, Any] = {
            "format": format,
            "scale": scale,
            "quality": quality,
            "annotated": "true" if annotated else "false",
        }
        resp = await self._get("/v1/screenshot", params=params)
        raise_for_status(resp)

        width = int(resp.headers.get("X-Image-Width", "0") or 0)
        height = int(resp.headers.get("X-Image-Height", "0") or 0)
        ctype = resp.headers.get("Content-Type", "image/png")
        fmt = "jpeg" if "jpeg" in ctype else "png"
        return Screenshot(data=resp.content, format=fmt, width=width, height=height)
