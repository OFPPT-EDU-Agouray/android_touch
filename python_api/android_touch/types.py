"""Typed response objects for the android_touch SDK.

These are lightweight dataclasses over the JSON shapes returned by the
device-side HTTP server. They preserve the original dict as ``raw`` so
unexpected keys are never lost.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class Bounds:
    """Android screen-pixel bounds of a UI element."""

    left: int
    top: int
    right: int
    bottom: int
    width: int = 0
    height: int = 0
    center_x: int = 0
    center_y: int = 0

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> Bounds:
        left = int(data.get("left", 0))
        top = int(data.get("top", 0))
        right = int(data.get("right", 0))
        bottom = int(data.get("bottom", 0))
        return cls(
            left=left,
            top=top,
            right=right,
            bottom=bottom,
            width=int(data.get("width", right - left)),
            height=int(data.get("height", bottom - top)),
            center_x=int(data.get("center_x", (left + right) // 2)),
            center_y=int(data.get("center_y", (top + bottom) // 2)),
        )


@dataclass
class UiNode:
    """A summarized AccessibilityNodeInfo as returned by the server."""

    text: str | None = None
    content_desc: str | None = None
    view_id: str | None = None
    class_name: str | None = None
    package: str | None = None
    bounds: Bounds | None = None
    clickable: bool = False
    long_clickable: bool = False
    focusable: bool = False
    focused: bool = False
    scrollable: bool = False
    checkable: bool = False
    checked: bool = False
    enabled: bool = True
    selected: bool = False
    password: bool = False
    visible: bool = True
    editable: bool = False
    depth: int = 0
    children: list[UiNode] = field(default_factory=list)
    raw: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> UiNode:
        bounds_data = data.get("bounds")
        return cls(
            text=data.get("text"),
            content_desc=data.get("content_desc"),
            view_id=data.get("view_id"),
            class_name=data.get("class"),
            package=data.get("package"),
            bounds=Bounds.from_json(bounds_data) if isinstance(bounds_data, dict) else None,
            clickable=bool(data.get("clickable", False)),
            long_clickable=bool(data.get("long_clickable", False)),
            focusable=bool(data.get("focusable", False)),
            focused=bool(data.get("focused", False)),
            scrollable=bool(data.get("scrollable", False)),
            checkable=bool(data.get("checkable", False)),
            checked=bool(data.get("checked", False)),
            enabled=bool(data.get("enabled", True)),
            selected=bool(data.get("selected", False)),
            password=bool(data.get("password", False)),
            visible=bool(data.get("visible", True)),
            editable=bool(data.get("editable", False)),
            depth=int(data.get("depth", 0)),
            children=[
                cls.from_json(child) for child in data.get("children", []) if isinstance(child, dict)
            ],
            raw=data,
        )

    def walk(self):
        """Yield this node and every descendant in depth-first order."""
        yield self
        for child in self.children:
            yield from child.walk()


@dataclass
class UiWindow:
    id: int = 0
    type: str = ""
    active: bool = False
    focused: bool = False
    bounds: Bounds | None = None
    root: UiNode | None = None
    raw: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> UiWindow:
        bounds_data = data.get("bounds")
        root_data = data.get("root")
        return cls(
            id=int(data.get("id", 0)),
            type=str(data.get("type", "")),
            active=bool(data.get("active", False)),
            focused=bool(data.get("focused", False)),
            bounds=Bounds.from_json(bounds_data) if isinstance(bounds_data, dict) else None,
            root=UiNode.from_json(root_data) if isinstance(root_data, dict) else None,
            raw=data,
        )


@dataclass
class UiTree:
    windows: list[UiWindow] = field(default_factory=list)
    node_count: int = 0
    truncated: bool = False
    raw: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> UiTree:
        return cls(
            windows=[UiWindow.from_json(w) for w in data.get("windows", []) if isinstance(w, dict)],
            node_count=int(data.get("node_count", 0)),
            truncated=bool(data.get("truncated", False)),
            raw=data,
        )

    def nodes(self):
        """Iterate every node across every window in depth-first order."""
        for window in self.windows:
            if window.root is not None:
                yield from window.root.walk()


@dataclass
class DisplayInfo:
    width: int
    height: int
    density: float

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> DisplayInfo:
        return cls(
            width=int(data["width"]),
            height=int(data["height"]),
            density=float(data["density"]),
        )


@dataclass
class ForegroundInfo:
    package: str | None
    activity: str | None

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> ForegroundInfo:
        return cls(package=data.get("package"), activity=data.get("activity"))


@dataclass
class ScrollResult:
    """Result of POST /v1/scroll_to."""

    status: str  # "completed" | "already_visible" | "error"
    scrolls: int = 0
    node: UiNode | None = None
    error: str | None = None
    raw: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_json(cls, data: dict[str, Any]) -> ScrollResult:
        node_data = data.get("node")
        return cls(
            status=str(data.get("status", "error")),
            scrolls=int(data.get("scrolls", 0)),
            node=UiNode.from_json(node_data) if isinstance(node_data, dict) else None,
            error=data.get("error"),
            raw=data,
        )


@dataclass
class Screenshot:
    """Binary screenshot response."""

    data: bytes
    format: str
    width: int = 0
    height: int = 0

    def save(self, path: str) -> None:
        with open(path, "wb") as f:
            f.write(self.data)


By = str
"""Matcher-kind strings accepted by the server: "text", "viewId", "contentDesc", "class"."""


VALID_BY: tuple[str, ...] = ("text", "viewId", "contentDesc", "class")
