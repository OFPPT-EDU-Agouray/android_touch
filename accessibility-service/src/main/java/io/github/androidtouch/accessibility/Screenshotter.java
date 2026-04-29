package io.github.androidtouch.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 3: capture a Bitmap of the current screen via
 * {@link AccessibilityService#takeScreenshot(int, java.util.concurrent.Executor,
 * AccessibilityService.TakeScreenshotCallback)} (API 30+), apply optional scale/format/quality,
 * and optionally render a debug overlay of the UI tree on top.
 *
 * Endpoint: GET /v1/screenshot
 *
 * Query params:
 *   format     png|jpeg     default png
 *   quality    int 1..100   jpeg quality, default 90 (ignored for png)
 *   scale      float 0..1   downsample factor, default 1.0
 *   annotated  bool         if true, draws a colored rectangle + view_id label for each
 *                           clickable / focusable / has-text node on top of the screenshot.
 *                           default false.
 *   max_age_ms int          if a previous capture is younger than this, reuse it (best effort).
 *                           default 0 (always recapture).
 */
public final class Screenshotter {

    public static final String FORMAT_PNG  = "png";
    public static final String FORMAT_JPEG = "jpeg";

    private static final long  CAPTURE_TIMEOUT_MS = 5_000L;
    private static final int   DEFAULT_QUALITY    = 90;
    private static final float MIN_SCALE          = 0.05f;
    private static final float MAX_SCALE          = 1.0f;

    private final AccessibilityService service;

    public Screenshotter(AccessibilityService service) {
        this.service = service;
    }

    public static final class Encoded {
        public final byte[] bytes;
        public final String contentType;
        public final int width;
        public final int height;

        public Encoded(byte[] bytes, String contentType, int width, int height) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.width = width;
            this.height = height;
        }
    }

    public static final class CaptureException extends Exception {
        public final String code;

        public CaptureException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * Capture, optionally annotate, encode, and return the screenshot bytes.
     */
    public Encoded capture(String format, int quality, float scale, boolean annotated)
            throws CaptureException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw new CaptureException("not_supported",
                    "AccessibilityService.takeScreenshot requires Android 11 (API 30)");
        }

        Bitmap bitmap = takeScreenshotBlocking();
        try {
            if (annotated) {
                bitmap = drawOverlay(bitmap);
            }
            float clampedScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
            if (clampedScale < 1.0f) {
                int newW = Math.max(1, Math.round(bitmap.getWidth() * clampedScale));
                int newH = Math.max(1, Math.round(bitmap.getHeight() * clampedScale));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            Bitmap.CompressFormat cf;
            String contentType;
            if (FORMAT_JPEG.equalsIgnoreCase(format)) {
                cf = Bitmap.CompressFormat.JPEG;
                contentType = "image/jpeg";
            } else {
                cf = Bitmap.CompressFormat.PNG;
                contentType = "image/png";
            }
            int q = clampQuality(quality);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            if (!bitmap.compress(cf, cf == Bitmap.CompressFormat.PNG ? 100 : q, baos)) {
                throw new CaptureException("encode_failed", "Bitmap.compress returned false");
            }
            return new Encoded(baos.toByteArray(), contentType, bitmap.getWidth(), bitmap.getHeight());
        } finally {
            try {
                bitmap.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- capture --------------------------------------------------------

    private Bitmap takeScreenshotBlocking() throws CaptureException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> result = new AtomicReference<>();
        final AtomicReference<String> errorCode = new AtomicReference<>();
        final AtomicReference<String> errorMessage = new AtomicReference<>();

        Executor executor = Runnable::run;

        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            HardwareBuffer hwBuffer = null;
                            Bitmap hardwareBitmap = null;
                            try {
                                hwBuffer = screenshot.getHardwareBuffer();
                                hardwareBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.getColorSpace());
                                if (hardwareBitmap == null) {
                                    errorCode.set("wrap_failed");
                                    errorMessage.set("Bitmap.wrapHardwareBuffer returned null");
                                    return;
                                }
                                Bitmap software = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true);
                                if (software == null) {
                                    errorCode.set("copy_failed");
                                    errorMessage.set("Bitmap.copy(ARGB_8888) returned null");
                                    return;
                                }
                                result.set(software);
                            } catch (Throwable throwable) {
                                errorCode.set("capture_threw");
                                errorMessage.set(String.valueOf(throwable.getMessage()));
                            } finally {
                                if (hardwareBitmap != null) {
                                    try {
                                        hardwareBitmap.recycle();
                                    } catch (Throwable ignored) {
                                    }
                                }
                                if (hwBuffer != null) {
                                    try {
                                        hwBuffer.close();
                                    } catch (Throwable ignored) {
                                    }
                                }
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int code) {
                            errorCode.set("capture_failed");
                            errorMessage.set("takeScreenshot onFailure code=" + code);
                            latch.countDown();
                        }
                    });
        } catch (Throwable throwable) {
            throw new CaptureException("capture_threw",
                    "takeScreenshot threw: " + throwable.getMessage());
        }

        try {
            if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new CaptureException("capture_timeout",
                        "takeScreenshot did not return within " + CAPTURE_TIMEOUT_MS + "ms");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CaptureException("interrupted", "screenshot wait interrupted");
        }

        Bitmap bitmap = result.get();
        if (bitmap == null) {
            String code = errorCode.get();
            String msg = errorMessage.get();
            throw new CaptureException(code != null ? code : "capture_unknown",
                    msg != null ? msg : "screenshot capture failed");
        }
        return bitmap;
    }

    // ---- annotation overlay --------------------------------------------

    private Bitmap drawOverlay(Bitmap base) {
        // Convert to mutable if needed
        Bitmap mutable = base.isMutable() ? base : base.copy(Bitmap.Config.ARGB_8888, true);
        if (mutable == null) {
            return base;
        }
        if (mutable != base) {
            try {
                base.recycle();
            } catch (Throwable ignored) {
            }
        }

        Canvas canvas = new Canvas(mutable);

        Paint clickablePaint = strokePaint(Color.argb(220, 0, 200, 90));   // green
        Paint focusablePaint = strokePaint(Color.argb(180, 70, 130, 255)); // blue
        Paint textPaint      = strokePaint(Color.argb(160, 255, 165, 0));  // amber

        Paint labelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelBg.setStyle(Paint.Style.FILL);
        labelBg.setColor(Color.argb(160, 0, 0, 0));

        Paint labelFg = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelFg.setColor(Color.WHITE);
        labelFg.setTypeface(Typeface.DEFAULT_BOLD);
        labelFg.setTextSize(Math.max(20f, mutable.getWidth() / 60f));

        AtomicInteger nodeCounter = new AtomicInteger();
        for (AccessibilityNodeInfo root : collectRoots()) {
            try {
                drawTree(root, canvas, clickablePaint, focusablePaint, textPaint,
                        labelBg, labelFg, nodeCounter, 0);
            } finally {
                try {
                    root.recycle();
                } catch (Throwable ignored) {
                }
            }
        }

        // Cap output to avoid annotating a 5000-node tree (defensive).
        return mutable;
    }

    private static Paint strokePaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3f);
        p.setColor(color);
        return p;
    }

    private static final int OVERLAY_NODE_LIMIT = 400;

    private void drawTree(AccessibilityNodeInfo node, Canvas canvas,
                          Paint clickablePaint, Paint focusablePaint, Paint textPaint,
                          Paint labelBg, Paint labelFg,
                          AtomicInteger counter, int depth) {
        if (node == null) {
            return;
        }
        if (counter.get() > OVERLAY_NODE_LIMIT) {
            return;
        }
        if (!node.isVisibleToUser()) {
            // Still descend — invisible parents can have visible kids.
            drawChildren(node, canvas, clickablePaint, focusablePaint, textPaint,
                    labelBg, labelFg, counter, depth);
            return;
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            drawChildren(node, canvas, clickablePaint, focusablePaint, textPaint,
                    labelBg, labelFg, counter, depth);
            return;
        }

        Paint paint = null;
        if (node.isClickable()) {
            paint = clickablePaint;
        } else if (node.isFocusable()) {
            paint = focusablePaint;
        } else {
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) {
                paint = textPaint;
            }
        }
        if (paint != null) {
            canvas.drawRect(bounds, paint);
            String label = makeLabel(node);
            if (label != null) {
                drawLabel(canvas, label, bounds, labelBg, labelFg);
            }
            counter.incrementAndGet();
        }

        drawChildren(node, canvas, clickablePaint, focusablePaint, textPaint,
                labelBg, labelFg, counter, depth);
    }

    private void drawChildren(AccessibilityNodeInfo node, Canvas canvas,
                              Paint clickablePaint, Paint focusablePaint, Paint textPaint,
                              Paint labelBg, Paint labelFg,
                              AtomicInteger counter, int depth) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && counter.get() <= OVERLAY_NODE_LIMIT; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                drawTree(child, canvas, clickablePaint, focusablePaint, textPaint,
                        labelBg, labelFg, counter, depth + 1);
            } finally {
                if (child != null) {
                    try {
                        child.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private static String makeLabel(AccessibilityNodeInfo node) {
        CharSequence id = node.getViewIdResourceName();
        if (id != null && id.length() > 0) {
            String s = id.toString();
            int colon = s.indexOf(":id/");
            return colon >= 0 ? s.substring(colon + 4) : s;
        }
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            return truncate(desc.toString(), 40);
        }
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            return truncate(text.toString(), 40);
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    private static void drawLabel(Canvas canvas, String label, Rect bounds, Paint bg, Paint fg) {
        Rect textBounds = new Rect();
        fg.getTextBounds(label, 0, label.length(), textBounds);
        int pad = 4;
        int x = bounds.left;
        int y = bounds.top - pad;
        if (y - textBounds.height() < 0) {
            // Doesn't fit above — draw inside.
            y = bounds.top + textBounds.height() + pad;
        }
        Rect bgRect = new Rect(x - pad, y - textBounds.height() - pad,
                x + textBounds.width() + pad, y + pad);
        canvas.drawRect(bgRect, bg);
        canvas.drawText(label, x, y, fg);
    }

    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                List<AccessibilityWindowInfo> ws = service.getWindows();
                if (ws != null) {
                    for (AccessibilityWindowInfo w : ws) {
                        if (w == null) {
                            continue;
                        }
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r != null) {
                            out.add(r);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (out.isEmpty()) {
            AccessibilityNodeInfo r = service.getRootInActiveWindow();
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static int clampQuality(int q) {
        if (q <= 0) {
            return DEFAULT_QUALITY;
        }
        if (q < 1) {
            return 1;
        }
        if (q > 100) {
            return 100;
        }
        return q;
    }

    /** Reserved for future "use cached screenshot if newer than X" optimization. */
    @SuppressWarnings("unused")
    private static long now() {
        return SystemClock.uptimeMillis();
    }
}
