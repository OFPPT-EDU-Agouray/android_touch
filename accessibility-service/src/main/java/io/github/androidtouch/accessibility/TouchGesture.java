package io.github.androidtouch.accessibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class TouchGesture {
    private static final int MAX_CONTACTS = 10;
    private static final long MAX_DELAY_MS = 60_000L;
    private static final long MAX_GESTURE_DURATION_MS = 60_000L;

    private final List<ContactStroke> strokes;

    private TouchGesture(List<ContactStroke> strokes) {
        this.strokes = Collections.unmodifiableList(strokes);
    }

    public List<ContactStroke> getStrokes() {
        return strokes;
    }

    public static TouchGesture fromJson(String body) throws GestureParseException {
        try {
            JSONArray commands = new JSONArray(body);
            GestureBuilder builder = new GestureBuilder();
            for (int index = 0; index < commands.length(); index++) {
                Object item = commands.get(index);
                if (!(item instanceof JSONObject)) {
                    throw new GestureParseException("Command " + index + " must be an object");
                }
                builder.apply((JSONObject) item, index);
            }
            return builder.build();
        } catch (JSONException exception) {
            throw new GestureParseException("Invalid JSON: " + exception.getMessage(), exception);
        }
    }

    public static final class ContactStroke {
        private final long startTimeMs;
        private final long durationMs;
        private final List<Point> points;

        private ContactStroke(long startTimeMs, long durationMs, List<Point> points) {
            this.startTimeMs = startTimeMs;
            this.durationMs = durationMs;
            this.points = Collections.unmodifiableList(points);
        }

        public long getStartTimeMs() {
            return startTimeMs;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public List<Point> getPoints() {
            return points;
        }
    }

    public static final class Point {
        private final float x;
        private final float y;

        private Point(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }
    }

    private static final class GestureBuilder {
        private final Map<Integer, MutableStroke> activeStrokes = new HashMap<>();
        private final List<ContactStroke> completedStrokes = new ArrayList<>();
        private long currentTimeMs;

        void apply(JSONObject command, int index) throws JSONException, GestureParseException {
            String type = requiredString(command, "type", index);
            switch (type) {
                case "down":
                    down(command, index);
                    break;
                case "move":
                    move(command, index);
                    break;
                case "up":
                    up(command, index);
                    break;
                case "commit":
                    break;
                case "delay":
                    delay(command, index);
                    break;
                case "reset":
                    reset();
                    break;
                case "stop":
                    throw new GestureParseException("Command " + index + " is not supported by the accessibility backend");
                default:
                    throw new GestureParseException("Command " + index + " has unsupported type: " + type);
            }
        }

        TouchGesture build() throws GestureParseException {
            for (MutableStroke stroke : new ArrayList<>(activeStrokes.values())) {
                stroke.finish(currentTimeMs);
                completedStrokes.add(stroke.toImmutable());
            }
            activeStrokes.clear();
            if (completedStrokes.isEmpty()) {
                throw new GestureParseException("Gesture must contain at least one completed stroke");
            }
            if (currentTimeMs > MAX_GESTURE_DURATION_MS) {
                throw new GestureParseException("Gesture duration must not exceed " + MAX_GESTURE_DURATION_MS + "ms");
            }
            return new TouchGesture(new ArrayList<>(completedStrokes));
        }

        private void down(JSONObject command, int index) throws JSONException, GestureParseException {
            int contact = contact(command, index);
            if (activeStrokes.containsKey(contact)) {
                throw new GestureParseException("Command " + index + " starts an already active contact: " + contact);
            }
            activeStrokes.put(contact, new MutableStroke(currentTimeMs, point(command, index)));
        }

        private void move(JSONObject command, int index) throws JSONException, GestureParseException {
            int contact = contact(command, index);
            MutableStroke stroke = activeStrokes.get(contact);
            if (stroke == null) {
                throw new GestureParseException("Command " + index + " moves inactive contact: " + contact);
            }
            stroke.addPoint(currentTimeMs, point(command, index));
        }

        private void up(JSONObject command, int index) throws JSONException, GestureParseException {
            int contact = contact(command, index);
            MutableStroke stroke = activeStrokes.remove(contact);
            if (stroke == null) {
                throw new GestureParseException("Command " + index + " ends inactive contact: " + contact);
            }
            stroke.finish(currentTimeMs);
            completedStrokes.add(stroke.toImmutable());
        }

        private void delay(JSONObject command, int index) throws JSONException, GestureParseException {
            long value = requiredLong(command, "value", index);
            if (value < 0L || value > MAX_DELAY_MS) {
                throw new GestureParseException("Command " + index + " delay must be between 0 and " + MAX_DELAY_MS);
            }
            if (currentTimeMs + value > MAX_GESTURE_DURATION_MS) {
                throw new GestureParseException("Command " + index + " makes gesture exceed " + MAX_GESTURE_DURATION_MS + "ms");
            }
            currentTimeMs += value;
        }

        private void reset() {
            activeStrokes.clear();
            completedStrokes.clear();
            currentTimeMs = 0L;
        }

        private int contact(JSONObject command, int index) throws JSONException, GestureParseException {
            int contact = requiredInt(command, "contact", index);
            if (contact < 0 || contact >= MAX_CONTACTS) {
                throw new GestureParseException("Command " + index + " contact must be between 0 and " + (MAX_CONTACTS - 1));
            }
            return contact;
        }

        private Point point(JSONObject command, int index) throws JSONException, GestureParseException {
            float x = (float) requiredDouble(command, "x", index);
            float y = (float) requiredDouble(command, "y", index);
            if (x < 0 || y < 0) {
                throw new GestureParseException("Command " + index + " coordinates must be non-negative");
            }
            return new Point(x, y);
        }
    }

    private static final class MutableStroke {
        private final long startTimeMs;
        private final List<Point> points = new ArrayList<>();
        private long endTimeMs;

        private MutableStroke(long startTimeMs, Point firstPoint) {
            this.startTimeMs = startTimeMs;
            this.endTimeMs = startTimeMs;
            points.add(firstPoint);
        }

        void addPoint(long timeMs, Point point) {
            endTimeMs = Math.max(endTimeMs, timeMs);
            points.add(point);
        }

        void finish(long timeMs) {
            endTimeMs = Math.max(endTimeMs, timeMs);
        }

        ContactStroke toImmutable() {
            return new ContactStroke(startTimeMs, Math.max(1L, endTimeMs - startTimeMs), new ArrayList<>(points));
        }
    }

    private static String requiredString(JSONObject command, String key, int index) throws JSONException, GestureParseException {
        if (!command.has(key)) {
            throw new GestureParseException("Command " + index + " missing field: " + key);
        }
        return command.getString(key);
    }

    private static int requiredInt(JSONObject command, String key, int index) throws JSONException, GestureParseException {
        if (!command.has(key)) {
            throw new GestureParseException("Command " + index + " missing field: " + key);
        }
        return command.getInt(key);
    }

    private static long requiredLong(JSONObject command, String key, int index) throws JSONException, GestureParseException {
        if (!command.has(key)) {
            throw new GestureParseException("Command " + index + " missing field: " + key);
        }
        return command.getLong(key);
    }

    private static double requiredDouble(JSONObject command, String key, int index) throws JSONException, GestureParseException {
        if (!command.has(key)) {
            throw new GestureParseException("Command " + index + " missing field: " + key);
        }
        return command.getDouble(key);
    }
}
