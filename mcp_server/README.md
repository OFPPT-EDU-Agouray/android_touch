# android-touch-mcp

MCP server exposing the on-device [android_touch](../) accessibility API as
a set of LLM-friendly tools. Any MCP-compatible runtime (Claude Desktop,
Cursor, Continue, Devin, custom agents) can plug in with zero glue and drive
an Android device.

## Install

```bash
# From a checkout, install both SDK and server into the current env:
pip install -e python_api
pip install -e mcp_server
```

## Prerequisites

The on-device accessibility service must be running and the HTTP port
forwarded to the host. See [`../accessibility-service/README.md`](../accessibility-service/README.md):

```bash
./gradlew :accessibility-service:assembleDebug
adb install -r accessibility-service/build/outputs/apk/debug/accessibility-service-debug.apk
adb shell am start -n io.github.androidtouch.accessibility/.MainActivity
adb forward tcp:9889 tcp:9889
```

## Run

```bash
# Default: stdio transport, expects http://localhost:9889
android-touch-mcp

# Point at a different device:
android-touch-mcp --base-url http://localhost:9890

# Debug over SSE or streamable HTTP:
android-touch-mcp --transport sse
```

Set `ANDROID_TOUCH_BASE_URL` to change the default without passing the flag.

## Claude Desktop config

```json
{
  "mcpServers": {
    "android-touch": {
      "command": "android-touch-mcp",
      "args": ["--base-url", "http://localhost:9889"]
    }
  }
}
```

## Tools

| Tool                   | Purpose                                                           |
|------------------------|-------------------------------------------------------------------|
| `get_health`           | Server reachability / phase reporting                             |
| `get_display_info`     | Screen width / height / density                                   |
| `get_foreground_app`   | Currently foregrounded package/activity                           |
| `get_ui_tree`          | Structured accessibility tree (depth-limited)                     |
| `get_focused_element`  | Currently input-focused element                                   |
| `find_elements`        | Matcher-based lookup, no action                                   |
| `click_element`        | `performAction(ACTION_CLICK)` on a matched node                   |
| `long_click_element`   | `performAction(ACTION_LONG_CLICK)` on a matched node              |
| `scroll_to_element`    | Scroll until the matched node is visible                          |
| `tap_coordinate`       | Raw coordinate tap (fallback)                                     |
| `swipe`                | Coordinate swipe                                                  |
| `type_text`            | Set the focused editable field's text                             |
| `press_key`            | Global key: back / home / recents / power / …                     |
| `launch_app`           | Launch by package (+ optional activity)                           |
| `take_screenshot`      | PNG / JPEG, optional annotated overlay                            |

Matcher arguments: `by ∈ {text, viewId, contentDesc, class}`,
`value`, optional `regex`, `timeout_ms` (server-side polling),
`visible_only`, `max_depth`.
