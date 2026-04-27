package io.github.androidtouch.accessibility;

public final class GestureResult {
    private final boolean success;
    private final String message;

    private GestureResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static GestureResult success() {
        return new GestureResult(true, "completed");
    }

    public static GestureResult cancelled() {
        return new GestureResult(false, "cancelled");
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
