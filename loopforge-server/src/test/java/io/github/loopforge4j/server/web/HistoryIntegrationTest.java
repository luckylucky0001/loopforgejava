package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Iteration 13: revision history of SOPs and roles (restore, publish, clone), the role delete
 * guard, run logs and deletion, schedule execution history.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HistoryIntegrationTest {

    static final String TOKEN = "history-test-token-0123456789-abcdef";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-history-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:history;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
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

    ResponseEntity<String> send(RestClient c, String method, String path, Object body) {
        var spec = c.method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }

    @SuppressWarnings("unchecked")
    <T> T call(RestClient c, String method, String path, Object body, int status) {
        ResponseEntity<String> r = send(c, method, path, body);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        if (r.getBody() == null || r.getBody().isBlank()) {
            return null;
        }
        return (T) Json.read(r.getBody(), Object.class);
    }

    String login(String user) {
        Map<String, Object> r = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", user, "password", user + "-password-1"),
                200);
        return (String) r.get("token");
    }

    static Map<String, Object> script(String id, String script) {
        return Map.of("id", id, "type", "script", "script", script);
    }

    static Map<String, Object> workflow(String name, String projectId, List<Map<String, Object>> nodes) {
        Map<String, Object> w = new java.util.HashMap<>(Map.of("name", name, "status", "published", "nodes", nodes));
        if (projectId != null) {
            w.put("projectId", projectId);
        }
        return w;
    }

    @Test
    @SuppressWarnings("unchecked")
    void sopAndRoleHistoryCanBeReadAndRestored() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        String maintainerId = (String) ((Map<String, Object>) call(
                        admin, "POST", "/api/v1/users", Map.of("username", "mia", "password", "mia-password-1"), 201))
                .get("id");
        String viewerId = (String) ((Map<String, Object>) call(
                        admin, "POST", "/api/v1/users", Map.of("username", "vic", "password", "vic-password-1"), 201))
                .get("id");
        call(admin, "PUT", "/api/v1/projects/p1/members/" + maintainerId, Map.of("role", "maintainer"), 200);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + viewerId, Map.of("role", "viewer"), 200);
        RestClient mia = client(login("mia"));
        RestClient vic = client(login("vic"));
        call(admin, "POST", "/api/v1/users", Map.of("username", "out", "password", "out-password-1"), 201);
        RestClient out = client(login("out"));

        // three saves: create, add a node, rename
        call(mia, "PUT", "/api/v1/workflows/flow", workflow("流程", "p1", List.of(script("a", "echo a"))), 200);
        call(
                mia,
                "PUT",
                "/api/v1/workflows/flow",
                workflow("流程", "p1", List.of(script("a", "echo a"), script("b", "echo b"))),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/flow",
                workflow("流程 v2", "p1", List.of(script("a", "echo a2"), script("b", "echo b"))),
                200);
        List<Map<String, Object>> revisions = call(vic, "GET", "/api/v1/workflows/flow/revisions", null, 200);
        assertThat(revisions).extracting(r -> r.get("revision")).containsExactly(3, 2, 1);
        assertThat(revisions).extracting(r -> r.get("author")).containsExactly("operator", "mia", "mia");
        assertThat(revisions.get(2)).containsEntry("summary", "创建").doesNotContainKey("payload");
        assertThat((String) revisions.get(1).get("summary")).contains("节点 +b");
        assertThat((String) revisions.get(0).get("summary")).contains("节点 ~a", "名称");
        call(out, "GET", "/api/v1/workflows/flow/revisions", null, 404);

        Map<String, Object> first = call(vic, "GET", "/api/v1/workflows/flow/revisions/1", null, 200);
        assertThat((List<Object>) ((Map<String, Object>) first.get("payload")).get("nodes"))
                .hasSize(1);
        call(vic, "GET", "/api/v1/workflows/flow/revisions/9", null, 404);

        // restoring is a write: maintainers may, viewers may not; it becomes revision 4
        call(vic, "POST", "/api/v1/workflows/flow/revisions/1/restore", null, 403);
        Map<String, Object> restored = call(mia, "POST", "/api/v1/workflows/flow/revisions/1/restore", null, 200);
        assertThat(restored).containsEntry("revision", 4).containsEntry("name", "流程");
        assertThat((List<Object>) restored.get("nodes")).hasSize(1);
        assertThat((String)
                        ((List<Map<String, Object>>) call(vic, "GET", "/api/v1/workflows/flow/revisions", null, 200))
                                .getFirst()
                                .get("summary"))
                .isEqualTo("节点 ~a −b；名称");

        // publish bumps the version; clone makes a draft copy and refuses an existing id
        Map<String, Object> published = call(mia, "POST", "/api/v1/workflows/flow/publish", null, 200);
        assertThat(published).containsEntry("status", "published").containsEntry("version", 2);
        Map<String, Object> copy = call(mia, "POST", "/api/v1/workflows/flow/clone", Map.of("id", "flow-copy"), 201);
        assertThat(copy)
                .containsEntry("id", "flow-copy")
                .containsEntry("status", "draft")
                .containsEntry("version", 1)
                .containsEntry("name", "流程 副本")
                .containsEntry("projectId", "p1");
        call(mia, "POST", "/api/v1/workflows/flow/clone", Map.of("id", "flow-copy"), 409);
        // a maintainer of p1 cannot clone into a global SOP
        call(mia, "POST", "/api/v1/workflows/flow/clone", Map.of("id", "flow-global", "projectId", ""), 403);
        call(vic, "POST", "/api/v1/workflows/flow/publish", null, 403);

        // roles: history, restore, and no deletion while an SOP uses the role
        call(admin, "PUT", "/api/v1/agents/dev", Map.of("name", "开发", "prompt", "v1", "executorId", "mock"), 200);
        call(admin, "PUT", "/api/v1/agents/dev", Map.of("name", "开发", "prompt", "v2", "executorId", "mock"), 200);
        List<Map<String, Object>> roleRevisions = call(vic, "GET", "/api/v1/agents/dev/revisions", null, 200);
        assertThat(roleRevisions).extracting(r -> r.get("revision")).containsExactly(2, 1);
        assertThat(roleRevisions.getFirst()).containsEntry("summary", "系统提示词");
        call(mia, "POST", "/api/v1/agents/dev/revisions/1/restore", null, 403);
        Map<String, Object> role = call(admin, "POST", "/api/v1/agents/dev/revisions/1/restore", null, 200);
        assertThat(role).containsEntry("prompt", "v1");
        call(
                admin,
                "PUT",
                "/api/v1/workflows/uses-dev",
                workflow("用角色", null, List.of(Map.of("id", "x", "type", "agent", "agentId", "dev"))),
                200);
        Map<String, Object> refused = call(admin, "DELETE", "/api/v1/agents/dev", null, 409);
        assertThat((String) refused.get("detail")).contains("uses-dev");
        call(admin, "DELETE", "/api/v1/workflows/uses-dev", null, 204);
        call(admin, "DELETE", "/api/v1/agents/dev", null, 204);
        call(admin, "PUT", "/api/v1/agents/dev", Map.of("name", "开发", "prompt", "new", "executorId", "mock"), 200);
        // a role re-created under the same id starts a new history
        assertThat((List<Object>) call(admin, "GET", "/api/v1/agents/dev/revisions", null, 200))
                .hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void runLogsCanBePagedAndDownloadedAndSettledRunsDeleted() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/inner",
                workflow("内层", "p2", List.of(script("say", "for i in 1 2 3 4 5; do echo line-$i; done"))),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/outer",
                workflow(
                        "外层",
                        "p2",
                        List.of(
                                script("first", "echo hello-logs; echo oops-stderr >&2"),
                                Map.of("id", "child", "type", "sop", "sopWorkflowId", "inner"))),
                200);
        Map<String, Object> run = call(
                admin, "POST", "/api/v1/runs", Map.of("workflowId", "outer", "projectId", "p2", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) call(admin, "GET", "/api/v1/runs/" + id, null, 200))
                                .get("status")));

        // newest page, then backwards, then forwards from the start
        Map<String, Object> newest = call(admin, "GET", "/api/v1/runs/" + id + "/logs?limit=3", null, 200);
        List<Map<String, Object>> logs = (List<Map<String, Object>>) newest.get("logs");
        assertThat(logs).hasSize(3);
        assertThat(newest).containsEntry("hasMore", true);
        List<Long> sequences =
                logs.stream().map(l -> ((Number) l.get("sequence")).longValue()).toList();
        assertThat(sequences).isSorted();
        long nextBefore = ((Number) newest.get("nextBefore")).longValue();
        assertThat(nextBefore).isEqualTo(sequences.getFirst());
        Map<String, Object> older =
                call(admin, "GET", "/api/v1/runs/" + id + "/logs?limit=200&before=" + nextBefore, null, 200);
        List<Map<String, Object>> olderLogs = (List<Map<String, Object>>) older.get("logs");
        assertThat(older).containsEntry("hasMore", false);
        assertThat(olderLogs)
                .allSatisfy(l ->
                        assertThat(((Number) l.get("sequence")).longValue()).isLessThan(nextBefore));
        Map<String, Object> forward = call(admin, "GET", "/api/v1/runs/" + id + "/logs?after=0&limit=2", null, 200);
        assertThat(forward).containsEntry("hasMore", true);
        assertThat(((Number) forward.get("nextAfter")).longValue())
                .isEqualTo(((Number) ((List<Map<String, Object>>) forward.get("logs"))
                                .getLast()
                                .get("sequence"))
                        .longValue());
        Map<String, Object> firstNode =
                call(admin, "GET", "/api/v1/runs/" + id + "/logs?nodeId=first&limit=200", null, 200);
        assertThat((List<Map<String, Object>>) firstNode.get("logs"))
                .isNotEmpty()
                .allSatisfy(l -> assertThat(l).containsEntry("nodeId", "first"))
                .anySatisfy(
                        l -> assertThat(l).containsEntry("category", "output").containsEntry("message", "hello-logs"))
                .anySatisfy(l -> assertThat(l).containsEntry("message", "oops-stderr"));

        ResponseEntity<String> text = send(admin, "GET", "/api/v1/runs/" + id + "/logs.txt", null);
        assertThat(text.getStatusCode().value()).isEqualTo(200);
        assertThat(text.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"" + id + "-logs.txt\"");
        assertThat(text.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(text.getBody())
                .startsWith("# LoopForge4J run " + id + " · 外层 · succeeded")
                .contains("| first |", "hello-logs");

        // the child run cannot be deleted on its own; deleting the parent removes both
        List<Map<String, Object>> children = call(admin, "GET", "/api/v1/runs?parentRunId=" + id, null, 200);
        String child = (String) children.getFirst().get("id");
        call(admin, "DELETE", "/api/v1/runs/" + child, null, 409);
        call(admin, "DELETE", "/api/v1/runs/" + id, null, 204);
        call(admin, "GET", "/api/v1/runs/" + id, null, 404);
        call(admin, "GET", "/api/v1/runs/" + child, null, 404);
        assertThat(data.resolve("workspaces").resolve(id)).doesNotExist();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsInProgressAreNotDeletedAndOnlyMaintainersDelete() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p3", Map.of("name", "P3"), 200);
        String devId = (String) ((Map<String, Object>) call(
                        admin, "POST", "/api/v1/users", Map.of("username", "dan", "password", "dan-password-1"), 201))
                .get("id");
        call(admin, "PUT", "/api/v1/projects/p3/members/" + devId, Map.of("role", "developer"), 200);
        RestClient dan = client(login("dan"));
        call(admin, "PUT", "/api/v1/workflows/slow", workflow("慢", "p3", List.of(script("wait", "sleep 30"))), 200);
        Map<String, Object> run =
                call(dan, "POST", "/api/v1/runs", Map.of("workflowId", "slow", "projectId", "p3", "input", "x"), 201);
        String id = (String) run.get("id");
        call(admin, "DELETE", "/api/v1/runs/" + id, null, 409);
        call(admin, "POST", "/api/v1/runs/" + id + "/cancel", null, 200);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "cancelled"
                        .equals(((Map<String, Object>) call(admin, "GET", "/api/v1/runs/" + id, null, 200))
                                .get("status")));
        call(dan, "DELETE", "/api/v1/runs/" + id, null, 403);
        call(admin, "DELETE", "/api/v1/runs/" + id, null, 204);

        // schedule history: manual and failed firings are recorded with the run's current status
        Map<String, Object> schedule = call(
                admin,
                "POST",
                "/api/v1/projects/p3/schedules",
                Map.of("name", "夜间", "cron", "0 3 * * *", "zone", "UTC", "workflowId", "slow", "enabled", true),
                201);
        String sid = (String) schedule.get("id");
        call(dan, "POST", "/api/v1/projects/p3/schedules/" + sid + "/run", null, 200);
        List<Map<String, Object>> executions =
                call(dan, "GET", "/api/v1/projects/p3/schedules/" + sid + "/executions", null, 200);
        assertThat(executions)
                .singleElement()
                .satisfies(e -> assertThat(e)
                        .containsEntry("trigger", "manual")
                        .containsEntry("status", "started")
                        .containsEntry("actor", "dan")
                        .containsEntry("runStatus", "running"));
        String scheduledRun = (String) executions.getFirst().get("runId");
        call(admin, "POST", "/api/v1/runs/" + scheduledRun + "/cancel", null, 200);
        call(admin, "PUT", "/api/v1/workflows/slow", workflow("慢", "p3", List.of(script("wait", "true"))), 200);
        call(admin, "DELETE", "/api/v1/workflows/slow", null, 204);
        call(dan, "POST", "/api/v1/projects/p3/schedules/" + sid + "/run", null, 200);
        executions = call(dan, "GET", "/api/v1/projects/p3/schedules/" + sid + "/executions", null, 200);
        assertThat(executions).hasSize(2);
        assertThat(executions.getFirst()).containsEntry("status", "failed").doesNotContainKey("runId");
        assertThat((String) executions.getFirst().get("error")).contains("slow");
        call(client(login("dan")), "GET", "/api/v1/projects/p9/schedules/" + sid + "/executions", null, 403);
    }
}
