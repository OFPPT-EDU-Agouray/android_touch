package io.github.androidtouch.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import java.io.IOException;

public class AndroidTouchAccessibilityService extends AccessibilityService {
    private static AndroidTouchAccessibilityService instance;

    private GestureHttpServer server;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
