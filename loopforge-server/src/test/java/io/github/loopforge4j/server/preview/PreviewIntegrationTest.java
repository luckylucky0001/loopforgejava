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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Frontend previews: the SOP contract's build step, preview links, the gateway and retention. */
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

    @Autowired
    PreviewService service;

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

    HttpResponse<String> open(String url, String... headers) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(url.startsWith("http") ? url : "http://localhost:" + port + url));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return browser.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
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
        Map<String, Object> done = call(c, "GET", "/api/v1/runs/" + id, null, 200);
        assertThat(done).as(() -> Json.write(done.get("nodes"))).containsEntry("status", "succeeded");
        return id;
    }

    static Map<String, Object> contractWorkflow(String setup, long retention) {
        return Map.of(
                "name", "前端交付",
                "status", "published",
                "projectId", "p1",
                "preview",
                        Map.of("enabled", true, "executorId", "mock", "spaFallback", true, "retentionSec", retention),
                "nodes", List.of(Map.of("id", "impl", "type", "script", "script", setup)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theContractsBuildStepArchivesASiteThatOpensThroughSessions() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        RestClient vic = client(member(admin, "vic", "viewer"));
        RestClient outsider = client(member(admin, "olga", null));

        // the build step is attached after every sink when the SOP is saved
        call(
                admin,
                "PUT",
                "/api/v1/workflows/fe",
                contractWorkflow("echo '{\"devDependencies\": {\"vite\": \"5\"}}' > package.json", 3600),
                200);
        Map<String, Object> stored = call(admin, "GET", "/api/v1/workflows/fe", null, 200);
        assertThat((List<Map<String, Object>>) stored.get("nodes"))
                .extracting(n -> n.get("id") + ":" + n.get("agentId"))
                .containsExactly("impl:null", "af-preview-build:af-preview-builder");
        assertThat((List<Map<String, Object>>) stored.get("edges"))
                .singleElement()
                .satisfies(
                        e -> assertThat(e).containsEntry("source", "impl").containsEntry("target", "af-preview-build"));
        assertThat((Map<String, Object>) stored.get("preview"))
                .containsEntry("sourcePath", "dist")
                .containsEntry("indexFile", "index.html")
                .containsEntry("kind", "static");

        String runId = settledRun(admin, "fe");
        Map<String, Object> preview = call(vic, "GET", "/api/v1/runs/" + runId + "/preview", null, 200);
        assertThat(preview)
                .containsEntry("status", "ready")
                .containsEntry("nodeId", "af-preview-build")
                .containsEntry("fileCount", 1)
                .containsEntry("entryFile", "index.html")
                .containsEntry("spaFallback", true);
        assertThat(Instant.parse((String) preview.get("expiresAt")))
                .isBetween(Instant.now().plusSeconds(3000), Instant.now().plusSeconds(3700));
        call(outsider, "GET", "/api/v1/runs/" + runId + "/preview", null, 403);
        call(outsider, "POST", "/api/v1/runs/" + runId + "/preview/session", Map.of(), 403);

        // a viewer opens a link; the page runs in an opaque origin with emulated storage
        Map<String, Object> session = call(vic, "POST", "/api/v1/runs/" + runId + "/preview/session", Map.of(), 200);
        String url = (String) session.get("url");
        assertThat(url).matches("http://localhost:" + port + "/preview/[0-9a-f]{32}/");
        String prefix = URI.create(url).getPath().replaceAll("/$", "");
        HttpResponse<String> page = open(url);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body())
                .contains("frontend-preview")
                .contains("data-lf-preview-storage")
                .doesNotContain("data-lf-preview-backend");
        assertThat(page.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(csp -> assertThat(csp)
                        .startsWith("sandbox allow-scripts")
                        .doesNotContain("allow-same-origin")
                        .contains("connect-src 'self';"));
        assertThat(page.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(page.headers().firstValue("Referrer-Policy")).hasValue("same-origin");

        // with a backend address the site's calls are sent there
        call(vic, "POST", "/api/v1/runs/" + runId + "/preview/session", Map.of("backendUrl", "ftp://x"), 400);
        call(vic, "POST", "/api/v1/runs/" + runId + "/preview/session", Map.of("backendUrl", "http://u:p@x.test"), 400);
        Map<String, Object> withBackend = call(
                vic,
                "POST",
                "/api/v1/runs/" + runId + "/preview/session",
                Map.of("backendUrl", "http://api.example.test:8080/v1#frag"),
                200);
        assertThat(withBackend).containsEntry("backendUrl", "http://api.example.test:8080/v1");
        HttpResponse<String> routed = open((String) withBackend.get("url"));
        assertThat(routed.body())
                .contains("window.__LOOPFORGE_PREVIEW_BACKEND__=\"http://api.example.test:8080/v1\"")
                .contains("data-lf-preview-history");
        assertThat(routed.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(
                        csp -> assertThat(csp).contains("connect-src 'self' http://api.example.test:8080;"));

        // storing a default backend address is for operators; it also hands back a fresh link
        call(vic, "PUT", "/api/v1/runs/" + runId + "/preview/backend", Map.of("backendUrl", "https://api.test"), 403);
        Map<String, Object> updated = call(
                admin,
                "PUT",
                "/api/v1/runs/" + runId + "/preview/backend",
                Map.of("backendUrl", "https://api.test"),
                200);
        assertThat((Map<String, Object>) updated.get("preview")).containsEntry("backendUrl", "https://api.test");
        assertThat((Map<String, Object>) updated.get("session")).containsEntry("backendUrl", "https://api.test");
        assertThat((Map<String, Object>) call(vic, "POST", "/api/v1/runs/" + runId + "/preview/session", null, 200))
                .containsEntry("backendUrl", "https://api.test");

        // a page sent to a root path comes back under the preview
        HttpResponse<String> stray = open(
                "/login?next=1",
                "Referer",
                url + "settings",
                "Sec-Fetch-Site",
                "same-origin",
                "Sec-Fetch-Mode",
                "navigate");
        assertThat(stray.statusCode()).isEqualTo(303);
        assertThat(stray.headers().firstValue("Location")).hasValue(prefix + "/login?next=1");
        assertThat(open("/login", "Referer", url, "Sec-Fetch-Site", "cross-site", "Sec-Fetch-Mode", "navigate")
                        .statusCode())
                .isNotEqualTo(303);

        // revoked, unknown and malformed links open nothing; viewers revoke only their own
        Map<String, Object> adminLink =
                call(admin, "POST", "/api/v1/runs/" + runId + "/preview/session", Map.of(), 200);
        call(vic, "DELETE", "/api/v1/runs/" + runId + "/preview/session/" + adminLink.get("id"), null, 403);
        call(admin, "DELETE", "/api/v1/runs/" + runId + "/preview/session/" + adminLink.get("id"), null, 204);
        call(vic, "DELETE", "/api/v1/runs/" + runId + "/preview/session/" + session.get("id"), null, 204);
        assertThat(open(url).statusCode()).isEqualTo(403);
        assertThat(open("/preview/" + "0".repeat(32) + "/").statusCode()).isEqualTo(403);
        assertThat(open("/preview/not-a-token/").statusCode()).isEqualTo(404);

        // zip download
        ResponseEntity<byte[]> zip = vic.get()
                .uri("/api/v1/runs/" + runId + "/preview/download")
                .retrieve()
                .toEntity(byte[].class);
        assertThat(zip.getStatusCode().value()).isEqualTo(200);
        assertThat(entries(zip.getBody())).containsExactly("index.html");

        // agents read the preview and open links through the platform MCP; deleting needs a maintainer
        call(vic, "DELETE", "/api/v1/runs/" + runId + "/preview", null, 403);
        call(admin, "DELETE", "/api/v1/runs/" + runId + "/preview", null, 204);
        call(vic, "GET", "/api/v1/runs/" + runId + "/preview", null, 404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void siteBuiltForTheRootPathIsRewrittenAndOldPreviewsExpire() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        String build = "mkdir -p dist/assets dist/docs && printf '<html><head><title>t</title></head><body>"
                + "<script type=\"module\" src=\"/assets/app.js\"></script><a href=\"/about\">a</a></body></html>'"
                + " > dist/index.html && printf 'import(\"/assets/chunk.js\"); fetch(\"/api/x\"); history.pushState({}, \"\", \"/x\")'"
                + " > dist/assets/app.js && printf 'body{background:url(/img/bg.png)}' > dist/assets/app.css"
                + " && echo docs > dist/docs/index.html";
        Map<String, Object> node = new HashMap<>(Map.of("id", "site", "type", "preview", "script", build));
        node.put("preview", Map.of("path", "dist", "spa", true));
        call(
                admin,
                "PUT",
                "/api/v1/workflows/site",
                Map.of("name", "站点", "status", "published", "projectId", "p1", "nodes", List.of(node)),
                200);
        String runId = settledRun(admin, "site");
        assertThat((Map<String, Object>) call(admin, "GET", "/api/v1/runs/" + runId + "/preview", null, 200))
                .containsEntry("status", "ready")
                .containsEntry("fileCount", 4);
        String url = (String)
                ((Map<String, Object>) call(admin, "POST", "/api/v1/runs/" + runId + "/preview/session", null, 200))
                        .get("url");
        String prefix = URI.create(url).getPath().replaceAll("/$", "");

        String html = open(url).body();
        assertThat(html.indexOf("<base href=\"" + prefix + "/\">"))
                .isPositive()
                .isLessThan(html.indexOf("<script type=\"module\""));
        assertThat(html)
                .contains("src=\"" + prefix + "/assets/app.js\"")
                .contains("href=\"" + prefix + "/about\"")
                .contains("var routed = true;");
        HttpResponse<String> js = open(url + "assets/app.js");
        assertThat(js.headers().firstValue("Content-Type"))
                .hasValueSatisfying(t -> assertThat(t).startsWith("text/javascript"));
        assertThat(js.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(c -> assertThat(c).contains("immutable"));
        assertThat(js.body())
                .contains("import(\"" + prefix + "/assets/chunk.js\")")
                .contains("fetch(\"/api/x\")");
        assertThat(open(url + "assets/app.css").body()).contains("url(" + prefix + "/img/bg.png)");
        // client-side routes fall back to the entry page; files do not
        assertThat(open(url + "settings/profile", "Accept", "text/html").body()).contains("<base href=");
        assertThat(open(url + "missing.png", "Accept", "image/png").statusCode())
                .isEqualTo(404);
        // directories redirect so that relative links resolve
        HttpResponse<String> bare = open(prefix);
        assertThat(bare.statusCode()).isEqualTo(302);
        assertThat(bare.headers().firstValue("Location")).hasValue(prefix + "/");
        assertThat(open(url + "docs").headers().firstValue("Location")).hasValue(prefix + "/docs/");
        assertThat(open(url + "docs/").body()).endsWith("docs\n");

        // a workspace without a frontend skips the contract's preview; nothing can be opened
        call(admin, "PUT", "/api/v1/workflows/be", contractWorkflow("echo 'package main' > main.go", 3600), 200);
        String backendRun = settledRun(admin, "be");
        Map<String, Object> skipped = call(admin, "GET", "/api/v1/runs/" + backendRun + "/preview", null, 200);
        assertThat(skipped).containsEntry("status", "skipped");
        assertThat((String) skipped.get("error")).contains("工作区没有前端工程");
        call(admin, "POST", "/api/v1/runs/" + backendRun + "/preview/session", null, 409);
        call(admin, "GET", "/api/v1/runs/" + backendRun + "/preview/download", null, 409);

        // previews past their retention expire: links stop working, the zip is gone
        call(
                admin,
                "PUT",
                "/api/v1/workflows/short",
                contractWorkflow("echo '{\"dependencies\": {\"react\": \"18\"}}' > package.json", 1),
                200);
        String shortRun = settledRun(admin, "short");
        String shortUrl = (String)
                ((Map<String, Object>) call(admin, "POST", "/api/v1/runs/" + shortRun + "/preview/session", null, 200))
                        .get("url");
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            service.sweep();
            return "expired"
                    .equals(((Map<String, Object>)
                                    call(admin, "GET", "/api/v1/runs/" + shortRun + "/preview", null, 200))
                            .get("status"));
        });
        assertThat(open(shortUrl).statusCode()).isEqualTo(403);
        call(admin, "GET", "/api/v1/runs/" + shortRun + "/preview/download", null, 410);

        // the preview and its links go with their run
        call(admin, "DELETE", "/api/v1/runs/" + runId, null, 204);
        assertThat(open(url).statusCode()).isEqualTo(403);
    }

    static List<String> entries(byte[] zip) throws Exception {
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.add(e.getName());
            }
        }
        return entries;
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
