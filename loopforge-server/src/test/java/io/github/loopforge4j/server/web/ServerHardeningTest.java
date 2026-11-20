package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.store.CatalogStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Regression tests for the server review: input limits, validation, races, error details. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerHardeningTest {

    static final String TOKEN = "hardening-token-0123456789-abcdefghijkl";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:hardening;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.security.max-request-size", () -> "1MB");
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
    }

    @LocalServerPort
    int port;

    @Autowired
    CatalogStore catalog;

    @Autowired
    List<ApplicationRunner> runners;

    RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @Test
    void overlongIdsAndNamesAreRejectedAsInvalidDefinitions() {
        String longId = "a".repeat(201);
        assertThat(putWorkflow(longId, "{\"nodes\":[{\"id\":\"n\",\"type\":\"script\",\"script\":\"true\"}]}"))
                .isEqualTo(422);
        assertThat(putWorkflow(
                        "ok-id", "{\"nodes\":[{\"id\":\"" + longId + "\",\"type\":\"script\",\"script\":\"true\"}]}"))
                .isEqualTo(422);
        assertThat(putWorkflow(
                        "ok-id",
                        "{\"name\":\"" + "名".repeat(501)
                                + "\",\"nodes\":[{\"id\":\"n\",\"type\":\"script\",\"script\":\"true\"}]}"))
                .isEqualTo(422);
        assertThat(putWorkflow(
                        "ok-id",
                        "{\"nodes\":[{\"id\":\"n\",\"type\":\"script\",\"script\":\"true\","
                                + "\"artifactOutputs\":[{\"name\":\"a\",\"path\":\"a.md\",\"mediaType\":\""
                                + "x".repeat(201) + "\"}]}]}"))
                .isEqualTo(422);
        assertThat(putWorkflow("bad/../id", "{}")).isBetween(400, 499); // firewall rejects the encoded slash
        ResponseEntity<Map<String, Object>> agent = api.put()
                .uri("/api/v1/agents/" + longId)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"prompt\":\"x\"}")
                .retrieve()
                .toEntity(MAP);
        assertThat(agent.getStatusCode().value()).isEqualTo(422);
        assertThat(catalog.workflow("ok-id")).isEmpty();
    }

    @Test
    void parameterRangeViolationsAreBadRequests() {
        for (String uri : List.of(
                "/api/v1/runs?limit=0",
                "/api/v1/runs?limit=501",
                "/api/v1/runs/x/events?after=-1",
                "/api/v1/runs/x/events?limit=0")) {
            ResponseEntity<Map<String, Object>> response =
                    api.get().uri(uri).retrieve().toEntity(MAP);
            assertThat(response.getStatusCode().value()).as(uri).isEqualTo(400);
        }
    }

    @Test
    void statusFilterAcceptsWireValues() {
        assertThat(api.get()
                        .uri("/api/v1/runs?status=running&status=waiting")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(api.get()
                        .uri("/api/v1/runs?status=bogus")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }

    @Test
    void concurrentPutsOfOneIdNeitherFailNorLoseRevisions() throws Exception {
        int writers = 8;
        String body = "{\"status\":\"published\",\"nodes\":[{\"id\":\"n\",\"type\":\"script\",\"script\":\"true\"}]}";
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                results.add(pool.submit(() -> putWorkflow("raced", body)));
            }
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
        }
        assertThat(statuses).containsOnly(200);
        assertThat(catalog.workflow("raced"))
                .get()
                .extracting(w -> w.revision())
                .isEqualTo(writers);
    }

    @Test
    void invalidImportWritesNothing() {
        String bundle = "{\"workflows\":[{\"id\":\"from-bundle\",\"nodes\":[{\"id\":\"n\",\"type\":\"script\","
                + "\"script\":\"true\"}]}],\"agents\":[{\"name\":\"no id\"}]}";
        ResponseEntity<Map<String, Object>> response = api.post()
                .uri("/api/v1/catalog/import")
                .contentType(MediaType.APPLICATION_JSON)
                .body(bundle)
                .retrieve()
                .toEntity(MAP);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(catalog.workflow("from-bundle")).isEmpty();
    }

    @Test
    void draftWorkflowsCannotBeStarted() {
        assertThat(putWorkflow("draft-wf", "{\"nodes\":[{\"id\":\"n\",\"type\":\"script\",\"script\":\"true\"}]}"))
                .isEqualTo(200);
        ResponseEntity<Map<String, Object>> response = api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workflowId", "draft-wf"))
                .retrieve()
                .toEntity(MAP);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().get("detail").toString()).contains("draft");
    }

    @Test
    void parserErrorsNameTheJsonPathButNoJavaTypes() {
        ResponseEntity<Map<String, Object>> response = api.put()
                .uri("/api/v1/workflows/typed")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"nodes\":\"not-a-list\"}")
                .retrieve()
                .toEntity(MAP);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        String detail = response.getBody().get("detail").toString();
        assertThat(detail).contains("nodes").doesNotContain("java.").doesNotContain("io.github");
    }

    @Test
    void oversizedBodiesAreRejectedBeforeParsing() throws Exception {
        String body = "{\"input\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}";
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/runs"))
                                .header("Authorization", "Bearer " + TOKEN)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void infoNeedsATokenAndPrometheusIsExposedToOperators() {
        RestClient anonymous = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        assertThat(anonymous
                        .get()
                        .uri("/actuator/info")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        ResponseEntity<String> metrics =
                api.get().uri("/actuator/prometheus").retrieve().toEntity(String.class);
        assertThat(metrics.getStatusCode().value()).isEqualTo(200);
        assertThat(metrics.getBody()).contains("jvm_");
    }

    @Test
    void demoCatalogIsNotReimportedAfterAnOperatorDeletedIt() throws Exception {
        assertThat(catalog.workflow("welcome-workflow")).isPresent();
        assertThat(catalog.deleteWorkflow("welcome-workflow")).isTrue();
        for (ApplicationRunner runner : runners) {
            if (runner.getClass().getSimpleName().startsWith("DemoCatalogImporter")) {
                runner.run(new DefaultApplicationArguments());
            }
        }
        assertThat(catalog.workflow("welcome-workflow")).isEmpty();
    }

    private int putWorkflow(String id, String body) {
        return api.put()
                .uri("/api/v1/workflows/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value();
    }
}
