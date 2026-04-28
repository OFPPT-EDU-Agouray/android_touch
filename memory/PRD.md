# android_touch — Phase 2 (Perception)

## Original problem statement
Roadmap from "what we have today" to "production AI agent that drives Android
devices." Phase 1 already shipped (gesture/touch HTTP primitives). This delivery
implements **Phase 2 — Perception**: turn coordinate-based taps into structured
node references so an LLM can ask "what's on screen?" and act by reference.

## Architecture
- Android Accessibility Service module at `/app/accessibility-service/`.
- Custom raw-socket HTTP server inside the service, port 9889 loopback.
- New Java classes:
  - `UiNodeMatcher` — `{by, value, regex}` predicate
  - `UiTreeBuilder` — JSON serialization of the AccessibilityNodeInfo tree
  - `UiActions` — find / click / long_click / scroll_to / focused
- New endpoints wired into `GestureHttpServer` (now a multi-route router):
  - `GET  /v1/ui_tree?max_depth=&visible_only=&package=`
  - `GET  /v1/focused`
  - `POST /v1/find`
  - `POST /v1/click_node`
  - `POST /v1/long_click_node`
  - `POST /v1/scroll_to`
- Phase 1 endpoints (`POST /`, `POST /v1/touch`, `GET /health`) unchanged.

## Permission change
`accessibility_service_config.xml` flipped `canRetrieveWindowContent` from
`false` → `true`, and added `flagReportViewIds | flagIncludeNotImportantViews`.
After upgrading from Phase 1 the user must toggle the service off/on for
Android to grant the new flag.

## What's been implemented (2026-01)
- All 6 Phase-2 endpoints, including timeout-polling for `find`/`click_node`/
  `long_click_node`/`scroll_to`, climb-to-clickable ancestor on click,
  visible-only filtering, package filtering, max-depth cap, 5000-node ceiling
  with `truncated:true` flag.
- `URLDecoder`-based query-string parser.
- Python helper `python_api/perception.py` for quick CLI testing.
- Updated `accessibility-service/README.md` with full Phase-2 endpoint docs.
- Validated by compiling every Java file against `android.jar` (API 29);
  compile clean, no errors.

## Backlog / next phases
- **Phase 3 (P0)** — `GET /v1/screenshot`, optional `?annotated=true&overlay=ui_tree`.
  Requires `canTakeScreenshot="true"` in service config + `takeScreenshot()` handler.
- **Phase 4 (P0)** — Python SDK + MCP server wrapping all endpoints.
- **Phase 5 (P1)** — agent helpers: `wait_for`, `wait_for_idle`, `dismiss_dialog`,
  `scroll_until`, `back_until`, `installed_apps`, `clipboard`, `notification_action`.
- **Phase 6 (P1)** — multi-turn agent runtime.
- **Phase 7 (P2)** — auth token, fixed-thread-pool concurrency, rate limiting,
  audit log, per-token allowlist, sticky-notification recovery.
- **Phase 8 (P2)** — OCR fallback, image-template matching, voice in/out.

## Test credentials
Not applicable (no auth in Phases 1–2).
