package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Full stack: HTTP API, security, JDBC persistence, engine and the bundled demo catalog. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTest {

    static final String TOKEN = "test-token-0123456789-abcdefghijklmnop";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:api;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
    }

    @LocalServerPort
    int port;

    RestClient api;
    RestClient anonymous;

    @BeforeEach
    void setUp() {
        anonymous = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        api = anonymous
                .mutate()
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .build();
    }

    @Test
    void requestsWithoutTokenAreRejectedButHealthIsPublic() {
        ResponseEntity<String> denied =
                anonymous.get().uri("/api/v1/workflows").retrieve().toEntity(String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(401);
        assertThat(denied.getHeaders().getFirst("WWW-Authenticate")).isEqualTo("Bearer");
        ResponseEntity<String> wrong = anonymous
                .get()
                .uri("/api/v1/workflows")
                .header("Authorization", "Bearer nope")
                .retrieve()
                .toEntity(String.class);
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        ResponseEntity<Map<String, Object>> health =
                anonymous.get().uri("/actuator/health").retrieve().toEntity(MAP);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).containsEntry("status", "UP").doesNotContainKey("components");
        assertThat(anonymous
                        .get()
                        .uri("/v3/api-docs")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    @Test
    void demoCatalogIsImported() {
        List<Map<String, Object>> workflows =
                api.get().uri("/api/v1/workflows").retrieve().body(LIST);
        assertThat(workflows)
                .extracting(w -> w.get("id"))
                .contains("welcome-workflow", "demo-delivery", "claude-bugfix");
        List<Map<String, Object>> executors =
                api.get().uri("/api/v1/executors").retrieve().body(LIST);
        assertThat(executors).extracting(e -> e.get("id")).contains("claude", "codex", "codebuddy", "mock");
    }

    @Test
    void welcomeSopRunsToCompletionAfterApproval() {
        String runId = start("welcome-workflow", "验收");
        Map<String, Object> waiting = awaitStatus(runId, "waiting");
        assertThat(nodeStatus(waiting, "approve")).isEqualTo("waiting_approval");

        ResponseEntity<Map<String, Object>> approved = api.post()
                .uri("/api/v1/runs/{id}/nodes/approve/approve", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("humanContext", "已确认"))
                .retrieve()
                .toEntity(MAP);
        assertThat(approved.getStatusCode().value()).isEqualTo(200);
        awaitStatus(runId, "succeeded");

        List<Map<String, Object>> artifacts =
                api.get().uri("/api/v1/runs/{id}/artifacts", runId).retrieve().body(LIST);
        assertThat(artifacts)
                .extracting(a -> a.get("name"))
                .containsExactly("welcome", "human-context", "decision", "completed");
        assertThat(artifacts).allMatch(a -> !a.containsKey("content"));
        ResponseEntity<String> body = api.get()
                .uri(
                        "/api/v1/runs/{id}/artifacts/{artifact}",
                        runId,
                        artifacts.getFirst().get("id"))
                .retrieve()
                .toEntity(String.class);
        assertThat(body.getHeaders().getContentType()).isNotNull();
        assertThat(body.getHeaders().getContentType().getType()).isEqualTo("text");
        assertThat(body.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(body.getBody()).contains("已验证");

        ResponseEntity<Map<String, Object>> conflict = api.post()
                .uri("/api/v1/runs/{id}/nodes/approve/approve", runId)
                .retrieve()
                .toEntity(MAP);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody()).containsEntry("title", "Operation not allowed in the current state");
    }

    @Test
    void mockDeliverySopShowsReworkLoop() {
        String runId = start("demo-delivery", "增加验证码");
        Map<String, Object> waiting = awaitStatus(runId, "waiting");
        @SuppressWarnings("unchecked")
        Map<String, Object> implement =
                (Map<String, Object>) ((Map<String, Object>) waiting.get("nodes")).get("implement");
        assertThat(implement).containsEntry("reworkCount", 1).containsEntry("attempt", 2);

        api.post()
                .uri("/api/v1/runs/{id}/nodes/accept/reject", runId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "需要回滚方案"))
                .retrieve()
                .toBodilessEntity();
        awaitStatus(runId, "waiting");
        api.post()
                .uri("/api/v1/runs/{id}/nodes/accept/approve", runId)
                .retrieve()
                .toBodilessEntity();
        awaitStatus(runId, "succeeded");

        List<Map<String, Object>> events = api.get()
                .uri("/api/v1/runs/{id}/events?limit=1000", runId)
                .retrieve()
                .body(LIST);
        assertThat(events).extracting(e -> e.get("type")).contains("gate.rejected", "gate.approved", "run.succeeded");
    }

    @Test
    void invalidRequestsAreProblemDetails() {
        ResponseEntity<Map<String, Object>> invalid = api.put()
                .uri("/api/v1/workflows/broken")
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        "{\"nodes\":[{\"id\":\"a\",\"type\":\"script\"}],\"edges\":[{\"source\":\"a\",\"target\":\"b\"}]}")
                .retrieve()
                .toEntity(MAP);
        assertThat(invalid.getStatusCode().value()).isEqualTo(422);
        assertThat(invalid.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat((List<?>) invalid.getBody().get("problems")).hasSize(2);

        ResponseEntity<Map<String, Object>> malformed = api.put()
                .uri("/api/v1/workflows/broken")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"nodes\":[{\"id\":\"a\",\"type\":\"robot\"}]}")
                .retrieve()
                .toEntity(MAP);
        assertThat(malformed.getStatusCode().value()).isEqualTo(400);
        assertThat(malformed.getBody().get("detail").toString()).contains("robot");

        ResponseEntity<Map<String, Object>> missingField = api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("input", "x"))
                .retrieve()
                .toEntity(MAP);
        assertThat(missingField.getStatusCode().value()).isEqualTo(400);

        assertThat(api.get()
                        .uri("/api/v1/runs/nope")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(api.post()
                        .uri("/api/v1/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("workflowId", "ghost"))
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void workflowCrudAndValidation() {
        String body = "{\"name\":\"临时\",\"nodes\":[{\"id\":\"a\",\"type\":\"script\",\"script\":\"echo hi\"}]}";
        Map<String, Object> saved = api.put()
                .uri("/api/v1/workflows/tmp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(MAP);
        assertThat(saved).containsEntry("id", "tmp").containsEntry("revision", 1);
        Map<String, Object> validation = api.post()
                .uri("/api/v1/workflows/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"id\":\"x\",\"nodes\":[{\"id\":\"a\",\"type\":\"agent\"}]}")
                .retrieve()
                .body(MAP);
        assertThat(validation).containsEntry("valid", false);
        assertThat(api.delete()
                        .uri("/api/v1/workflows/tmp")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(api.get()
                        .uri("/api/v1/workflows/tmp")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    @Test
    void catalogExportCanBeReimported() {
        Map<String, Object> bundle =
                api.get().uri("/api/v1/catalog/export").retrieve().body(MAP);
        assertThat((List<?>) bundle.get("workflows")).isNotEmpty();
        Map<String, Object> result = api.post()
                .uri("/api/v1/catalog/import")
                .contentType(MediaType.APPLICATION_JSON)
                .body(bundle)
                .retrieve()
                .body(MAP);
        assertThat((List<?>) result.get("written")).isEmpty();
    }

    @Test
    void streamDeliversEventsAndSnapshotsUntilTerminal() throws Exception {
        String runId = start("welcome-workflow", "stream");
        awaitStatus(runId, "waiting");
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/runs/" + runId + "/stream?access_token=" + TOKEN))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        api.post()
                .uri("/api/v1/runs/{id}/nodes/approve/approve", runId)
                .retrieve()
                .toBodilessEntity();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        assertThat(lines).contains("event:event", "event:run");
        assertThat(lines).anyMatch(l -> l.startsWith("data:") && l.contains("\"run.succeeded\""));
        assertThat(lines).anyMatch(l -> l.startsWith("id:"));
    }

    @Test
    void streamRequiresToken() {
        ResponseEntity<String> denied =
                anonymous.get().uri("/api/v1/runs/x/stream").retrieve().toEntity(String.class);
        assertThat(denied.getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> queryTokenElsewhere = anonymous
                .get()
                .uri("/api/v1/workflows?access_token=" + TOKEN)
                .retrieve()
                .toEntity(String.class);
        assertThat(queryTokenElsewhere.getStatusCode().value()).isEqualTo(401);
    }

    private String start(String workflowId, String input) {
        ResponseEntity<Map<String, Object>> created = api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workflowId", workflowId, "input", input))
                .retrieve()
                .toEntity(MAP);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        return created.getBody().get("id").toString();
    }

    private Map<String, Object> awaitStatus(String runId, String status) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> status.equals(api.get()
                        .uri("/api/v1/runs/{id}", runId)
                        .retrieve()
                        .body(MAP)
                        .get("status")));
        return api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(MAP);
    }

    @SuppressWarnings("unchecked")
    private static String nodeStatus(Map<String, Object> run, String nodeId) {
        return ((Map<String, Map<String, Object>>) run.get("nodes"))
                .get(nodeId)
                .get("status")
                .toString();
    }
}
