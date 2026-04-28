"""``android-touch`` command-line entry point.

Thin ergonomic wrapper around :class:`android_touch.Device` useful for quick
interactive debugging against a device forwarded to ``localhost:9889``.

Examples::

    android-touch health
    android-touch tap 500 1200
    android-touch swipe 100 100 100 900 --duration-ms 400
    android-touch tree --max-depth 6
    android-touch click text "Send" --timeout-ms 2000
    android-touch screenshot --annotated --out screen.png
"""
from __future__ import annotations

import argparse
import dataclasses
import json
import sys
from typing import Any

from ._http import DEFAULT_BASE_URL
from .device import Device
from .errors import AndroidTouchError
from .types import Screenshot, UiTree


def _dump(obj: Any) -> str:
    if dataclasses.is_dataclass(obj):
        # Use .raw if available so the CLI mirrors the raw server response,
        # which is more useful than a derived view for debugging.
        if hasattr(obj, "raw") and obj.raw:
            return json.dumps(obj.raw, indent=2, ensure_ascii=False)
        return json.dumps(dataclasses.asdict(obj), indent=2, ensure_ascii=False, default=str)
    if isinstance(obj, list):
        return json.dumps(
            [o.raw if hasattr(o, "raw") else o for o in obj],
            indent=2,
            ensure_ascii=False,
            default=str,
        )
    return json.dumps(obj, indent=2, ensure_ascii=False, default=str)


def _add_matcher_args(p: argparse.ArgumentParser) -> None:
    p.add_argument("by", choices=["text", "viewId", "contentDesc", "class"])
    p.add_argument("value")
    p.add_argument("--regex", action="store_true")
    p.add_argument("--timeout-ms", type=int, default=0)
    p.add_argument("--max-depth", type=int, default=32)
    p.add_argument("--no-visible-only", action="store_true")


def _matcher_kwargs(args: argparse.Namespace) -> dict[str, Any]:
    return {
        "regex": args.regex,
        "timeout_ms": args.timeout_ms,
        "max_depth": args.max_depth,
        "visible_only": not args.no_visible_only,
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="android-touch",
        description="CLI for the android_touch accessibility server.",
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help=f"device HTTP base URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("--timeout", type=float, default=30.0,
                        help="per-request HTTP timeout in seconds")
    sub = parser.add_subparsers(dest="cmd", required=True)

    sub.add_parser("health", help="ping the server and print the health payload")
    sub.add_parser("display", help="print display width/height/density")
    sub.add_parser("foreground", help="print current foreground app package/activity")

    p_tap = sub.add_parser("tap", help="dispatch a tap gesture")
    p_tap.add_argument("x", type=float)
    p_tap.add_argument("y", type=float)

    p_swipe = sub.add_parser("swipe", help="dispatch a swipe gesture")
    p_swipe.add_argument("x1", type=float)
    p_swipe.add_argument("y1", type=float)
    p_swipe.add_argument("x2", type=float)
    p_swipe.add_argument("y2", type=float)
    p_swipe.add_argument("--duration-ms", type=int, default=300)

    p_text = sub.add_parser("text", help="set text on the currently focused input")
    p_text.add_argument("text")

    p_key = sub.add_parser("key", help="send a global key (back, home, recents, ...)")
    p_key.add_argument("key")

    p_launch = sub.add_parser("launch", help="launch an app by package")
    p_launch.add_argument("package")
    p_launch.add_argument("--activity")

    p_tree = sub.add_parser("tree", help="dump the current UI tree as JSON")
    p_tree.add_argument("--max-depth", type=int, default=32)
    p_tree.add_argument("--no-visible-only", action="store_true")
    p_tree.add_argument("--package")

    sub.add_parser("focused", help="dump the currently focused input node")

    for name, help_ in [
        ("find", "find matching nodes (no action)"),
        ("click", "click the first node matching a matcher"),
        ("long-click", "long-click the first node matching a matcher"),
    ]:
        sp = sub.add_parser(name, help=help_)
        _add_matcher_args(sp)

    p_scroll = sub.add_parser("scroll-to", help="scroll a container until a node is visible")
    _add_matcher_args(p_scroll)
    p_scroll.add_argument("--max-scrolls", type=int, default=20)

    p_shot = sub.add_parser("screenshot", help="capture a screenshot")
    p_shot.add_argument("--format", choices=["png", "jpeg"], default="png")
    p_shot.add_argument("--scale", type=float, default=1.0)
    p_shot.add_argument("--quality", type=int, default=90)
    p_shot.add_argument("--annotated", action="store_true")
    p_shot.add_argument("--out", help="write image to this path (default: stdout)")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    try:
        with Device(args.base_url, timeout=args.timeout) as d:
            if args.cmd == "health":
                print(_dump(d.health()))
            elif args.cmd == "display":
                print(_dump(d.display()))
            elif args.cmd == "foreground":
                print(_dump(d.foreground()))
            elif args.cmd == "tap":
                print(_dump(d.tap(args.x, args.y)))
            elif args.cmd == "swipe":
                print(_dump(d.swipe(args.x1, args.y1, args.x2, args.y2, duration_ms=args.duration_ms)))
            elif args.cmd == "text":
                print(_dump(d.text(args.text)))
            elif args.cmd == "key":
                print(_dump(d.key(args.key)))
            elif args.cmd == "launch":
                print(_dump(d.launch(args.package, activity=args.activity)))
            elif args.cmd == "tree":
                tree: UiTree = d.ui_tree(
                    max_depth=args.max_depth,
                    visible_only=not args.no_visible_only,
                    package=args.package,
                )
                print(_dump(tree))
            elif args.cmd == "focused":
                print(_dump(d.focused()))
            elif args.cmd == "find":
                print(_dump(d.find(args.by, args.value, **_matcher_kwargs(args))))
            elif args.cmd == "click":
                print(_dump(d.click(args.by, args.value, **_matcher_kwargs(args))))
            elif args.cmd == "long-click":
                print(_dump(d.long_click(args.by, args.value, **_matcher_kwargs(args))))
            elif args.cmd == "scroll-to":
                print(_dump(
                    d.scroll_to(
                        args.by, args.value,
                        max_scrolls=args.max_scrolls,
                        **_matcher_kwargs(args),
                    )
                ))
            elif args.cmd == "screenshot":
                shot: Screenshot = d.screenshot(
                    format=args.format, scale=args.scale,
                    quality=args.quality, annotated=args.annotated,
                )
                if args.out:
                    shot.save(args.out)
                    print(f"wrote {len(shot.data)} bytes to {args.out} "
                          f"({shot.format} {shot.width}x{shot.height})")
                else:
                    sys.stdout.buffer.write(shot.data)
            else:  # pragma: no cover — argparse guarantees coverage
                parser.error(f"unknown command: {args.cmd}")
    except AndroidTouchError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
