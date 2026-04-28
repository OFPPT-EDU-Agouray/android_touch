# android_touch Accessibility Service backend

This module adds a non-root backend for modern Android devices. It runs as an
Android Accessibility Service and dispatches gestures with
`AccessibilityService.dispatchGesture`, so it does not need `/dev/input` access,
root, or SELinux policy changes.

The existing native backend remains useful for rooted, userdebug, or lab devices
where raw evdev writes are allowed. This module is intended for stock devices
where the native `/dev/input` path is blocked.

## What's in this build

- **Phase 1** — touch / gesture dispatch (`POST /` and `POST /v1/touch`).
- **Phase 2 (new)** — perception. The agent can ask "what's on screen?" and
  act on UI elements by reference instead of pixel coordinates:
  - `GET  /v1/ui_tree`         — serialized AccessibilityNodeInfo tree
  - `GET  /v1/focused`         — currently-input-focused node
  - `POST /v1/find`            — return all matches for a matcher
  - `POST /v1/click_node`      — `performAction(ACTION_CLICK)` on a matched node
  - `POST /v1/long_click_node` — `performAction(ACTION_LONG_CLICK)`
  - `POST /v1/scroll_to`       — recursive `ACTION_SCROLL_FORWARD` until match

## Build

```bash
./gradlew :accessibility-service:assembleDebug
```

The APK is written to:

```text
accessibility-service/build/outputs/apk/debug/accessibility-service-debug.apk
```

## Install and enable

```bash
adb install -r accessibility-service/build/outputs/apk/debug/accessibility-service-debug.apk
adb shell am start -n io.github.androidtouch.accessibility/.MainActivity
```

On the device, tap **Open Accessibility settings** and enable
**android_touch gesture service**.

> **Important:** Phase 2 requires `canRetrieveWindowContent="true"` in
> `accessibility_service_config.xml` (already enabled in this build). The user
> will see this on the Accessibility settings page as
> "Observe your actions / Retrieve window content."
>
> If you re-enable the service after upgrading from Phase 1, the user must
> *toggle off and on again* so Android picks up the new flag.

## Connect from the host

```bash
adb forward tcp:9889 tcp:9889
curl http://localhost:9889/health
# → {"status":"ok","backend":"accessibility","phase":2}
```

---

## Phase 1 — gesture dispatch

```bash
curl -d '[{"type":"down","contact":0,"x":100,"y":100},{"type":"delay","value":50},{"type":"up","contact":0}]' \
  http://localhost:9889/v1/touch
```

Supported commands:

- `down`, `move`, `up`, `delay`, `commit`, `reset`. `stop` is rejected.
- `pressure` is accepted but ignored — Android's gesture API does not expose it.
- Coordinates are Android screen pixels.
- Total gesture duration is limited to 60 seconds; up to 10 contacts.

---

## Phase 2 — perception

### `GET /v1/ui_tree`

JSON snapshot of every accessible window's node tree.

Query parameters:

| param          | type    | default | meaning                                                |
|----------------|---------|---------|--------------------------------------------------------|
| `max_depth`    | int     | 32      | hard cap on recursion depth                            |
| `visible_only` | bool    | true    | drop nodes where `isVisibleToUser()` is false          |
| `package`      | string  | —       | only include nodes whose `packageName` equals this     |

Response shape (truncated for readability):

```json
{
  "windows": [
    {
      "id": 0, "type": "application", "active": true, "focused": true,
      "bounds": {"left":0,"top":0,"right":1080,"bottom":2400, "...":"..."},
      "root": {
        "depth": 0,
        "class": "android.widget.FrameLayout",
        "package": "com.android.settings",
        "bounds": {"...":"..."},
        "clickable": false, "scrollable": false, "...": true,
        "children": [ /* recursive */ ]
      }
    }
  ],
  "node_count": 187,
  "truncated": false
}
```

Per-node fields: `text`, `content_desc`, `view_id`, `class`, `package`,
`bounds`, `clickable`, `long_clickable`, `focusable`, `focused`, `scrollable`,
`checkable`, `checked`, `enabled`, `selected`, `password`, `visible`, `editable`,
`depth`. Missing string fields are omitted (not emitted as `null`).

`bounds` contains `left, top, right, bottom, center_x, center_y, width, height`
in Android screen pixels.

The response is hard-capped at 5000 nodes; if exceeded, `"truncated": true`.

### `GET /v1/focused`

```bash
curl http://localhost:9889/v1/focused
# → {"found": true, "node": { "view_id": "...", "text": "...", "password": false, ... }}
# → {"found": false}
```

### `POST /v1/find`

Body:

```json
{
  "by": "text" | "viewId" | "contentDesc" | "class",
  "value": "...",
  "regex": false,            // optional, default false
  "timeout_ms": 0,           // optional; if >0 polls until match or timeout
  "max_depth": 32,           // optional
  "visible_only": true       // optional
}
```

Response:

```json
{
  "matches": [ { /* node summary */ }, ... ],
  "count": N
}
```

Returns immediately if `timeout_ms` is `0` or omitted.

### `POST /v1/click_node` and `POST /v1/long_click_node`

Same body shape as `find`. Returns:

```json
{ "status": "completed",
  "action": "ACTION_CLICK",
  "node": { /* node summary */ } }
```

If the matched node is not directly clickable / long-clickable, the action is
performed on the nearest actionable ancestor (climb up to 32 levels). On
mismatch returns HTTP 404 with `{"status":"error","error":"element_not_found"}`.

### `POST /v1/scroll_to`

Body adds `max_scrolls` (default 20). Performs `ACTION_SCROLL_FORWARD` on the
first visible scrollable container, waits for the UI to settle (~350 ms), then
re-searches. Stops when the matched node is visible, when the container refuses
further scroll, or when `max_scrolls` is reached.

Response on success:

```json
{ "status": "completed", "scrolls": 3, "node": { /* ... */ } }
```

If the target was already visible: `"status": "already_visible", "scrolls": 0`.
If no scrollable container exists: `"error": "no_scrollable"`.

---

## Quick try

```bash
adb forward tcp:9889 tcp:9889

# What's on screen?
curl 'http://localhost:9889/v1/ui_tree?visible_only=true&max_depth=8' | jq

# Click the "OK" button
curl -X POST http://localhost:9889/v1/click_node \
     -H 'Content-Type: application/json' \
     -d '{"by":"text","value":"OK","timeout_ms":2000}'

# Scroll down until "Bluetooth" is visible
curl -X POST http://localhost:9889/v1/scroll_to \
     -H 'Content-Type: application/json' \
     -d '{"by":"text","value":"Bluetooth","max_scrolls":15}'
```

---

## Limitations

- Accessibility queries cannot see inside SurfaceView / WebView canvases or
  custom-drawn games. Phase 3 (screenshots) will help here.
- Performing `ACTION_CLICK` on a node marked clickable is far more reliable
  than coordinate taps, but a small minority of apps (e.g. games using their
  own input loop) will not respond. Fall back to Phase 1 coordinate gestures.
- The HTTP server is still single-threaded — long `timeout_ms` on `find` /
  `scroll_to` blocks subsequent requests. Phase 7 will introduce a fixed
  thread pool.
- The user must keep the accessibility service enabled. After upgrading from
  Phase 1 to Phase 2, the new `canRetrieveWindowContent` permission requires
  the user to toggle the service off then on once.
