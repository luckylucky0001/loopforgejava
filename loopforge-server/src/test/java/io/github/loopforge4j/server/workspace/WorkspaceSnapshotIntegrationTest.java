package io.github.loopforge4j.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.json.Json;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Finished workspaces are snapshotted before release, downloadable, and restored on replay. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceSnapshotIntegrationTest {

    static final String TOKEN = "workspace-snapshot-token-0123456789-ab";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-snapshot-it");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:workspace-snapshots;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

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

    RestClient member(RestClient admin, String name, String project) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        call(
                admin,
                "PUT",
                "/api/v1/projects/" + project + "/members/" + created.get("id"),
                Map.of("role", "developer"),
                200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return client((String) login.get("token"));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> succeeded(RestClient c, String id) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        return call(c, "GET", "/api/v1/runs/" + id, null, 200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void releasedWorkspacesLeaveASnapshotThatReplaysRestore() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        call(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        RestClient lily = member(admin, "lily", "p1");
        RestClient mark = member(admin, "mark", "p2");
        call(
                admin,
                "PUT",
                "/api/v1/workflows/notes",
                Map.of(
                        "name", "notes",
                        "status", "published",
                        "projectId", "p1",
                        "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "write",
                                                "type",
                                                "script",
                                                "script",
                                                "[ -f notes.txt ] || { echo first > notes.txt; echo SECRET=1 > .env;"
                                                        + " mkdir -p node_modules/x && echo dep > node_modules/x/i.js; }"),
                                        Map.of("id", "read", "type", "script", "script", "cat notes.txt; ls -a")),
                        "edges", List.of(Map.of("source", "write", "target", "read"))),
                200);
        Map<String, Object> run =
                call(lily, "POST", "/api/v1/runs", Map.of("workflowId", "notes", "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        succeeded(lily, id);

        Map<String, Object> workspace = org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(
                        () -> (Map<String, Object>) call(lily, "GET", "/api/v1/runs/" + id + "/workspace", null, 200),
                        w -> w.containsKey("snapshot"));
        assertThat(workspace).containsEntry("available", false);
        assertThat((Map<String, Object>) workspace.get("snapshot")).containsKey("savedAt");

        ResponseEntity<byte[]> download = lily.get()
                .uri("/api/v1/runs/" + id + "/workspace/snapshot")
                .retrieve()
                .toEntity(byte[].class);
        assertThat(download.getStatusCode().value()).isEqualTo(200);
        assertThat(download.getHeaders().getContentType()).hasToString("application/zip");
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.getBody()))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).contains("notes.txt").doesNotContain(".env", ".loopforge-snapshot.json");
        assertThat(names).noneMatch(n -> n.startsWith("node_modules") || n.startsWith(".loopforge"));
        assertThat(mark.get()
                        .uri("/api/v1/runs/" + id + "/workspace/snapshot")
                        .retrieve()
                        .toEntity(byte[].class)
                        .getStatusCode()
                        .value())
                .isIn(403, 404);

        // replaying a node brings the workspace back: the earlier file is there
        call(lily, "POST", "/api/v1/runs/" + id + "/retry", Map.of("nodeId", "read"), 200);
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> ((Map<String, Object>) ((Map<String, Object>)
                                        ((Map<String, Object>) call(lily, "GET", "/api/v1/runs/" + id, null, 200))
                                                .get("nodes"))
                                .get("read"))
                        .get("attempt")
                        .equals(2));
        Map<String, Object> replayed = succeeded(lily, id);
        String output = (String)
                ((Map<String, Object>) ((Map<String, Object>) replayed.get("nodes")).get("read")).get("output");
        assertThat(output).contains("first").contains(".env");

        call(admin, "DELETE", "/api/v1/runs/" + id, null, 204);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM run_workspace_snapshots")
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
