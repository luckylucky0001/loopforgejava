package io.github.loopforge4j.server.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
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

/** A shell in a run's workspace: for its operators, only while no node runs, theirs alone. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceTerminalIntegrationTest {

    static final String TOKEN = "terminal-token-0123456789-abcdefghij";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-terminal-it");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:terminal;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
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

    String member(RestClient admin, String name, String role) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + created.get("id"), Map.of("role", role), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return (String) login.get("token");
    }

    @SuppressWarnings("unchecked")
    String run(RestClient c, String workflow, List<Map<String, Object>> nodes, String until) {
        call(
                c,
                "PUT",
                "/api/v1/workflows/" + workflow,
                Map.of(
                        "name",
                        workflow,
                        "status",
                        "published",
                        "projectId",
                        "p1",
                        "nodes",
                        nodes,
                        "edges",
                        nodes.size() > 1
                                ? List.of(Map.of(
                                        "source",
                                        nodes.get(0).get("id"),
                                        "target",
                                        nodes.get(1).get("id")))
                                : List.of()),
                200);
        Map<String, Object> run =
                call(c, "POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> until.equals(
                        ((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        return id;
    }

    String until(RestClient c, String path, String id, String expected) throws Exception {
        StringBuilder all = new StringBuilder();
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            Map<String, Object> r = call(c, "POST", path, Map.of("operation", "read", "id", id), 200);
            all.append(new String(Base64.getDecoder().decode((String) r.get("data")), StandardCharsets.UTF_8));
            if (all.toString().contains(expected) || Boolean.TRUE.equals(r.get("exited"))) {
                break;
            }
            Thread.sleep(150);
        }
        return all.toString();
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorsGetAShellInAWorkspaceNoAgentIsUsing() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        RestClient dev = client(member(admin, "dev", "developer"));
        RestClient ops = client(member(admin, "ops", "developer"));
        RestClient vic = client(member(admin, "vic", "viewer"));

        String waiting = run(
                admin,
                "review",
                List.of(
                        Map.of("id", "work", "type", "script", "script", "echo built > out.txt"),
                        Map.of("id", "review", "type", "approval")),
                "waiting");
        String path = "/api/v1/runs/" + waiting + "/workspace/terminal";

        // viewers may look at the run but not get a shell in it
        call(vic, "POST", path, Map.of("operation", "open"), 403);

        Map<String, Object> opened = call(dev, "POST", path, Map.of("operation", "open", "cols", 90, "rows", 25), 200);
        String id = (String) opened.get("id");
        call(
                dev,
                "POST",
                path,
                Map.of(
                        "operation",
                        "input",
                        "id",
                        id,
                        "data",
                        "cat out.txt; stty size; echo \"rid=$LOOPFORGE_RUN_ID\"; echo end-$((40+2))\n"),
                200);
        // the prompt shows the workspace path (and so the run id): wait for computed output
        String out = until(dev, path, id, "end-42");
        assertThat(out)
                .contains("built")
                .contains("25 90")
                .contains("rid=" + waiting)
                .contains("end-42");

        // the shell is the opener's alone
        call(ops, "POST", path, Map.of("operation", "read", "id", id), 409);
        call(ops, "POST", path, Map.of("operation", "input", "id", id, "data", "rm -rf .\n"), 409);

        // what the shell changes is in the workspace, and the run notes who opened it
        call(
                dev,
                "POST",
                path,
                Map.of("operation", "input", "id", id, "data", "echo fixed > fix.txt; echo done-$((6*7))\n"),
                200);
        assertThat(until(dev, path, id, "done-42")).contains("done-42");
        Map<String, Object> file =
                call(dev, "GET", "/api/v1/runs/" + waiting + "/workspace/file?path=fix.txt", null, 200);
        assertThat((String) file.get("content")).isEqualTo("fixed\n");
        List<Map<String, Object>> events = call(admin, "GET", "/api/v1/runs/" + waiting + "/events", null, 200);
        assertThat(events).anySatisfy(e -> assertThat(e).containsEntry("type", "workspace.terminal"));

        call(dev, "POST", path, Map.of("operation", "close", "id", id), 200);
        call(dev, "POST", path, Map.of("operation", "read", "id", id), 409);

        // never while an agent or script is at work
        String busy =
                run(admin, "busy", List.of(Map.of("id", "work", "type", "script", "script", "sleep 30")), "running");
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            Map<String, Object> r = call(admin, "GET", "/api/v1/runs/" + busy, null, 200);
            return "running"
                    .equals(((Map<String, Object>) ((Map<String, Object>) r.get("nodes")).get("work")).get("status"));
        });
        Map<String, Object> refused =
                call(dev, "POST", "/api/v1/runs/" + busy + "/workspace/terminal", Map.of("operation", "open"), 409);
        assertThat((String) refused.get("detail")).contains("正在运行");
        call(admin, "POST", "/api/v1/runs/" + busy + "/cancel", null, 200);
    }
}
