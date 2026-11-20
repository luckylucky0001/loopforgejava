package io.github.loopforge4j.server.tracker;

import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/** JSON over HTTP for the tracker clients; errors carry host and status only. */
final class TrackerHttp {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private TrackerHttp() {}

    static JsonNode send(HttpRequest.Builder request) throws TrackerException {
        HttpRequest built = request.timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "loopforge4j")
                .build();
        String host = built.uri().getHost();
        HttpResponse<byte[]> response;
        try {
            response = HTTP.send(built, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new TrackerException(host + " is unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackerException("interrupted");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new TrackerException(host + " rejected the credentials (HTTP " + status + ")");
        }
        if (status < 200 || status >= 300) {
            throw new TrackerException(host + " answered HTTP " + status);
        }
        if (response.body().length > (16 << 20)) {
            throw new TrackerException(host + " response is too large");
        }
        try {
            return Json.mapper().readTree(response.body().length == 0 ? "{}".getBytes() : response.body());
        } catch (JacksonException e) {
            throw new TrackerException(host + " sent invalid JSON");
        }
    }

    static HttpRequest.BodyPublisher json(Object body) {
        return HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8);
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static URI uri(URI base, String path) {
        return URI.create(base.toString().replaceAll("/+$", "") + path);
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asString("");
    }
}
