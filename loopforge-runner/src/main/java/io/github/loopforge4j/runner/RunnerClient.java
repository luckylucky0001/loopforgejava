package io.github.loopforge4j.runner;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.StoreException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The machine's only link to the server: outbound HTTPS to {@code /api/v1/runner-api} with the
 * machine token. Failures surface as {@link StoreException}, which the engine already treats as a
 * store outage (retries, crash back-off).
 */
public class RunnerClient {

    private final String base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public RunnerClient(String serverUrl, String token) {
        this.base = serverUrl.strip().replaceAll("/+$", "") + "/api/v1/runner-api";
        this.token = token;
    }

    /** The server's base URL as this machine reaches it. */
    public String serverUrl() {
        return base.substring(0, base.length() - "/api/v1/runner-api".length());
    }

    public JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), path)
                .orElseThrow(() -> new StoreException(path + " not found", null));
    }

    public Optional<JsonNode> find(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET(), path);
    }

    public JsonNode post(String path, Object body) {
        return send(
                        HttpRequest.newBuilder(URI.create(base + path))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8)),
                        path)
                .orElseThrow(() -> new StoreException(path + " not found", null));
    }

    private Optional<JsonNode> send(HttpRequest.Builder builder, String path) {
        HttpRequest request = builder.timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("User-Agent", "loopforge4j-runner")
                .build();
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new StoreException("server unreachable: " + e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("interrupted", e);
        }
        int status = response.statusCode();
        if (status == 404) {
            return Optional.empty();
        }
        if (status == 401) {
            throw new StoreException("the server rejected the runner token", null);
        }
        if (status < 200 || status >= 300) {
            throw new StoreException("server answered HTTP " + status + " for " + path, null);
        }
        return Optional.of(Json.mapper().readTree(response.body()));
    }
}
