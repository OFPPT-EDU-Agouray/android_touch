# android_touch Accessibility Service backend

This module adds a non-root backend for modern Android devices. It runs as an
Android Accessibility Service and dispatches gestures with
`AccessibilityService.dispatchGesture`, so it does not need `/dev/input` access,
root, or SELinux policy changes.

The existing native backend remains useful for rooted, userdebug, or lab devices
where raw evdev writes are allowed. This module is intended for stock devices
where the native `/dev/input` path is blocked.

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

Android requires the user to enable third-party accessibility services manually.
ADB cannot silently grant this on normal production devices.

## Connect from the host

The service listens only on device loopback, port `9889`. Forward that port
through ADB:

```bash
adb forward tcp:9889 tcp:9889
```

Check health:

```bash
curl http://localhost:9889/health
```

Send the same JSON touch command format used by the native backend:

```bash
curl -d '[{"type":"down","contact":0,"x":100,"y":100,"pressure":50},{"type":"commit"},{"type":"delay","value":50},{"type":"up","contact":0},{"type":"commit"}]' \
  http://localhost:9889/
```

The newer explicit endpoint is also supported:

```bash
curl -d '[{"type":"down","contact":0,"x":100,"y":100},{"type":"delay","value":50},{"type":"up","contact":0}]' \
  http://localhost:9889/v1/touch
```

## High-level convenience endpoints

These wrap the gesture engine with simpler request shapes that are easier
for automation (and AI agents) to drive than composing raw `down`/`move`/`up`
arrays. All POST bodies are a single JSON object. Responses are
`{"status":"completed"}` on success or `{"error":"..."}` with a 4xx/5xx code.

```bash
# Single tap
curl -d '{"x":540,"y":1200}' http://localhost:9889/v1/tap

# Swipe (defaults to 300ms duration)
curl -d '{"x1":540,"y1":2000,"x2":540,"y2":600,"duration_ms":250}' \
  http://localhost:9889/v1/swipe

# Replace text in the currently focused input field
curl -d '{"text":"hello world"}' http://localhost:9889/v1/text

# Global key actions: home, back, recents, notifications, quick_settings,
# power_dialog, lock_screen (API 28+), take_screenshot (API 28+).
curl -d '{"action":"home"}' http://localhost:9889/v1/key

# Launch an installed app by package name
curl -d '{"package":"com.android.settings"}' http://localhost:9889/v1/launch

# Read display geometry (pixels and density)
curl http://localhost:9889/v1/display
# {"width":1080,"height":2400,"density":2.625}

# Read the foreground app (last observed window-state-changed event)
curl http://localhost:9889/v1/foreground
# {"package":"com.android.settings","activity":"com.android.settings.Settings"}
```

## Supported command semantics

Supported commands:

- `down`: starts a contact stroke. Requires `contact`, `x`, and `y`.
- `move`: appends a point to an active contact stroke. Requires `contact`, `x`, and `y`.
- `up`: finishes an active contact stroke. Requires `contact`.
- `delay`: advances gesture time. Requires `value` in milliseconds.
- `commit`: accepted for compatibility with the native backend.
- `reset`: clears the gesture currently being parsed.

Unsupported command:

- `stop`: intentionally rejected by this backend.

Notes:

- `pressure` is accepted in JSON for compatibility but ignored. Android's
  Accessibility gesture API dispatches paths, not low-level evdev pressure.
- Coordinates are Android screen pixels.
- Contact IDs must be `0` through `9`.
- Total gesture duration is limited to 60 seconds.
- The service binds to `127.0.0.1` on the device. Use ADB port forwarding for
  host access.

## Limitations

Accessibility gestures are supported on Android API 24 and newer. They are
safer and work on non-rooted devices, but they are higher-level than raw evdev
events:

- They cannot set raw pressure, touch major, width, or device-specific evdev
  fields.
- Timing and latency are controlled by Android's accessibility framework.
- The user must keep the accessibility service enabled.
- Other accessibility services or system policy may affect gesture dispatch.
