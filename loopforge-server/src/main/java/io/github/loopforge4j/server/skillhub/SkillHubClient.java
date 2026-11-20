package io.github.loopforge4j.server.skillhub;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads the SkillHub market (upstream {@code skillhubClient}). The API key goes only to the market's
 * own host: redirects are followed by hand and drop it when they leave.
 */
@Component
public class SkillHubClient {

    static final String API_KEY_HEADER = "X-API-Key";
    static final String USER_HEADER = "X-Client-User-Id";
    private static final int MAX_REDIRECTS = 5;
    private static final long JSON_LIMIT = 4L << 20;

    private final LoopForgeProperties.Skillhub config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public SkillHubClient(LoopForgeProperties properties) {
        this.config = properties.skillhub();
    }

    public boolean configured() {
        return config.configured();
    }

    public JsonNode json(String path, Map<String, String> query, String userKey) {
        byte[] body = get(path, query, userKey, config.timeout(), JSON_LIMIT);
        try {
            return Json.mapper().readTree(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SkillHub 返回了无法解析的数据");
        }
    }

    /** A file's bytes, at most {@code limit}; {@code null} when it is larger. */
    public byte[] file(String path, Map<String, String> query, String userKey, long limit) {
        try {
            return get(path, query, userKey, config.downloadTimeout(), limit);
        } catch (TooLarge e) {
            return null;
        }
    }

    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super(null, null, false, false);
        }
    }

    private byte[] get(String path, Map<String, String> query, String userKey, Duration timeout, long limit) {
        if (!configured()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SkillHub 未配置（loopforge.skillhub.base-url）");
        }
        URI base = URI.create(config.baseUrl().strip().replaceAll("/+$", ""));
        URI target = URI.create(base + path + encode(query));
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(target)
                    .timeout(timeout)
                    .header("Accept", "application/json, */*")
                    .GET();
            boolean ownHost = sameOrigin(base, target);
            if (ownHost && config.apiKey() != null && !config.apiKey().isBlank()) {
                request.header(API_KEY_HEADER, config.apiKey().strip());
            }
            if (ownHost && userKey != null) {
                request.header(USER_HEADER, userKey);
            }
            HttpResponse<InputStream> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (java.net.http.HttpTimeoutException e) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "SkillHub 响应超时，请稍后重试");
            } catch (IOException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "无法连接 SkillHub，请检查网络后重试");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted");
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                close(response);
                if (location == null) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "SkillHub 返回了无效的跳转");
                }
                target = target.resolve(location);
                if (!"https".equals(target.getScheme()) && !"http".equals(target.getScheme())) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "SkillHub 返回了无效的跳转");
                }
                continue;
            }
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
            } catch (IOException e) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "读取 SkillHub 响应失败");
            }
            if (status == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND, "SkillHub 上未找到该技能或文件（可能已下架或不可见）");
            }
            if (status == 429) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "SkillHub 请求过于频繁，请稍后重试");
            }
            if (status == 401 || status == 403) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SkillHub 拒绝了访问，请检查 API Key");
            }
            if (status >= 400) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "SkillHub 返回错误 " + status);
            }
            if (body.length > limit) {
                throw new TooLarge();
            }
            return body;
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "SkillHub 跳转次数过多");
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost())
                && a.getPort() == b.getPort();
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException e) {
            // ignore
        }
    }

    private static String encode(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("&", "?", "");
        new LinkedHashMap<>(query).forEach((k, v) -> {
            if (v != null && !v.isBlank()) {
                joiner.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(v, StandardCharsets.UTF_8));
            }
        });
        return joiner.length() == 1 ? "" : joiner.toString();
    }
}
