package io.github.androidtouch.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.IOException;

public class AndroidTouchAccessibilityService extends AccessibilityService {
    private static AndroidTouchAccessibilityService instance;

    private GestureHttpServer server;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile ForegroundInfo foreground = new ForegroundInfo(null, null);

    public static AndroidTouchAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        server = new GestureHttpServer(9889);
        try {
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start gesture server", exception);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            ForegroundInfo current = foreground;
            String newPkg = pkg != null ? pkg.toString() : current.packageName;
            String newCls = cls != null ? cls.toString() : current.className;
            foreground = new ForegroundInfo(newPkg, newCls);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stopServer();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stopServer();
        instance = null;
        super.onDestroy();
    }

    public GestureResult dispatch(TouchGesture gesture) throws InterruptedException {
        GestureDescription description = buildGestureDescription(gesture);
        GestureResultCallbackBridge callback = new GestureResultCallbackBridge();
        mainHandler.post(() -> {
            boolean accepted = dispatchGesture(description, callback, mainHandler);
            if (!accepted) {
                callback.cancel();
            }
        });
        return callback.await();
    }

    public GestureResult dispatchTap(float x, float y) throws InterruptedException {
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, 1L));
        return dispatchInternal(builder.build());
    }

    public GestureResult dispatchSwipe(float x1, float y1, float x2, float y2, long durationMs) throws InterruptedException {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, Math.max(1L, durationMs)));
        return dispatchInternal(builder.build());
    }

    public boolean setFocusedText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) {
                return false;
            }
            try {
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text == null ? "" : text);
                return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            } finally {
                focused.recycle();
            }
        } finally {
            root.recycle();
        }
    }

    public boolean performKey(String action) throws GestureParseException {
        int globalAction;
        switch (action == null ? "" : action) {
            case "home":
                globalAction = GLOBAL_ACTION_HOME;
                break;
            case "back":
                globalAction = GLOBAL_ACTION_BACK;
                break;
            case "recents":
                globalAction = GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                globalAction = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quick_settings":
                globalAction = GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "power_dialog":
                globalAction = GLOBAL_ACTION_POWER_DIALOG;
                break;
            case "lock_screen":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    throw new GestureParseException("lock_screen requires Android 9 (API 28) or newer");
                }
                globalAction = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            case "take_screenshot":
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    throw new GestureParseException("take_screenshot requires Android 9 (API 28) or newer");
                }
                globalAction = GLOBAL_ACTION_TAKE_SCREENSHOT;
                break;
            default:
                throw new GestureParseException("Unsupported key action: " + action);
        }
        return performGlobalAction(globalAction);
    }

    public boolean launchPackage(String pkg) throws GestureParseException {
        if (pkg == null || pkg.isEmpty()) {
            throw new GestureParseException("package must not be empty");
        }
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            throw new GestureParseException("No launch intent for package: " + pkg);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        return true;
    }

    public DisplayInfo getDisplayInfo() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return new DisplayInfo(metrics.widthPixels, metrics.heightPixels, metrics.density);
    }

    public ForegroundInfo getForeground() {
        return foreground;
    }

    private GestureResult dispatchInternal(GestureDescription description) throws InterruptedException {
        GestureResultCallbackBridge callback = new GestureResultCallbackBridge();
        mainHandler.post(() -> {
            boolean accepted = dispatchGesture(description, callback, mainHandler);
            if (!accepted) {
                callback.cancel();
            }
        });
        return callback.await();
    }

    private GestureDescription buildGestureDescription(TouchGesture gesture) {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        for (TouchGesture.ContactStroke stroke : gesture.getStrokes()) {
            Path path = new Path();
            TouchGesture.Point firstPoint = stroke.getPoints().get(0);
            path.moveTo(firstPoint.getX(), firstPoint.getY());
            for (int index = 1; index < stroke.getPoints().size(); index++) {
                TouchGesture.Point point = stroke.getPoints().get(index);
                path.lineTo(point.getX(), point.getY());
            }
            builder.addStroke(new GestureDescription.StrokeDescription(
                    path,
                    stroke.getStartTimeMs(),
                    Math.max(1L, stroke.getDurationMs())));
        }
        return builder.build();
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public static final class DisplayInfo {
        public final int width;
        public final int height;
        public final float density;

        DisplayInfo(int width, int height, float density) {
            this.width = width;
            this.height = height;
            this.density = density;
        }
    }

    public static final class ForegroundInfo {
        public final String packageName;
        public final String className;

        ForegroundInfo(String packageName, String className) {
            this.packageName = packageName;
            this.className = className;
        }
    }

    private static final class GestureResultCallbackBridge extends GestureResultCallback {
        private boolean complete;
        private boolean success;

        @Override
        public synchronized void onCompleted(GestureDescription gestureDescription) {
            complete = true;
            success = true;
            notifyAll();
        }

        @Override
        public synchronized void onCancelled(GestureDescription gestureDescription) {
            cancel();
        }

        synchronized void cancel() {
            complete = true;
            success = false;
            notifyAll();
        }

        synchronized GestureResult await() throws InterruptedException {
            while (!complete) {
                wait();
            }
            return success ? GestureResult.success() : GestureResult.cancelled();
        }
    }
}
