package io.github.androidtouch.accessibility;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONException;
import org.json.JSONObject;

public final class GestureHttpServer {
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final int port;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ServerSocket serverSocket;
    private volatile boolean running;

    public GestureHttpServer(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        running = true;
        executor.execute(this::serve);
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        executor.shutdownNow();
    }

    private void serve() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                handle(socket);
            } catch (IOException ignored) {
                if (running) {
                    continue;
                }
                return;
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket currentSocket = socket) {
            currentSocket.setSoTimeout(60_000);
            OutputStream out = currentSocket.getOutputStream();
            try {
                HttpRequest request = readRequest(currentSocket.getInputStream());
                route(request, out);
            } catch (GestureParseException exception) {
                writeJsonError(out, 400, exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                writeJsonError(out, 500, "request interrupted");
            } catch (IOException exception) {
                writeJsonError(out, 500, exception.getMessage());
            } catch (RuntimeException exception) {
                writeJsonError(out, 500, exception.getMessage());
            }
        } catch (IOException exception) {
            // socket already closed
        }
    }

    // ------------- routing ------------------------------------------------

    private void route(HttpRequest request, OutputStream out)
            throws IOException, GestureParseException, InterruptedException {

        // Health
        if ("GET".equals(request.method) && ("/health".equals(request.path) || "/v1/health".equals(request.path))) {
            writeResponse(out, 200, "{\"status\":\"ok\",\"backend\":\"accessibility\",\"phase\":2}");
            return;
        }

        // Phase 1: gesture dispatch
        if ("POST".equals(request.method) && ("/".equals(request.path) || "/v1/touch".equals(request.path))) {
            handleTouch(request, out);
            return;
        }

        // Phase 2: perception
        if ("GET".equals(request.method) && "/v1/ui_tree".equals(request.path)) {
            handleUiTree(request, out);
            return;
        }
        if ("GET".equals(request.method) && "/v1/focused".equals(request.path)) {
            handleFocused(out);
            return;
        }
        if ("POST".equals(request.method) && "/v1/find".equals(request.path)) {
            handleFind(request, out);
            return;
        }
        if ("POST".equals(request.method) && "/v1/click_node".equals(request.path)) {
            handleClickNode(request, out, /*longClick=*/false);
            return;
        }
        if ("POST".equals(request.method) && "/v1/long_click_node".equals(request.path)) {
            handleClickNode(request, out, /*longClick=*/true);
            return;
        }
        if ("POST".equals(request.method) && "/v1/scroll_to".equals(request.path)) {
            handleScrollTo(request, out);
            return;
        }

        // Method-not-allowed for known paths, otherwise 404
        if (isKnownPath(request.path)) {
            writeResponse(out, 405, "{\"error\":\"method_not_allowed\"}");
        } else {
            writeResponse(out, 404, "{\"error\":\"not_found\"}");
        }
    }

    private static boolean isKnownPath(String p) {
        return "/".equals(p)
                || "/v1/touch".equals(p)
                || "/health".equals(p)
                || "/v1/health".equals(p)
                || "/v1/ui_tree".equals(p)
                || "/v1/find".equals(p)
                || "/v1/click_node".equals(p)
                || "/v1/long_click_node".equals(p)
                || "/v1/scroll_to".equals(p)
                || "/v1/focused".equals(p);
    }

    // ------------- handlers -----------------------------------------------

    private void handleTouch(HttpRequest request, OutputStream out)
            throws IOException, GestureParseException, InterruptedException {
        AndroidTouchAccessibilityService service = AndroidTouchAccessibilityService.getInstance();
        if (service == null) {
            writeResponse(out, 503, "{\"error\":\"accessibility_service_not_connected\"}");
            return;
        }
        TouchGesture gesture = TouchGesture.fromJson(request.body);
        GestureResult result = service.dispatch(gesture);
        if (result.isSuccess()) {
            writeResponse(out, 200, "{\"status\":\"completed\"}");
        } else {
            writeResponse(out, 409, "{\"error\":\"" + jsonEscape(result.getMessage()) + "\"}");
        }
    }

    private void handleUiTree(HttpRequest request, OutputStream out) throws IOException {
        AndroidTouchAccessibilityService service = AndroidTouchAccessibilityService.getInstance();
        if (service == null) {
            writeResponse(out, 503, "{\"error\":\"accessibility_service_not_connected\"}");
            return;
        }
        int maxDepth = parseIntQuery(request.query, "max_depth", UiTreeBuilder.DEFAULT_MAX_DEPTH);
        boolean visibleOnly = parseBoolQuery(request.query, "visible_only", true);
        String pkg = request.query.get("package");

        try {
            UiTreeBuilder builder = new UiTreeBuilder(maxDepth, visibleOnly, pkg);
            JSONObject tree = builder.build(service);
            writeResponse(out, 200, tree.toString());
        } catch (JSONException exception) {
            writeJsonError(out, 500, "ui_tree_serialize_failed: " + exception.getMessage());
        } catch (RuntimeException exception) {
            writeJsonError(out, 500, "ui_tree_failed: " + exception.getMessage());
        }
    }

    private void handleFocused(OutputStream out) throws IOException {
        AndroidTouchAccessibilityService service = AndroidTouchAccessibilityService.getInstance();
        if (service == null) {
            writeResponse(out, 503, "{\"error\":\"accessibility_service_not_connected\"}");
            return;
        }
        try {
            UiActions actions = new UiActions(service);
            JSONObject result = actions.focused();
            writeResponse(out, 200, result.toString());
        } catch (JSONException exception) {
            writeJsonError(out, 500, "focused_failed: " + exception.getMessage());
        }
    }

    private void handleFind(HttpRequest request, OutputStream out) throws IOException, GestureParseException {
        AndroidTouchAccessibilityService service = AndroidTouchAccessibilityService.getInstance();
        if (service == null) {
            writeResponse(out, 503, "{\"error\":\"accessibility_service_not_connected\"}");
            return;
        }
        try {
            JSONObject body = parseJsonObject(request.body);
            UiNodeMatcher matcher = UiNodeMatcher.fromJson(body);
            long timeoutMs = body.optLong("timeout_ms", 0L);
            int maxDepth = body.optInt("max_depth", UiTreeBuilder.DEFAULT_MAX_DEPTH);
            boolean visibleOnly = body.optBoolean("visible_only", true);

            UiActions actions = new UiActions(service);
            JSONObject response = new JSONObject();
            response.put("matches", actions.find(matcher, timeoutMs, maxDepth, visibleOnly));
            response.put("count", response.getJSONArray("matches").length());
            writeResponse(out, 200, response.toString());
        } catch (JSONException exception) {
            throw new GestureParseException("invalid json: " + exception.getMessage());
        }
    }

    private void handleClickNode(HttpRequest request, OutputStream out, boolean longClick)
            throws IOException, GestureParseException {
        AndroidTouchAccessibilityService service = AndroidTouchAccessibilityService.getInstance();
        if (service == null) {
            writeResponse(out, 503, "{\"error\":\"accessibility_service_not_connected\"}");
            return;
        }
        try {
            JSONObject body = parseJsonObject(request.body);
            UiNodeMatcher matcher = UiNodeMatcher.fromJson(body);
            long timeoutMs = body.optLong("timeout_ms", 0L);
            boolean visibleOnly = body.optBoolean("visible_only", true);

            UiActions actions = new UiActions(service);
            JSONObject result = longClick
                    ? actions.longClickNode(matcher, timeoutMs, visibleOnly)
                    : actions.clickNode(matcher, timeoutMs, visibleOnly);

            int status = "completed".equals(result.optString("status")) ? 200
                    : "failed".equals(result.optString("status")) ? 409 : 404;
            writeResponse(out, status, result.toString());
        } catch (JSONException exception) {
            throw new GestureParseException("invalid json: " + exception.getMessage());
        }
    }

    private void handleScrollTo(HttpRequest request, OutputStream out) throws IOException, GestureParseException {
        AndroidTouchAccessibilityService service = AndroidTouchAccessibilityService.getInstance();
        if (service == null) {
            writeResponse(out, 503, "{\"error\":\"accessibility_service_not_connected\"}");
            return;
        }
        try {
            JSONObject body = parseJsonObject(request.body);
            UiNodeMatcher matcher = UiNodeMatcher.fromJson(body);
            long timeoutMs = body.optLong("timeout_ms", 0L);
            int maxScrolls = body.optInt("max_scrolls", 20);
            boolean visibleOnly = body.optBoolean("visible_only", true);

            UiActions actions = new UiActions(service);
            JSONObject result = actions.scrollTo(matcher, timeoutMs, maxScrolls, visibleOnly);
            int status = "error".equals(result.optString("status")) ? 404 : 200;
            writeResponse(out, status, result.toString());
        } catch (JSONException exception) {
            throw new GestureParseException("invalid json: " + exception.getMessage());
        }
    }

    // ------------- request parsing ----------------------------------------

    private static JSONObject parseJsonObject(String body) throws GestureParseException {
        if (body == null || body.isEmpty()) {
            throw new GestureParseException("request body required");
        }
        try {
            return new JSONObject(body);
        } catch (JSONException exception) {
            throw new GestureParseException("invalid json body: " + exception.getMessage());
        }
    }

    private static int parseIntQuery(Map<String, String> query, String key, int defaultValue) {
        String value = query.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static boolean parseBoolQuery(Map<String, String> query, String key, boolean defaultValue) {
        String value = query.get(key);
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private HttpRequest readRequest(InputStream inputStream) throws IOException {
        BufferedInputStream reader = new BufferedInputStream(inputStream);
        String requestLine = readLine(reader);
        if (requestLine == null || requestLine.isEmpty()) {
            throw new IOException("empty request");
        }

        String[] requestParts = requestLine.split(" ");
        if (requestParts.length < 2) {
            throw new IOException("invalid request line");
        }

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = readLine(reader)) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(line.substring(0, separator).trim().toLowerCase(Locale.US), line.substring(separator + 1).trim());
            }
        }

        int contentLength = parseContentLength(headers.get("content-length"));
        if (contentLength > MAX_BODY_BYTES) {
            throw new IOException("request body too large");
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream(Math.max(0, contentLength));
        int offset = 0;
        while (offset < contentLength) {
            int read = reader.read();
            if (read < 0) {
                throw new IOException("unexpected end of request body");
            }
            body.write(read);
            offset++;
        }

        // Split path and query string
        String rawTarget = requestParts[1];
        String path;
        Map<String, String> query = new HashMap<>();
        int qIndex = rawTarget.indexOf('?');
        if (qIndex >= 0) {
            path = rawTarget.substring(0, qIndex);
            parseQueryString(rawTarget.substring(qIndex + 1), query);
        } else {
            path = rawTarget;
        }

        return new HttpRequest(requestParts[0], path, query, body.toString(StandardCharsets.UTF_8.name()));
    }

    private static void parseQueryString(String qs, Map<String, String> out) {
        if (qs == null || qs.isEmpty()) {
            return;
        }
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k;
            String v;
            try {
                if (eq < 0) {
                    k = URLDecoder.decode(pair, "UTF-8");
                    v = "";
                } else {
                    k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
                out.put(k, v);
            } catch (Exception ignored) {
            }
        }
    }

    private String readLine(InputStream inputStream) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean sawCarriageReturn = false;
        while (true) {
            int value = inputStream.read();
            if (value < 0) {
                if (line.size() == 0 && !sawCarriageReturn) {
                    return null;
                }
                break;
            }
            if (sawCarriageReturn) {
                if (value == '\n') {
                    break;
                }
                line.write('\r');
                sawCarriageReturn = false;
            }
            if (value == '\r') {
                sawCarriageReturn = true;
            } else if (value == '\n') {
                break;
            } else {
                line.write(value);
            }
        }
        return line.toString(StandardCharsets.UTF_8.name());
    }

    private int parseContentLength(String value) throws IOException {
        if (value == null) {
            return 0;
        }
        try {
            int length = Integer.parseInt(value);
            if (length < 0) {
                throw new IOException("invalid content length");
            }
            return length;
        } catch (NumberFormatException exception) {
            throw new IOException("invalid content length", exception);
        }
    }

    // ------------- response writing ---------------------------------------

    private void writeJsonError(OutputStream out, int statusCode, String message) {
        try {
            writeResponse(out, statusCode, "{\"error\":\"" + jsonEscape(message) + "\"}");
        } catch (IOException ignored) {
        }
    }

    private void writeResponse(OutputStream outputStream, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = statusText(statusCode);
        String headers = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        outputStream.write(headers.getBytes(StandardCharsets.UTF_8));
        outputStream.write(bytes);
        outputStream.flush();
    }

    private String statusText(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            case 503:
                return "Service Unavailable";
            default:
                return "Internal Server Error";
        }
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static final class HttpRequest {
        private final String method;
        private final String path;
        private final Map<String, String> query;
        private final String body;

        private HttpRequest(String method, String path, Map<String, String> query, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.body = body;
        }
    }
}
