package io.github.loopforge4j.server.skillhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.json.Json;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Browsing the SkillHub market and installing / updating skills from it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkillHubIntegrationTest {

    static final String TOKEN = "skillhub-test-token-0123456789-abcdef";
    static final String API_KEY = "hub-api-key-123";
    static final HttpServer hub;
    static final HttpServer cdn;
    static final List<String> hubCalls = new CopyOnWriteArrayList<>();
    static final List<String> cdnKeys = new CopyOnWriteArrayList<>();

    static {
        try {
            cdn = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            cdn.createContext("/blob", ex -> {
                cdnKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("X-API-Key")));
                reply(ex, 200, "# 参考\n来自 CDN 的说明\n".getBytes(StandardCharsets.UTF_8));
            });
            cdn.start();
            hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            hub.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                hubCalls.add(path + " key=" + ex.getRequestHeaders().getFirst("X-API-Key") + " user="
                        + ex.getRequestHeaders().getFirst("X-Client-User-Id"));
                String version = q.getOrDefault("version", "1.1.0");
                switch (path) {
                    case "/api/skills" ->
                        json(
                                ex,
                                Map.of(
                                        "code",
                                        0,
                                        "data",
                                        Map.of(
                                                "total",
                                                1,
                                                "skills",
                                                List.of(Map.of(
                                                        "slug",
                                                        "pdf-tools",
                                                        "name",
                                                        "PDF 工具",
                                                        "version",
                                                        "1.1.0",
                                                        "namespace",
                                                        Map.of("handle", "acme"),
                                                        "keyword",
                                                        q.getOrDefault("keyword", ""),
                                                        "pageSize",
                                                        q.getOrDefault("pageSize", ""))))));
                    case "/api/v1/categories" ->
                        json(ex, Map.of("items", List.of(Map.of("key", "docs", "name", "文档"))));
                    case "/api/v1/skills/pdf-tools" ->
                        json(
                                ex,
                                Map.of(
                                        "namespace", Map.of("handle", "acme"),
                                        "latestVersion", Map.of("version", "1.1.0"),
                                        "skill",
                                                Map.of(
                                                        "slug",
                                                        "pdf-tools",
                                                        "displayName",
                                                        "PDF 工具",
                                                        "summary_zh",
                                                        "处理 PDF")));
                    case "/api/v1/skills/pdf-tools/versions" ->
                        json(ex, Map.of("versions", List.of(Map.of("version", "1.1.0"), Map.of("version", "1.0.0"))));
                    case "/api/v1/skills/pdf-tools/files" ->
                        json(
                                ex,
                                Map.of(
                                        "files",
                                        List.of(
                                                Map.of("path", "SKILL.md", "size", 20),
                                                Map.of("path", "scripts/split.py", "size", 30),
                                                Map.of("path", "references/guide.md", "size", 30),
                                                Map.of("path", "assets/logo.png", "size", 8),
                                                Map.of("path", "../escape.sh", "size", 5),
                                                Map.of("path", "huge.bin", "size", 50_000_000))));
                    case "/api/v1/skills/pdf-tools/file" -> {
                        String file = q.get("path");
                        switch (file) {
                            case "SKILL.md" ->
                                reply(
                                        ex,
                                        200,
                                        ("# PDF 工具 " + version + "\n用 split.py 拆分\n").getBytes(StandardCharsets.UTF_8));
                            case "scripts/split.py" ->
                                reply(ex, 200, "print('split')\n".getBytes(StandardCharsets.UTF_8));
                            case "references/guide.md" -> {
                                ex.getResponseHeaders()
                                        .add(
                                                "Location",
                                                "http://127.0.0.1:"
                                                        + cdn.getAddress().getPort() + "/blob/guide.md");
                                ex.sendResponseHeaders(302, -1);
                                ex.close();
                            }
                            case "assets/logo.png" ->
                                reply(ex, 200, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, (byte) 0xff});
                            default -> reply(ex, 404, new byte[0]);
                        }
                    }
                    default -> reply(ex, 404, new byte[0]);
                }
            });
            hub.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new java.util.HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] kv = pair.split("=", 2);
                out.put(
                        URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return out;
    }

    static void json(HttpExchange ex, Object body) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        reply(ex, 200, Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    static void reply(HttpExchange ex, int status, byte[] body) throws java.io.IOException {
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    @AfterAll
    static void stop() {
        hub.stop(0);
        cdn.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-skillhub-it");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:skillhub;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        // a different spelling of the host than the redirect target, so the key must not travel there
        registry.add(
                "loopforge.skillhub.base-url",
                () -> "http://localhost:" + hub.getAddress().getPort() + "/");
        registry.add("loopforge.skillhub.api-key", () -> API_KEY);
        registry.add("loopforge.skillhub.max-file-size", () -> "1MB");
    }

    @LocalServerPort
    int port;

    RestClient client(String token) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @SuppressWarnings("unchecked")
    <T> T call(RestClient c, String method, String path, Object body, int status) {
        var spec = c.method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> r = spec.retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        return r.getBody() == null || r.getBody().isBlank() ? null : (T) Json.read(r.getBody(), Object.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skillsAreBrowsedInstalledAndUpdated() {
        RestClient admin = client(TOKEN);
        call(admin, "POST", "/api/v1/users", Map.of("username", "mia", "password", "mia-password-1"), 201);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", "mia", "password", "mia-password-1"),
                200);
        RestClient mia = client((String) login.get("token"));

        assertThat((Map<String, Object>) call(mia, "GET", "/api/v1/skillhub/status", null, 200))
                .containsEntry("configured", true);
        Map<String, Object> page = call(mia, "GET", "/api/v1/skillhub/skills?keyword=pdf&pageSize=500", null, 200);
        assertThat((List<Map<String, Object>>) page.get("skills"))
                .singleElement()
                .satisfies(s -> assertThat(s)
                        .containsEntry("slug", "pdf-tools")
                        .containsEntry("keyword", "pdf")
                        .containsEntry("pageSize", "50"));
        assertThat(hubCalls)
                .anySatisfy(c -> assertThat(c).startsWith("/api/skills key=" + API_KEY + " user="))
                .noneSatisfy(c -> assertThat(c).contains("mia"));
        call(mia, "GET", "/api/v1/skillhub/categories", null, 200);
        call(mia, "GET", "/api/v1/skillhub/skills/pdf-tools/versions", null, 200);
        Map<String, Object> preview =
                call(mia, "GET", "/api/v1/skillhub/skills/pdf-tools/file?path=SKILL.md", null, 200);
        assertThat((String) preview.get("content")).startsWith("# PDF 工具");
        call(mia, "GET", "/api/v1/skillhub/skills/pdf-tools/file?path=../x", null, 422);
        call(mia, "GET", "/api/v1/skillhub/skills/-evil", null, 422);

        // installing is an administrator's job: skills are shared by every role
        call(mia, "POST", "/api/v1/skillhub/install", Map.of("slug", "pdf-tools"), 403);
        Map<String, Object> installed = call(
                admin,
                "POST",
                "/api/v1/skillhub/install",
                Map.of("slug", "pdf-tools", "version", "1.0.0", "id", "pdf"),
                201);
        assertThat((Map<String, Object>) installed.get("source"))
                .containsEntry("namespace", "acme")
                .containsEntry("version", "1.0.0")
                .containsEntry("installedBy", "operator");
        assertThat((List<String>) installed.get("skipped"))
                .containsExactlyInAnyOrder("assets/logo.png（二进制文件）", "../escape.sh（路径无效）", "huge.bin（超过单文件上限）");
        Map<String, Object> skill = call(admin, "GET", "/api/v1/skills/pdf", null, 200);
        assertThat(skill)
                .containsEntry("name", "PDF 工具")
                .containsEntry("description", "处理 PDF")
                .containsEntry("content", "# PDF 工具 1.0.0\n用 split.py 拆分\n");
        assertThat((Map<String, Object>) skill.get("files"))
                .containsOnlyKeys("scripts/split.py", "references/guide.md")
                .containsEntry("references/guide.md", "# 参考\n来自 CDN 的说明\n");
        // the redirect left the market's host: the API key stayed behind
        assertThat(cdnKeys).isNotEmpty().allSatisfy(k -> assertThat(k).isEqualTo("null"));

        call(admin, "POST", "/api/v1/skillhub/install", Map.of("slug", "pdf-tools", "id", "pdf-2"), 409);
        call(admin, "POST", "/api/v1/skillhub/install", Map.of("slug", "pdf-tools", "id", "bad id"), 422);

        Map<String, Object> updated = call(admin, "POST", "/api/v1/skillhub/update", Map.of("skillId", "pdf"), 200);
        assertThat((Map<String, Object>) updated.get("source")).containsEntry("version", "1.1.0");
        assertThat((String) ((Map<String, Object>) call(admin, "GET", "/api/v1/skills/pdf", null, 200)).get("content"))
                .startsWith("# PDF 工具 1.1.0");
        assertThat((List<Map<String, Object>>) call(mia, "GET", "/api/v1/skillhub/installed", null, 200))
                .singleElement()
                .satisfies(s -> assertThat(s).containsEntry("skillId", "pdf").containsEntry("version", "1.1.0"));
        call(admin, "POST", "/api/v1/skillhub/update", Map.of("skillId", "nope"), 404);
    }
}
