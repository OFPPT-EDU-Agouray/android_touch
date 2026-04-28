"""
Tiny Phase 2 helper for the Android Touch accessibility service.

Use after `adb forward tcp:9889 tcp:9889`:

    from python_api.perception import Perception
    p = Perception()
    print(p.ui_tree(max_depth=6))
    p.click("text", "OK", timeout_ms=2000)

This is a thin convenience wrapper, intentionally NOT the full Phase 4 SDK.
"""
from __future__ import annotations

import json
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional


class PerceptionError(RuntimeError):
    pass


class Perception:
    def __init__(self, base_url: str = "http://localhost:9889", timeout: float = 60.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    # ---- raw helpers -------------------------------------------------

    def _get(self, path: str, query: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode({k: str(v) for k, v in query.items() if v is not None})
        req = urllib.request.Request(url, method="GET")
        return self._send(req)

    def _post(self, path: str, body: Dict[str, Any]) -> Dict[str, Any]:
        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            self.base_url + path,
            data=data,
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        return self._send(req)

    def _send(self, req: urllib.request.Request) -> Dict[str, Any]:
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", errors="replace")
            try:
                payload = json.loads(raw)
            except Exception:
                payload = {"error": raw}
            raise PerceptionError(f"HTTP {e.code}: {payload}") from None

    # ---- typed endpoints --------------------------------------------

    def health(self) -> Dict[str, Any]:
        return self._get("/health")

    def ui_tree(
        self,
        max_depth: int = 32,
        visible_only: bool = True,
        package: Optional[str] = None,
    ) -> Dict[str, Any]:
        return self._get(
            "/v1/ui_tree",
            {"max_depth": max_depth, "visible_only": str(visible_only).lower(), "package": package},
        )

    def focused(self) -> Dict[str, Any]:
        return self._get("/v1/focused")

    def find(
        self,
        by: str,
        value: str,
        *,
        regex: bool = False,
        timeout_ms: int = 0,
        visible_only: bool = True,
        max_depth: int = 32,
    ) -> List[Dict[str, Any]]:
        body = {
            "by": by, "value": value, "regex": regex,
            "timeout_ms": timeout_ms, "visible_only": visible_only, "max_depth": max_depth,
        }
        return self._post("/v1/find", body).get("matches", [])

    def click(self, by: str, value: str, **kw: Any) -> Dict[str, Any]:
        return self._post("/v1/click_node", {"by": by, "value": value, **kw})

    def long_click(self, by: str, value: str, **kw: Any) -> Dict[str, Any]:
        return self._post("/v1/long_click_node", {"by": by, "value": value, **kw})

    def scroll_to(self, by: str, value: str, *, max_scrolls: int = 20, **kw: Any) -> Dict[str, Any]:
        return self._post("/v1/scroll_to", {"by": by, "value": value, "max_scrolls": max_scrolls, **kw})


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Phase 2 quick test client")
    parser.add_argument("cmd", choices=["health", "tree", "focused", "find", "click", "long_click", "scroll_to"])
    parser.add_argument("--by")
    parser.add_argument("--value")
    parser.add_argument("--regex", action="store_true")
    parser.add_argument("--timeout-ms", type=int, default=0)
    parser.add_argument("--max-depth", type=int, default=32)
    parser.add_argument("--max-scrolls", type=int, default=20)
    parser.add_argument("--no-visible-only", action="store_true")
    parser.add_argument("--package")
    parser.add_argument("--base-url", default="http://localhost:9889")
    args = parser.parse_args()

    p = Perception(base_url=args.base_url)
    visible_only = not args.no_visible_only
    if args.cmd == "health":
        print(json.dumps(p.health(), indent=2))
    elif args.cmd == "tree":
        print(json.dumps(p.ui_tree(args.max_depth, visible_only, args.package), indent=2))
    elif args.cmd == "focused":
        print(json.dumps(p.focused(), indent=2))
    elif args.cmd == "find":
        print(json.dumps(
            p.find(args.by, args.value, regex=args.regex, timeout_ms=args.timeout_ms,
                   visible_only=visible_only, max_depth=args.max_depth),
            indent=2))
    elif args.cmd == "click":
        print(json.dumps(p.click(args.by, args.value, regex=args.regex,
                                 timeout_ms=args.timeout_ms, visible_only=visible_only), indent=2))
    elif args.cmd == "long_click":
        print(json.dumps(p.long_click(args.by, args.value, regex=args.regex,
                                      timeout_ms=args.timeout_ms, visible_only=visible_only), indent=2))
    elif args.cmd == "scroll_to":
        print(json.dumps(p.scroll_to(args.by, args.value, max_scrolls=args.max_scrolls,
                                     regex=args.regex, timeout_ms=args.timeout_ms,
                                     visible_only=visible_only), indent=2))
