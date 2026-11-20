package io.github.loopforge4j.server.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.browser.CdpBrowser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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

/** A preview whose settings ask for it is screenshotted by a real headless browser (skipped without one). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PreviewScreenshotIntegrationTest {

    static final String TOKEN = "preview-shot-token-0123456789-abcdef";

    @BeforeAll
    static void browser() {
        Assumptions.assumeTrue(CdpBrowser.detect().isPresent(), "no Chromium or Chrome installed");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-preview-shot-it");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:preview-shot;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        // CI hosts often forbid the user namespaces Chromium's own sandbox needs
        registry.add("loopforge.browser.no-sandbox", () -> "true");
    }

    @LocalServerPort
    int port;

    RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @SuppressWarnings("unchecked")
    <T> T call(String method, String path, Object body, int status) {
        var spec = client().method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> r = spec.retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        return r.getBody() == null || r.getBody().isBlank() ? null : (T) Json.read(r.getBody(), Object.class);
    }

    @SuppressWarnings("unchecked")
    String run(String workflow) {
        Map<String, Object> run =
                call("POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) call("GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        return id;
    }

    @Test
    @SuppressWarnings("unchecked")
    void readySitesAreScreenshottedWithoutReachingAnythingElse() throws Exception {
        call("PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        // the page also tries to load something from another site: the browser must not fetch it
        String build = "mkdir -p dist && printf '%s' '<html><body style=\"background:#0a7\"><h1>截图</h1>"
                + "<img src=\"http://example.invalid/x.png\"></body></html>' > dist/index.html";
        Map<String, Object> node = new HashMap<>(Map.of("id", "site", "type", "preview", "script", build));
        node.put("preview", Map.of("path", "dist", "screenshot", true));
        call(
                "PUT",
                "/api/v1/workflows/shot",
                Map.of("name", "截图", "status", "published", "projectId", "p1", "nodes", List.of(node)),
                200);
        String runId = run("shot");

        await().atMost(Duration.ofSeconds(60))
                .until(() -> !"pending"
                        .equals(((Map<String, Object>) call("GET", "/api/v1/runs/" + runId + "/preview", null, 200))
                                .get("screenshot")));
        Map<String, Object> preview = call("GET", "/api/v1/runs/" + runId + "/preview", null, 200);
        assertThat(preview).as(Json.write(preview)).containsEntry("screenshot", "ready");
        ResponseEntity<byte[]> png = client().get()
                .uri("/api/v1/runs/" + runId + "/preview/screenshot")
                .retrieve()
                .toEntity(byte[].class);
        assertThat(png.getStatusCode().value()).isEqualTo(200);
        assertThat(png.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(Arrays.copyOf(png.getBody(), 4)).isEqualTo(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        // the site itself was rendered (its background), at the documented size
        java.awt.image.BufferedImage image =
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png.getBody()));
        assertThat(image.getWidth()).isEqualTo(PreviewScreenshots.WIDTH);
        assertThat(image.getRGB(PreviewScreenshots.WIDTH - 10, PreviewScreenshots.HEIGHT - 10) & 0xffffff)
                .isEqualTo(0x00aa77);

        // the browser's own preview link is revoked once it is done; taking it again works
        call("POST", "/api/v1/runs/" + runId + "/preview/screenshot", null, 202);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> "ready"
                        .equals(((Map<String, Object>) call("GET", "/api/v1/runs/" + runId + "/preview", null, 200))
                                .get("screenshot")));

        // a site without the setting has none
        node.put("preview", Map.of("path", "dist"));
        call(
                "PUT",
                "/api/v1/workflows/plain",
                Map.of("name", "无截图", "status", "published", "projectId", "p1", "nodes", List.of(node)),
                200);
        String plain = run("plain");
        assertThat((Map<String, Object>) call("GET", "/api/v1/runs/" + plain + "/preview", null, 200))
                .doesNotContainKey("screenshot");
        call("GET", "/api/v1/runs/" + plain + "/preview/screenshot", null, 404);
    }
}
