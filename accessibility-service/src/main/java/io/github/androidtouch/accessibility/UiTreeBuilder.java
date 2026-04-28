package io.github.androidtouch.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Walks AccessibilityNodeInfo trees and produces JSON.
 *
 * Phase 2 endpoint: GET /v1/ui_tree
 *
 * Query params:
 *   max_depth      int     default 32
 *   visible_only   bool    default true
 *   include_off    bool    if true, include offscreen nodes (visible_only must be false)
 *   package        string  optional package filter (e.g. com.android.settings)
 */
public final class UiTreeBuilder {
    public static final int DEFAULT_MAX_DEPTH = 32;

    private final int maxDepth;
    private final boolean visibleOnly;
    private final String packageFilter;
    private final UiNodeMatcher matcher;

    public UiTreeBuilder(int maxDepth, boolean visibleOnly, String packageFilter) {
        this(maxDepth, visibleOnly, packageFilter, null);
    }

    public UiTreeBuilder(int maxDepth, boolean visibleOnly, String packageFilter, UiNodeMatcher matcher) {
        this.maxDepth = maxDepth <= 0 ? DEFAULT_MAX_DEPTH : maxDepth;
        this.visibleOnly = visibleOnly;
        this.packageFilter = TextUtils.isEmpty(packageFilter) ? null : packageFilter;
        this.matcher = matcher;
    }

    /** Build a JSON object: { "windows": [ ... ], "node_count": N } */
    public JSONObject build(AccessibilityService service) throws JSONException {
        JSONObject root = new JSONObject();
        JSONArray windows = new JSONArray();
        Counter counter = new Counter();

        List<AccessibilityWindowInfo> windowList = null;
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                windowList = service.getWindows();
            } catch (Throwable ignored) {
                windowList = null;
            }
        }

        if (windowList != null && !windowList.isEmpty()) {
            for (AccessibilityWindowInfo window : windowList) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo windowRoot = window.getRoot();
                if (windowRoot == null) {
                    continue;
                }
                JSONObject windowJson = new JSONObject();
                windowJson.put("id", window.getId());
                windowJson.put("type", windowTypeName(window.getType()));
                windowJson.put("active", window.isActive());
                windowJson.put("focused", window.isFocused());
                Rect wb = new Rect();
                window.getBoundsInScreen(wb);
                windowJson.put("bounds", boundsToJson(wb));

                JSONObject nodeJson = serializeNode(windowRoot, 0, counter);
                if (nodeJson != null) {
                    windowJson.put("root", nodeJson);
                    windows.put(windowJson);
                }
                try {
                    windowRoot.recycle();
                } catch (Throwable ignored) {
                }
            }
        } else {
            AccessibilityNodeInfo activeRoot = service != null ? service.getRootInActiveWindow() : null;
            if (activeRoot != null) {
                JSONObject windowJson = new JSONObject();
                windowJson.put("id", -1);
                windowJson.put("type", "active");
                windowJson.put("active", true);
                windowJson.put("focused", true);
                JSONObject nodeJson = serializeNode(activeRoot, 0, counter);
                if (nodeJson != null) {
                    windowJson.put("root", nodeJson);
                    windows.put(windowJson);
                }
                try {
                    activeRoot.recycle();
                } catch (Throwable ignored) {
                }
            }
        }

        root.put("windows", windows);
        root.put("node_count", counter.count);
        root.put("truncated", counter.truncated);
        return root;
    }

    /**
     * Find all nodes matching the matcher passed to this builder.
     * Returns the list of node JSON objects (no tree wrapping).
     */
    public JSONArray findAll(AccessibilityService service) throws JSONException {
        if (matcher == null) {
            throw new IllegalStateException("matcher is null");
        }
        JSONArray results = new JSONArray();
        Counter counter = new Counter();

        List<AccessibilityWindowInfo> windowList = null;
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                windowList = service.getWindows();
            } catch (Throwable ignored) {
                windowList = null;
            }
        }
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        if (windowList != null && !windowList.isEmpty()) {
            for (AccessibilityWindowInfo window : windowList) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo r = window.getRoot();
                if (r != null) {
                    roots.add(r);
                }
            }
        } else {
            AccessibilityNodeInfo r = service != null ? service.getRootInActiveWindow() : null;
            if (r != null) {
                roots.add(r);
            }
        }

        for (AccessibilityNodeInfo r : roots) {
            collectMatches(r, 0, counter, results);
            try {
                r.recycle();
            } catch (Throwable ignored) {
            }
        }
        return results;
    }

    private void collectMatches(AccessibilityNodeInfo node, int depth, Counter counter, JSONArray out)
            throws JSONException {
        if (node == null || depth > maxDepth) {
            return;
        }
        counter.count++;
        if (counter.count > MAX_NODES) {
            counter.truncated = true;
            return;
        }
        if (passesFilters(node) && matcher.matches(node)) {
            out.put(nodeFields(node, depth));
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                collectMatches(child, depth + 1, counter, out);
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

    private static final int MAX_NODES = 5000;

    private JSONObject serializeNode(AccessibilityNodeInfo node, int depth, Counter counter) throws JSONException {
        if (node == null) {
            return null;
        }
        if (counter.count >= MAX_NODES) {
            counter.truncated = true;
            return null;
        }
        if (depth > maxDepth) {
            return null;
        }
        if (!passesFilters(node)) {
            return null;
        }

        counter.count++;
        JSONObject json = nodeFields(node, depth);

        int childCount = node.getChildCount();
        if (childCount > 0 && depth + 1 <= maxDepth) {
            JSONArray children = new JSONArray();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = null;
                try {
                    child = node.getChild(i);
                    JSONObject childJson = serializeNode(child, depth + 1, counter);
                    if (childJson != null) {
                        children.put(childJson);
                    }
                } finally {
                    if (child != null) {
                        try {
                            child.recycle();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            if (children.length() > 0) {
                json.put("children", children);
            }
        }
        return json;
    }

    private boolean passesFilters(AccessibilityNodeInfo node) {
        if (visibleOnly && !node.isVisibleToUser()) {
            return false;
        }
        if (packageFilter != null) {
            CharSequence pkg = node.getPackageName();
            if (pkg == null || !packageFilter.equals(pkg.toString())) {
                return false;
            }
        }
        return true;
    }

    private JSONObject nodeFields(AccessibilityNodeInfo node, int depth) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("depth", depth);
        putIfNotNull(json, "text", node.getText());
        putIfNotNull(json, "content_desc", node.getContentDescription());
        putIfNotNull(json, "view_id", node.getViewIdResourceName());
        putIfNotNull(json, "class", node.getClassName());
        putIfNotNull(json, "package", node.getPackageName());

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        json.put("bounds", boundsToJson(bounds));

        json.put("clickable", node.isClickable());
        json.put("long_clickable", node.isLongClickable());
        json.put("focusable", node.isFocusable());
        json.put("focused", node.isFocused());
        json.put("scrollable", node.isScrollable());
        json.put("checkable", node.isCheckable());
        json.put("checked", node.isChecked());
        json.put("enabled", node.isEnabled());
        json.put("selected", node.isSelected());
        json.put("password", node.isPassword());
        json.put("visible", node.isVisibleToUser());
        json.put("editable", node.isEditable());
        return json;
    }

    private static JSONObject boundsToJson(Rect rect) throws JSONException {
        JSONObject b = new JSONObject();
        b.put("left", rect.left);
        b.put("top", rect.top);
        b.put("right", rect.right);
        b.put("bottom", rect.bottom);
        b.put("center_x", rect.centerX());
        b.put("center_y", rect.centerY());
        b.put("width", rect.width());
        b.put("height", rect.height());
        return b;
    }

    private static void putIfNotNull(JSONObject json, String key, CharSequence value) throws JSONException {
        if (value != null) {
            json.put(key, value.toString());
        }
    }

    private static String windowTypeName(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION:
                return "application";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD:
                return "input_method";
            case AccessibilityWindowInfo.TYPE_SYSTEM:
                return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY:
                return "accessibility_overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER:
                return "split_screen_divider";
            default:
                return "unknown";
        }
    }

    private static final class Counter {
        int count;
        boolean truncated;
    }
}
