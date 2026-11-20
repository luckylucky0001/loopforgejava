package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.store.InstanceRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Iteration 4 operations: instance registry, leases released after settling, Prometheus metrics. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpsIntegrationTest {

    static final String TOKEN = "ops-test-token-0123456789-abcdefghijkl";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-ops-it");
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:ops;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.instance-id", () -> "ops-instance-1");
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    InstanceRegistry instances;

    @Test
    void instanceHeartbeatsAndMetricsAreExported() {
        RestClient api = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        assertThat(instances.find("ops-instance-1"))
                .get()
                .satisfies(i -> assertThat(i.pid()).isPositive());

        api.put()
                .uri("/api/v1/workflows/two")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "status",
                        "published",
                        "nodes",
                        List.of(
                                Map.of("id", "a", "type", "script", "script", "echo a"),
                                Map.of("id", "b", "type", "script", "script", "exit 3")),
                        "edges",
                        List.of(Map.of("source", "a", "target", "b"))))
                .retrieve()
                .toBodilessEntity();
        String runId = (String) api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workflowId", "two"))
                .retrieve()
                .body(MAP)
                .get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "failed"
                        .equals(api.get()
                                .uri("/api/v1/runs/{id}", runId)
                                .retrieve()
                                .body(MAP)
                                .get("status")));
        Map<String, Object> run =
                api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(MAP);
        assertThat(run).doesNotContainKey("ownerInstanceId").containsKey("workspaceHost");

        String metrics = api.get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(metrics)
                .contains("loopforge_runs_finished_total{status=\"failed\",workflow=\"two\"} 1.0")
                .contains("loopforge_nodes_finished_total{status=\"succeeded\",type=\"script\"}")
                .contains("loopforge_nodes_finished_total{status=\"failed\",type=\"script\"}")
                .contains("loopforge_node_duration_seconds_count")
                .contains("loopforge_runs_active");
    }
}
