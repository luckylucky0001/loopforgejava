package io.github.loopforge4j.server.knowledge;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.mcp.McpRpc;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Minimal Streamable HTTP MCP client (upstream {@code mcpclient}): initialize once, then list and
 * call tools. Responses may be plain JSON or a server-sent event stream.
 */
final class McpHttpClient {

    /** A tool the upstream server lists. */
    record Tool(String name, String description, JsonNode inputSchema, JsonNode annotations) {}

    /** The upstream server answered with an error, or could not be reached. */
    static final class McpException extends IOException {
        McpException(String message) {
            super(message);
        }
    }

    private static final int MAX_RESPONSE = 8 << 20;

    private final URI endpoint;
    private final String authorization;
    private final Duration timeout;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();
    private final Object lock = new Object();
    private volatile String sessionId;
    private volatile String protocol;

    McpHttpClient(URI endpoint, String token, Duration timeout) {
        this.endpoint = endpoint;
        this.authorization = "Bearer " + token;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    List<Tool> tools() throws IOException {
        JsonNode result = request("tools/list", Map.of());
        List<Tool> tools = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            tools.add(new Tool(
                    t.path("name").asString(""),
                    t.path("description").asString(""),
                    t.get("inputSchema"),
                    t.get("annotations")));
        }
        return tools;
    }

    /** Calls a tool and returns its text content; a tool error is thrown with that text. */
    String call(String tool, Map<String, Object> arguments) throws IOException {
        JsonNode result = request("tools/call", Map.of("name", tool, "arguments", arguments));
        StringBuilder text = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asString(""))) {
                text.append(c.path("text").asString(""));
            }
        }
        if (text.isEmpty() && result.has("structuredContent")) {
            text.append(Json.write(result.get("structuredContent")));
        }
        if (result.path("isError").asBoolean(false)) {
            throw new McpException(clip(text.toString().strip(), 300));
        }
        return text.toString();
    }

    private JsonNode request(String method, Map<String, Object> params) throws IOException {
        ensureInitialized();
        try {
            return send(method, params);
        } catch (SessionExpired e) {
            synchronized (lock) {
                sessionId = null;
                protocol = null;
            }
            ensureInitialized();
            return send(method, params);
        }
    }

    private void ensureInitialized() throws IOException {
        if (protocol != null) {
            return;
        }
        synchronized (lock) {
            if (protocol != null) {
                return;
            }
            JsonNode result;
            try {
                result = send(
                        "initialize",
                        Map.of(
                                "protocolVersion", McpRpc.LATEST,
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "loopforge4j", "version", "1")));
            } catch (SessionExpired e) {
                throw new McpException("knowledge base rejected the MCP session");
            }
            String version = result.path("protocolVersion").asString(McpRpc.LATEST);
            protocol = version.isBlank() ? McpRpc.LATEST : version;
            notifyInitialized();
        }
    }

    private void notifyInitialized() throws IOException {
        HttpRequest request = base().POST(HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")),
                        StandardCharsets.UTF_8))
                .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted");
        }
    }

    private HttpRequest.Builder base() {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", authorization);
        if (protocol != null) {
            b.header("MCP-Protocol-Version", protocol);
        }
        if (sessionId != null) {
            b.header("Mcp-Session-Id", sessionId);
        }
        return b;
    }

    /** The session the server issued is gone (HTTP 404 with a session id): start a new one. */
    private static final class SessionExpired extends IOException {}

    private JsonNode send(String method, Map<String, Object> params) throws IOException {
        long id = ids.incrementAndGet();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        HttpRequest request = base().POST(
                        HttpRequest.BodyPublishers.ofString(Json.write(message), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("interrupted");
        } catch (IOException e) {
            throw new McpException(
                    "knowledge base is unreachable: " + e.getClass().getSimpleName());
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404 && sessionId != null) {
                throw new SessionExpired();
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new McpException("knowledge base rejected the token (HTTP " + response.statusCode() + ")");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new McpException("knowledge base answered HTTP " + response.statusCode());
            }
            response.headers().firstValue("Mcp-Session-Id").ifPresent(s -> sessionId = s);
            String type = response.headers().firstValue("Content-Type").orElse("");
            JsonNode reply = type.startsWith("text/event-stream") ? fromEvents(body, id) : fromJson(body);
            if (reply == null) {
                throw new McpException("knowledge base sent no response to " + method);
            }
            JsonNode error = reply.get("error");
            if (error != null && !error.isNull()) {
                throw new McpException(
                        "knowledge base error: " + clip(error.path("message").asString(""), 300));
            }
            JsonNode result = reply.get("result");
            if (result == null) {
                throw new McpException("knowledge base sent no result to " + method);
            }
            return result;
        }
    }

    private static JsonNode fromJson(InputStream body) throws IOException {
        byte[] bytes = body.readNBytes(MAX_RESPONSE);
        try {
            return Json.mapper().readTree(bytes);
        } catch (JacksonException e) {
            throw new McpException("knowledge base sent invalid JSON");
        }
    }

    /** The first message of the stream that answers {@code id}. */
    private static JsonNode fromEvents(InputStream body, long id) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder();
        long read = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            read += line.length();
            if (read > MAX_RESPONSE) {
                throw new McpException("knowledge base response is too large");
            }
            if (line.isEmpty()) {
                JsonNode message = parseEvent(data);
                data.setLength(0);
                if (message != null && message.path("id").asLong(-1) == id) {
                    return message;
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).stripLeading()).append('\n');
            }
        }
        JsonNode message = parseEvent(data);
        return message != null && message.path("id").asLong(-1) == id ? message : null;
    }

    private static JsonNode parseEvent(StringBuilder data) {
        if (data.isEmpty()) {
            return null;
        }
        try {
            return Json.mapper().readTree(data.toString());
        } catch (JacksonException e) {
            return null;
        }
    }

    static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
