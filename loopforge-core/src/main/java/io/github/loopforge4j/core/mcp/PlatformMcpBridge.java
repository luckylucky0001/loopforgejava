package io.github.loopforge4j.core.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stdio bridge to a LoopForge server's platform MCP (upstream {@code agentflow-mcp}) for MCP clients
 * that only start local processes. JDK only: {@code java -cp loopforge-core.jar
 * io.github.loopforge4j.core.mcp.PlatformMcpBridge} with {@code LOOPFORGE_API_URL} and {@code
 * LOOPFORGE_API_TOKEN}. Every stdin line is one JSON-RPC message, relayed to {@code <url>/mcp}.
 */
public final class PlatformMcpBridge {

    public static final String URL_ENV = "LOOPFORGE_API_URL";
    public static final String TOKEN_ENV = "LOOPFORGE_API_TOKEN";
    private static final int MAX_LINE = 4 << 20;

    private final URI endpoint;
    private final String token;
    private final HttpClient http;
    private final PrintStream out;

    PlatformMcpBridge(String baseUrl, String token, HttpClient http, PrintStream out) {
        String base = baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = URI.create(base + "/mcp");
        this.token = token == null ? "" : token.strip();
        this.http = http;
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        String url = System.getenv(URL_ENV);
        if (url == null || url.isBlank()) {
            System.err.println("loopforge-mcp: set " + URL_ENV + " to the running LoopForge server");
            System.exit(2);
        }
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        new PlatformMcpBridge(
                        url, System.getenv(TOKEN_ENV), http, new PrintStream(System.out, true, StandardCharsets.UTF_8))
                .serve(System.in);
    }

    void serve(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String reply = line.length() > MAX_LINE ? error(null, -32600, "request too large") : relay(line);
            if (reply != null) {
                out.println(reply);
            }
        }
    }

    /** The server's reply as one line, {@code null} for notifications. */
    String relay(String line) {
        Object id = null;
        boolean notification = false;
        try {
            Object parsed = MiniJson.parse(line);
            if (parsed instanceof Map<?, ?> message) {
                id = message.get("id");
                notification = !message.containsKey("id");
            }
        } catch (RuntimeException e) {
            return error(null, -32700, "parse error");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(line, StandardCharsets.UTF_8));
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return notification
                    ? null
                    : error(
                            id,
                            -32603,
                            "LoopForge server is unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return notification ? null : error(id, -32603, "interrupted");
        }
        if (notification || response.statusCode() == 202) {
            return null;
        }
        String body = response.body() == null ? "" : response.body().strip();
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return error(
                    id,
                    -32001,
                    "LoopForge rejected the token (HTTP " + response.statusCode() + "); check " + TOKEN_ENV);
        }
        try {
            Object parsed = MiniJson.parse(body);
            if (parsed instanceof Map<?, ?> map && (map.containsKey("result") || map.containsKey("error"))) {
                // one line on stdout, whatever the server's formatting
                return MiniJson.write(parsed);
            }
        } catch (RuntimeException e) {
            // not JSON-RPC
        }
        return error(id, -32603, "LoopForge answered HTTP " + response.statusCode());
    }

    static String error(Object id, int code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", error);
        return MiniJson.write(out);
    }
}
