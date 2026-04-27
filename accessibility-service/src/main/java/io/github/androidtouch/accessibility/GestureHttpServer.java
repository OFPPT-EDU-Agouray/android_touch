package io.github.androidtouch.accessibility;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

public final class GestureHttpServer {
    private static final String TAG = "GestureHttpServer";
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
            } catch (RuntimeException exception) {
                Log.e(TAG, "unexpected error while serving request", exception);
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket currentSocket = socket) {
            currentSocket.setSoTimeout(30_000);
            try {
                HttpRequest request = readRequest(currentSocket.getInputStream());
                if ("GET".equals(request.method) && ("/health".equals(request.path) || "/v1/health".equals(request.path))) {
                    writeResponse(currentSocket.getOutputStream(), 200, "{\"status\":\"ok\",\"backend\":\"accessibility\"}");
                    return;
                }

                AndroidTouchAccessibilityService service = AndroidTouchAccessibilityService.getInstance();
                if (service == null) {
                    writeResponse(currentSocket.getOutputStream(), 503, "{\"error\":\"accessibility_service_not_connected\"}");
                    return;
                }

                if ("GET".equals(request.method) && "/v1/display".equals(request.path)) {
                    AndroidTouchAccessibilityService.DisplayInfo info = service.getDisplayInfo();
                    String density = String.format(Locale.US, "%.3f", info.density);
                    writeResponse(currentSocket.getOutputStream(), 200,
                            "{\"width\":" + info.width + ",\"height\":" + info.height + ",\"density\":" + density + "}");
                    return;
                }

                if ("GET".equals(request.method) && "/v1/foreground".equals(request.path)) {
                    AndroidTouchAccessibilityService.ForegroundInfo info = service.getForeground();
                    writeResponse(currentSocket.getOutputStream(), 200,
                            "{\"package\":" + jsonStringOrNull(info.packageName)
                                    + ",\"activity\":" + jsonStringOrNull(info.className) + "}");
                    return;
                }

                if (!"POST".equals(request.method)) {
                    writeResponse(currentSocket.getOutputStream(), 405, "{\"error\":\"method_not_allowed\"}");
                    return;
                }

                if ("/v1/tap".equals(request.path)) {
                    handleTap(currentSocket, service, request.body);
                    return;
                }
                if ("/v1/swipe".equals(request.path)) {
                    handleSwipe(currentSocket, service, request.body);
                    return;
                }
                if ("/v1/text".equals(request.path)) {
                    handleText(currentSocket, service, request.body);
                    return;
                }
                if ("/v1/key".equals(request.path)) {
                    handleKey(currentSocket, service, request.body);
                    return;
                }
                if ("/v1/launch".equals(request.path)) {
                    handleLaunch(currentSocket, service, request.body);
                    return;
                }

                if (!"/".equals(request.path) && !"/v1/touch".equals(request.path)) {
                    writeResponse(currentSocket.getOutputStream(), 404, "{\"error\":\"not_found\"}");
                    return;
                }

                TouchGesture gesture = TouchGesture.fromJson(request.body);
                GestureResult result = service.dispatch(gesture);
                if (result.isSuccess()) {
                    writeResponse(currentSocket.getOutputStream(), 200, "{\"status\":\"completed\"}");
                } else {
                    writeResponse(currentSocket.getOutputStream(), 409, "{\"error\":\"" + jsonEscape(result.getMessage()) + "\"}");
                }
            } catch (GestureParseException exception) {
                writeError(currentSocket, 400, exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                writeError(currentSocket, 500, "gesture dispatch interrupted");
            } catch (IOException exception) {
                writeError(currentSocket, 500, exception.getMessage());
            } catch (RuntimeException exception) {
                writeError(currentSocket, 500, exception.getMessage());
            }
        } catch (IOException exception) {
        }
    }

    private void handleTap(Socket socket, AndroidTouchAccessibilityService service, String body)
            throws IOException, GestureParseException, InterruptedException {
        JSONObject json = parseJsonObject(body);
        float x = (float) requireDouble(json, "x");
        float y = (float) requireDouble(json, "y");
        if (x < 0 || y < 0) {
            throw new GestureParseException("x and y must be non-negative");
        }
        GestureResult result = service.dispatchTap(x, y);
        writeGestureResult(socket, result);
    }

    private void handleSwipe(Socket socket, AndroidTouchAccessibilityService service, String body)
            throws IOException, GestureParseException, InterruptedException {
        JSONObject json = parseJsonObject(body);
        float x1 = (float) requireDouble(json, "x1");
        float y1 = (float) requireDouble(json, "y1");
        float x2 = (float) requireDouble(json, "x2");
        float y2 = (float) requireDouble(json, "y2");
        long durationMs = json.optLong("duration_ms", 300L);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
            throw new GestureParseException("coordinates must be non-negative");
        }
        if (durationMs < 0 || durationMs > 60_000L) {
            throw new GestureParseException("duration_ms must be between 0 and 60000");
        }
        GestureResult result = service.dispatchSwipe(x1, y1, x2, y2, durationMs);
        writeGestureResult(socket, result);
    }

    private void handleText(Socket socket, AndroidTouchAccessibilityService service, String body)
            throws IOException, GestureParseException {
        JSONObject json = parseJsonObject(body);
        String text = requireString(json, "text");
        boolean ok = service.setFocusedText(text);
        if (ok) {
            writeResponse(socket.getOutputStream(), 200, "{\"status\":\"completed\"}");
        } else {
            writeResponse(socket.getOutputStream(), 409, "{\"error\":\"no_focused_input\"}");
        }
    }

    private void handleKey(Socket socket, AndroidTouchAccessibilityService service, String body)
            throws IOException, GestureParseException {
        JSONObject json = parseJsonObject(body);
        String action = requireString(json, "action");
        boolean ok = service.performKey(action);
        if (ok) {
            writeResponse(socket.getOutputStream(), 200, "{\"status\":\"completed\"}");
        } else {
            writeResponse(socket.getOutputStream(), 409, "{\"error\":\"global_action_rejected\"}");
        }
    }

    private void handleLaunch(Socket socket, AndroidTouchAccessibilityService service, String body)
            throws IOException, GestureParseException {
        JSONObject json = parseJsonObject(body);
        String pkg = requireString(json, "package");
        service.launchPackage(pkg);
        writeResponse(socket.getOutputStream(), 200, "{\"status\":\"completed\"}");
    }

    private void writeGestureResult(Socket socket, GestureResult result) throws IOException {
        if (result.isSuccess()) {
            writeResponse(socket.getOutputStream(), 200, "{\"status\":\"completed\"}");
        } else {
            writeResponse(socket.getOutputStream(), 409, "{\"error\":\"" + jsonEscape(result.getMessage()) + "\"}");
        }
    }

    private static JSONObject parseJsonObject(String body) throws GestureParseException {
        try {
            return new JSONObject(body == null ? "" : body);
        } catch (JSONException exception) {
            throw new GestureParseException("Invalid JSON: " + exception.getMessage(), exception);
        }
    }

    private static String requireString(JSONObject json, String key) throws GestureParseException {
        if (!json.has(key) || json.isNull(key)) {
            throw new GestureParseException("Missing required field: " + key);
        }
        try {
            return json.getString(key);
        } catch (JSONException exception) {
            throw new GestureParseException("Field " + key + " must be a string", exception);
        }
    }

    private static double requireDouble(JSONObject json, String key) throws GestureParseException {
        if (!json.has(key) || json.isNull(key)) {
            throw new GestureParseException("Missing required field: " + key);
        }
        try {
            return json.getDouble(key);
        } catch (JSONException exception) {
            throw new GestureParseException("Field " + key + " must be numeric", exception);
        }
    }

    private static String jsonStringOrNull(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + jsonEscape(value) + "\"";
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

        ByteArrayOutputStream body = new ByteArrayOutputStream(contentLength);
        int offset = 0;
        while (offset < contentLength) {
            int read = reader.read();
            if (read < 0) {
                throw new IOException("unexpected end of request body");
            }
            body.write(read);
            offset++;
        }
        return new HttpRequest(requestParts[0], requestParts[1], body.toString(StandardCharsets.UTF_8.name()));
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

    private void writeError(Socket socket, int statusCode, String message) {
        try {
            writeResponse(socket.getOutputStream(), statusCode, "{\"error\":\"" + jsonEscape(message) + "\"}");
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
        private final String body;

        private HttpRequest(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }
}
