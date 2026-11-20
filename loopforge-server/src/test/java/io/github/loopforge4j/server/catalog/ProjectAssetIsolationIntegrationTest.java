package io.github.loopforge4j.server.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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

/**
 * With members kept to isolated sandboxes (the default), what a project's maintainers write (its
 * roles) never runs outside one, whoever starts the run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectAssetIsolationIntegrationTest {

    static final String TOKEN = "project-asset-token-0123456789-abcdef";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        var data = Files.createTempDirectory("lf-asset-isolation-it");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:asset-isolation;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    @SuppressWarnings("unchecked")
    <T> T call(String method, String path, Object body, int status) {
        var spec = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build()
                .method(HttpMethod.valueOf(method))
                .uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> r = spec.retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        return r.getBody() == null || r.getBody().isBlank() ? null : (T) Json.read(r.getBody(), Object.class);
    }

    static Map<String, Object> sop(String project, String agent, boolean generic) {
        Map<String, Object> sop = new java.util.HashMap<>(Map.of(
                "name",
                "sop",
                "status",
                "published",
                "generic",
                generic,
                "nodes",
                List.of(Map.of("id", "a", "type", "agent", "agentId", agent))));
        if (project != null) {
            sop.put("projectId", project);
        }
        return sop;
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectRolesRunOnlyInIsolatedSandboxes() {
        call("PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        call("PUT", "/api/v1/agents/dev", Map.of("name", "开发", "prompt", "写代码", "executorId", "mock"), 200);
        call("PUT", "/api/v1/workflows/tpl", sop(null, "dev", true), 200);

        // adopting a local template needs an isolated sandbox for the copy, even for an administrator
        Map<String, Object> refused =
                call("POST", "/api/v1/workflow-templates/tpl/adopt", Map.of("projectId", "p1"), 422);
        assertThat(Json.write(refused)).contains("隔离沙箱");
        Map<String, Object> adopted = call(
                "POST",
                "/api/v1/workflow-templates/tpl/adopt",
                Map.of("projectId", "p1", "sandboxProfileId", "docker"),
                201);
        assertThat(adopted).containsEntry("sandboxProfileId", "docker");

        // a project role in a local SOP fails before it runs, whoever starts it; shared roles still run
        call("PUT", "/api/v1/workflows/own", sop("p1", "dev-p1", false), 200);
        Map<String, Object> run =
                call("POST", "/api/v1/runs", Map.of("workflowId", "own", "projectId", "p1", "input", "x"), 201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "failed"
                        .equals(((Map<String, Object>) call("GET", "/api/v1/runs/" + run.get("id"), null, 200))
                                .get("status")));
        Map<String, Object> failed = call("GET", "/api/v1/runs/" + run.get("id"), null, 200);
        assertThat((String) ((Map<String, Object>) ((Map<String, Object>) failed.get("nodes")).get("a")).get("error"))
                .contains("只能在隔离沙箱中运行");
        call("PUT", "/api/v1/workflows/shared", sop("p1", "dev", false), 200);
        Map<String, Object> plain =
                call("POST", "/api/v1/runs", Map.of("workflowId", "shared", "projectId", "p1", "input", "x"), 201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) call("GET", "/api/v1/runs/" + plain.get("id"), null, 200))
                                .get("status")));
    }
}
