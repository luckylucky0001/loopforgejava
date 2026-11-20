package io.github.loopforge4j.server.mcp;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Calls this server's own API as the original caller (upstream {@code RemoteInvoker} against
 * itself): the request passes the whole security filter chain and the controllers' access checks
 * again, so a tool can never do more than the caller's own token.
 */
@Component
public class InternalApi {

    /** Responses beyond this are cut off (the client also limits what it shows). */
    static final int MAX_BODY = 8 << 20;

    /**
     * @param truncated the body was longer than {@link #MAX_BODY}
     */
    public record Response(int status, String contentType, String body, boolean truncated) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String configured;
    private final String contextPath;
    private final RunTokens tokens;
    private volatile String loopback;

    public InternalApi(LoopForgeProperties properties, Environment environment, RunTokens tokens) {
        this.tokens = tokens;
        String url = properties.mcp().internalUrl();
        this.configured = url == null || url.isBlank() ? null : strip(url);
        this.contextPath = strip(environment.getProperty("server.servlet.context-path", ""));
    }

    @EventListener
    void onStarted(WebServerInitializedEvent event) {
        // a separate management server (management.server.port) announces itself too: not the API
        if (event.getApplicationContext().getServerNamespace() != null) {
            return;
        }
        if (event.getWebServer().getPort() > 0) {
            loopback = "http://127.0.0.1:" + event.getWebServer().getPort() + contextPath;
        }
    }

    /** Base URL of this server on the loopback interface, {@code null} before it listens. */
    public String loopbackBase() {
        return loopback;
    }

    /** How processes on this host reach the API: the configured internal URL, else loopback. */
    public String localBase() {
        return configured != null ? configured : loopback;
    }

    /**
     * @param pathAndQuery absolute path below the base, query already encoded
     * @param authorization the caller's {@code Authorization} header, forwarded unchanged
     */
    public Response call(String method, String pathAndQuery, String jsonBody, String authorization)
            throws IOException, InterruptedException {
        String base = configured != null ? configured : loopback;
        if (base == null) {
            throw new IOException("the server is not listening yet");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json");
        if (authorization != null && !authorization.isBlank()) {
            request.header("Authorization", authorization);
        }
        // the assistant's delegated token is accepted only on requests forwarded for its MCP tools
        request.header(RunTokens.FORWARDED, tokens.forwardingMark());
        if (jsonBody != null) {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            boolean truncated = bytes.length > MAX_BODY;
            String text = new String(bytes, 0, Math.min(bytes.length, MAX_BODY), StandardCharsets.UTF_8);
            return new Response(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    text,
                    truncated);
        }
    }

    private static String strip(String url) {
        String s = url.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
