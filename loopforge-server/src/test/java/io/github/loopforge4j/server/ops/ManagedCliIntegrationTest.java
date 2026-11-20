package io.github.loopforge4j.server.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * Agents run the managed CLI version their run pinned (here a pre-provisioned fake Claude Code);
 * administrators see the inventory. Nothing is downloaded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagedCliIntegrationTest {

    static final String TOKEN = "managed-cli-token-0123456789-abcdefgh";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-managed-cli-it");
            Path root = data.resolve("managed");
            Path bin = Files.createDirectories(root.resolve("providers/claude-code/2.1.0-abc/node_modules/.bin"));
            Path cli = bin.resolve("claude");
            Files.writeString(cli, """
                    #!/bin/sh
                    cat > /dev/null
                    echo "managed 2.1.0" > which.txt
                    echo '{"type":"system","subtype":"init","session_id":"s1"}'
                    echo '{"type":"result","session_id":"s1","is_error":false,"result":"done","usage":{"input_tokens":1,"output_tokens":1}}'
                    """);
            cli.toFile().setExecutable(true);
            Files.writeString(
                    root.resolve("state.json"),
                    Json.write(Map.of(
                            "current",
                            Map.of(
                                    "claude-code",
                                    Map.of(
                                            "version",
                                            "2.1.0",
                                            "binary",
                                            cli.toString(),
                                            "integrity",
                                            "sha512-x",
                                            "installedAt",
                                            Instant.now().toString())))));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:managed-cli;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.managed-cli.enabled", () -> "true");
        registry.add("loopforge.managed-cli.root", () -> data.resolve("managed").toString());
        registry.add("loopforge.managed-cli.daily-check", () -> "false");
        // no PATH from the host: a real agent CLI installed there can never run instead
        registry.add("loopforge.sandbox.passthrough-env", () -> "LANG");
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
    void agentsUseTheManagedVersionTheirRunPinned() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        call(admin, "PUT", "/api/v1/agents/dev", Map.of("name", "开发", "prompt", "写代码", "executorId", "claude"), 200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/sop",
                Map.of(
                        "name",
                        "sop",
                        "status",
                        "published",
                        "projectId",
                        "p1",
                        "nodes",
                        List.of(
                                Map.of("id", "a", "type", "agent", "agentId", "dev"),
                                Map.of("id", "review", "type", "approval")),
                        "edges",
                        List.of(Map.of("source", "a", "target", "review"))),
                200);
        Map<String, Object> run =
                call(admin, "POST", "/api/v1/runs", Map.of("workflowId", "sop", "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of("waiting", "failed")
                        .contains(((Map<String, Object>) call(admin, "GET", "/api/v1/runs/" + id, null, 200))
                                .get("status")));
        Map<String, Object> done = call(admin, "GET", "/api/v1/runs/" + id, null, 200);
        assertThat(done).as(() -> Json.write(done.get("nodes"))).containsEntry("status", "waiting");
        Map<String, Object> which =
                call(admin, "GET", "/api/v1/runs/" + id + "/workspace/file?path=which.txt", null, 200);
        assertThat((String) which.get("content")).isEqualTo("managed 2.1.0\n");

        Map<String, Object> view = call(admin, "GET", "/api/v1/admin/managed-clis", null, 200);
        assertThat(view).containsEntry("enabled", true);
        assertThat((Map<String, Object>) ((Map<String, Object>) view.get("current")).get("claude-code"))
                .containsEntry("version", "2.1.0");
        assertThat(view).containsKey("nextCheckAt");
        Map<String, Object> refused = call(admin, "POST", "/api/v1/admin/managed-clis/claude-code/rollback", null, 409);
        assertThat((String) refused.get("detail")).contains("no previous");
        call(admin, "POST", "/api/v1/admin/managed-clis/vim/rollback", null, 400);

        // members cannot see or change it
        Map<String, Object> user =
                call(admin, "POST", "/api/v1/users", Map.of("username", "mia", "password", "mia-password-1"), 201);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", "mia", "password", "mia-password-1"),
                200);
        assertThat(user).isNotNull();
        RestClient mia = client((String) login.get("token"));
        call(mia, "GET", "/api/v1/admin/managed-clis", null, 403);
        call(mia, "POST", "/api/v1/admin/managed-clis/update", Map.of(), 403);
    }
}
