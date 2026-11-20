package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** The web console is public static content with a strict CSP; its data still needs a token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConsoleTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-console");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:console;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> "console-test-token-0123456789-abcdefgh");
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    @Test
    void consoleIsServedWithACspAndTheApiStaysProtected() {
        RestClient anonymous = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        ResponseEntity<String> index = anonymous.get().uri("/").retrieve().toEntity(String.class);
        assertThat(index.getStatusCode().value()).isEqualTo(200);
        assertThat(index.getBody()).contains("/console/app.js");
        assertThat(index.getHeaders().getFirst("Content-Security-Policy"))
                .contains("script-src 'self'", "frame-ancestors 'none'");
        ResponseEntity<String> script =
                anonymous.get().uri("/console/app.js").retrieve().toEntity(String.class);
        assertThat(script.getStatusCode().value()).isEqualTo(200);
        assertThat(script.getBody()).doesNotContainPattern("\\.(innerHTML|outerHTML)\\s*=|insertAdjacentHTML");
        assertThat(anonymous
                        .get()
                        .uri("/api/v1/runs")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(anonymous
                        .get()
                        .uri("/api/v1/auth/status")
                        .retrieve()
                        .toEntity(String.class)
                        .getBody())
                .contains("setupRequired");
    }
}
