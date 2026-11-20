package io.github.loopforge4j.server.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
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

/** Preview nodes archive a static site that opens through signed, expiring links. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreviewIntegrationTest {

    static final String TOKEN = "preview-token-0123456789-abcdefghij";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-preview-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:preview;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    final HttpClient browser =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

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

    HttpResponse<byte[]> open(String path) throws Exception {
        return browser.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    String member(RestClient admin, String name, String role) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        if (role != null) {
            call(admin, "PUT", "/api/v1/projects/p1/members/" + created.get("id"), Map.of("role", role), 200);
        }
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return (String) login.get("token");
    }

    @SuppressWarnings("unchecked")
    String settledRun(RestClient c, String workflow) {
        Map<String, Object> run =
                call(c, "POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of("succeeded", "failed")
                        .contains(
                                ((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        return id;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theArchivedSiteOpensThroughASignedLinkInAnIsolatedOrigin() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        RestClient vic = client(member(admin, "vic", "viewer"));
        RestClient outsider = client(member(admin, "olga", null));
        String build =
                "mkdir -p dist/assets dist/docs && printf '<script src=\"assets/app.js\"></script>' > dist/index.html"
                        + " && echo 'console.log(1)' > dist/assets/app.js && echo docs > dist/docs/index.html";
        call(
                admin,
                "PUT",
                "/api/v1/workflows/site",
                Map.of(
                        "name", "站点",
                        "status", "published",
                        "projectId", "p1",
                        "nodes",
                                List.of(Map.of(
                                        "id",
                                        "preview",
                                        "type",
                                        "preview",
                                        "script",
                                        build,
                                        "preview",
                                        Map.of("path", "dist", "spa", true)))),
                200);
        Map<String, Object> stored = call(admin, "GET", "/api/v1/workflows/site", null, 200);
        assertThat(((List<Map<String, Object>>) stored.get("nodes")).getFirst())
                .containsEntry("preview", Map.of("path", "dist", "index", "index.html", "spa", true));
        call(
                admin,
                "PUT",
                "/api/v1/workflows/bad",
                Map.of(
                        "name",
                        "坏",
                        "nodes",
                        List.of(Map.of("id", "p", "type", "preview", "preview", Map.of("path", "../outside")))),
                422);

        String runId = settledRun(admin, "site");
        Map<String, Object> run = call(admin, "GET", "/api/v1/runs/" + runId, null, 200);
        assertThat(run).as(() -> Json.write(run)).containsEntry("status", "succeeded");

        // project members get the link; people outside the project do not
        call(outsider, "GET", "/api/v1/runs/" + runId + "/preview", null, 403);
        Map<String, Object> preview = call(vic, "GET", "/api/v1/runs/" + runId + "/preview", null, 200);
        assertThat(preview)
                .containsEntry("nodeId", "preview")
                .containsEntry("files", 3)
                .containsEntry("index", "index.html")
                .containsKey("expiresAt");
        String url = (String) preview.get("url");
        assertThat(url).startsWith("/preview/" + runId + "/").endsWith("/");

        // the browser needs no token; scripts run in an opaque origin
        HttpResponse<byte[]> index = open(url);
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(new String(index.body(), StandardCharsets.UTF_8)).contains("assets/app.js");
        assertThat(index.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("text/html"));
        assertThat(index.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(csp ->
                        assertThat(csp).startsWith("sandbox allow-scripts").doesNotContain("allow-same-origin"));
        assertThat(index.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(open(url + "assets/app.js").headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("text/javascript"));
        // client-side routes fall back to the entry page; missing files do not
        assertThat(new String(open(url + "settings/profile").body(), StandardCharsets.UTF_8))
                .contains("assets/app.js");
        assertThat(open(url + "missing.png").statusCode()).isEqualTo(404);
        // directories redirect so that relative links resolve
        HttpResponse<byte[]> bare = open(url.substring(0, url.length() - 1));
        assertThat(bare.statusCode()).isEqualTo(302);
        assertThat(bare.headers().firstValue("Location")).hasValue(url);
        assertThat(open(url + "docs").headers().firstValue("Location")).hasValue(url + "docs/");
        assertThat(new String(open(url + "docs/").body(), StandardCharsets.UTF_8))
                .isEqualTo("docs\n");

        // a tampered, expired or foreign link opens nothing
        String token = url.split("/")[3];
        long expires = Long.parseLong(token.substring(0, token.indexOf('.')));
        String signature = token.substring(token.indexOf('.') + 1);
        assertThat(open("/preview/" + runId + "/" + (expires + 3600) + "." + signature + "/")
                        .statusCode())
                .isEqualTo(403);
        assertThat(open("/preview/" + runId + "/1." + signature + "/").statusCode())
                .isEqualTo(403);
        assertThat(open("/preview/" + runId + "/garbage/").statusCode()).isEqualTo(403);
        String other = settledRun(admin, "site");
        assertThat(open("/preview/" + other + "/" + token + "/").statusCode()).isEqualTo(403);

        // the zip holds the site
        ResponseEntity<byte[]> zip = vic.get()
                .uri("/api/v1/runs/" + runId + "/preview/download")
                .retrieve()
                .toEntity(byte[].class);
        assertThat(zip.getStatusCode().value()).isEqualTo(200);
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.getBody()))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.add(e.getName());
            }
        }
        assertThat(entries).containsExactly("assets/app.js", "docs/index.html", "index.html");

        // agents can hand out the link through the platform MCP, and the preview goes with its run
        call(admin, "DELETE", "/api/v1/runs/" + runId, null, 204);
        assertThat(open(url).statusCode()).isEqualTo(404);
        assertThat((Map<String, Object>) call(admin, "GET", "/api/v1/runs/" + other + "/preview", null, 200))
                .containsKey("url");
    }
}
