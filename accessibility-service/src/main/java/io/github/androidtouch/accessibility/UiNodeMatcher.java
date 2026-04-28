package io.github.androidtouch.accessibility;

import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.json.JSONObject;

/**
 * Represents a node-matching criterion supplied by the agent.
 *
 * Body shape:
 *   { "by": "text" | "viewId" | "contentDesc" | "class",
 *     "value": "...",
 *     "regex": false   // optional, default false
 *   }
 */
public final class UiNodeMatcher {
    public enum By {
        TEXT,
        VIEW_ID,
        CONTENT_DESC,
        CLASS_NAME
    }

    private final By by;
    private final String value;
    private final boolean regex;
    private final Pattern pattern;

    private UiNodeMatcher(By by, String value, boolean regex, Pattern pattern) {
        this.by = by;
        this.value = value;
        this.regex = regex;
        this.pattern = pattern;
    }

    public By getBy() {
        return by;
    }

    public String getValue() {
        return value;
    }

    public boolean isRegex() {
        return regex;
    }

    public boolean matches(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        CharSequence target;
        switch (by) {
            case TEXT:
                target = node.getText();
                break;
            case VIEW_ID:
                target = node.getViewIdResourceName();
                break;
            case CONTENT_DESC:
                target = node.getContentDescription();
                break;
            case CLASS_NAME:
                target = node.getClassName();
                break;
            default:
                return false;
        }
        if (target == null) {
            return false;
        }
        String haystack = target.toString();
        if (regex) {
            return pattern.matcher(haystack).find();
        }
        return haystack.equals(value);
    }

    public static UiNodeMatcher fromJson(JSONObject body) throws GestureParseException {
        if (body == null) {
            throw new GestureParseException("matcher body is required");
        }
        String byRaw = body.optString("by", "").trim();
        String value = body.optString("value", null);
        boolean regex = body.optBoolean("regex", false);

        if (TextUtils.isEmpty(byRaw)) {
            throw new GestureParseException("'by' is required (text|viewId|contentDesc|class)");
        }
        if (value == null || value.isEmpty()) {
            throw new GestureParseException("'value' is required");
        }
        By by;
        switch (byRaw) {
            case "text":
                by = By.TEXT;
                break;
            case "viewId":
            case "view_id":
            case "resourceId":
                by = By.VIEW_ID;
                break;
            case "contentDesc":
            case "content_desc":
            case "desc":
                by = By.CONTENT_DESC;
                break;
            case "class":
            case "className":
            case "class_name":
                by = By.CLASS_NAME;
                break;
            default:
                throw new GestureParseException("unsupported 'by': " + byRaw);
        }
        Pattern pattern = null;
        if (regex) {
            try {
                pattern = Pattern.compile(value);
            } catch (PatternSyntaxException exception) {
                throw new GestureParseException("invalid regex: " + exception.getMessage());
            }
        }
        return new UiNodeMatcher(by, value, regex, pattern);
    }

    @Override
    public String toString() {
        return "UiNodeMatcher{by=" + by + ", value='" + value + "', regex=" + regex + "}";
    }
}
