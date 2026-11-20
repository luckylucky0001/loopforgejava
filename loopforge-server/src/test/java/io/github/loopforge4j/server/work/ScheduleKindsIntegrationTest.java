package io.github.loopforge4j.server.work;

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

/** AI scheduled tasks and requirement auto-dispatch. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduleKindsIntegrationTest {

    static final String TOKEN = "schedule-kinds-token-0123456789-abcdef";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-schedule-kinds-it");
            Path cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import json, sys, urllib.request
                    argv = sys.argv[1:]
                    prompt = sys.stdin.read()
                    config = json.load(open(argv[argv.index("--mcp-config") + 1]))["mcpServers"]
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    def call(name, arguments):
                        p = config["loopforge-platform"]
                        req = urllib.request.Request(p["url"], method="POST", data=json.dumps({"jsonrpc": "2.0", "id": 1,
                              "method": "tools/call", "params": {"name": name, "arguments": arguments}}).encode(),
                              headers=dict(p["headers"], **{"Content-Type": "application/json"}))
                        return json.load(opener.open(req, timeout=30))["result"]
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": "s"}), flush=True)
                    started = call("runs_start", {"body": {"workflowId": "wf", "projectId": "p1", "input": "nightly"}})
                    run = json.loads(started["content"][0]["text"])["id"]
                    scheduled = "定时任务触发" in prompt and "整理今天的需求" in prompt
                    print(json.dumps({"type": "result", "session_id": "s", "is_error": False,
                                      "result": "scheduled=%s started %s" % (scheduled, run),
                                      "usage": {"input_tokens": 10, "output_tokens": 5}}), flush=True)
                    """);
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:schedule-kinds;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
        registry.add(
                "loopforge.executors.claude.binary",
                () -> data.resolve("fake-claude").toString());
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

    String login(String name) {
        Map<String, Object> r = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return (String) r.get("token");
    }

    String member(RestClient admin, String name, String role) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + created.get("id"), Map.of("role", role), 200);
        return (String) created.get("id");
    }

    static Map<String, Object> schedule(String kind, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(
                Map.of("name", kind + " 任务", "cron", "0 3 * * *", "zone", "UTC", "enabled", true, "kind", kind));
        body.putAll(extra);
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchAndAiTasksWorkForTheirSaver() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        member(admin, "carol", "maintainer");
        member(admin, "dave", "maintainer");
        RestClient carol = client(login("carol"));
        RestClient dave = client(login("dave"));
        call(
                admin,
                "PUT",
                "/api/v1/workflows/wf",
                Map.of(
                        "name", "快",
                        "status", "published",
                        "projectId", "p1",
                        "nodes", List.of(Map.of("id", "a", "type", "script", "script", "sleep 2"))),
                200);
        String base = "/api/v1/projects/p1/requirements";
        call(carol, "POST", base, Map.of("title", "一", "status", "todo", "workflowId", "wf"), 201);
        call(
                carol,
                "POST",
                base,
                Map.of("title", "二", "status", "todo", "priority", "urgent", "workflowId", "wf"),
                201);
        call(carol, "POST", base, Map.of("title", "三", "status", "backlog", "workflowId", "wf"), 201);
        call(carol, "POST", base, Map.of("title", "四", "status", "todo"), 201);
        call(carol, "POST", base, Map.of("title", "五", "status", "todo", "priority", "low", "workflowId", "wf"), 201);

        // validation
        call(carol, "POST", "/api/v1/projects/p1/schedules", schedule("nope", Map.of()), 422);
        call(
                carol,
                "POST",
                "/api/v1/projects/p1/schedules",
                schedule("dispatch", Map.of("dispatchStatuses", List.of("done"))),
                422);
        call(carol, "POST", "/api/v1/projects/p1/schedules", schedule("assistant", Map.of()), 422);
        call(admin, "POST", "/api/v1/projects/p1/schedules", schedule("assistant", Map.of("prompt", "x")), 422);

        // dispatch: most urgent first, at most maxDispatch, requirements without an SOP reported
        Map<String, Object> dispatch = call(
                carol, "POST", "/api/v1/projects/p1/schedules", schedule("dispatch", Map.of("maxDispatch", 2)), 201);
        assertThat(dispatch).containsEntry("runAs", "carol").containsEntry("dispatchStatuses", List.of("todo"));
        String did = (String) dispatch.get("id");
        call(dave, "POST", "/api/v1/projects/p1/schedules/" + did + "/run", null, 200);
        List<Map<String, Object>> executions =
                call(dave, "GET", "/api/v1/projects/p1/schedules/" + did + "/executions", null, 200);
        Map<String, Object> first = executions.getFirst();
        assertThat(first)
                .containsEntry("status", "started")
                .containsEntry("reply", "派发 2 个需求：#2 #1")
                .doesNotContainKey("error");
        assertThat((List<String>) first.get("runIds")).hasSize(2);
        Map<String, Object> run = call(dave, "GET", "/api/v1/runs/" + first.get("runId"), null, 200);
        assertThat((Map<String, Object>) run.get("context")).containsEntry("triggeredBy", "schedule:" + did);
        assertThat((String) ((Map<String, Object>) call(carol, "GET", base + "/2", null, 200)).get("status"))
                .isEqualTo("in_progress");
        // the next firing leaves running requirements alone and takes the remaining one
        call(dave, "POST", "/api/v1/projects/p1/schedules/" + did + "/run", null, 200);
        executions = call(dave, "GET", "/api/v1/projects/p1/schedules/" + did + "/executions", null, 200);
        assertThat(executions.getFirst()).containsEntry("reply", "派发 1 个需求：#5").containsEntry("error", "#4 没有绑定 SOP");

        // AI task: a turn in the project as the last saver, with full permissions; the run it starts is recorded
        Map<String, Object> ai = call(
                carol,
                "POST",
                "/api/v1/projects/p1/schedules",
                schedule("assistant", Map.of("prompt", "整理今天的需求并启动 wf")),
                201);
        String aid = (String) ai.get("id");
        call(
                dave,
                "PUT",
                "/api/v1/projects/p1/schedules/" + aid,
                schedule("assistant", Map.of("prompt", "整理今天的需求并启动 wf")),
                200);
        assertThat((Map<String, Object>) call(dave, "GET", "/api/v1/projects/p1/schedules/" + aid, null, 200))
                .containsEntry("runAs", "dave")
                .containsEntry("createdBy", "carol");
        call(carol, "POST", "/api/v1/projects/p1/schedules/" + aid + "/run", null, 200);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> !"running"
                        .equals(((List<Map<String, Object>>) call(
                                        carol,
                                        "GET",
                                        "/api/v1/projects/p1/schedules/" + aid + "/executions",
                                        null,
                                        200))
                                .getFirst()
                                .get("status")));
        Map<String, Object> turn = ((List<Map<String, Object>>)
                        call(carol, "GET", "/api/v1/projects/p1/schedules/" + aid + "/executions", null, 200))
                .getFirst();
        assertThat(turn).as(turn::toString).containsEntry("status", "succeeded").containsKey("finishedAt");
        assertThat((String) turn.get("reply")).startsWith("scheduled=True started run-");
        assertThat((List<String>) turn.get("runIds")).singleElement().satisfies(id -> {
            assertThat((String) turn.get("reply")).endsWith(id);
            Map<String, Object> started = call(dave, "GET", "/api/v1/runs/" + id, null, 200);
            assertThat((Map<String, Object>) started.get("context")).containsEntry("triggeredBy", "dave");
        });
        // the conversation belongs to the project and is visible to its members
        Map<String, Object> session =
                call(dave, "GET", "/api/v1/assistant/sessions/" + turn.get("sessionId"), null, 200);
        assertThat(session).containsEntry("projectId", "p1").containsEntry("ownerName", "dave");
        assertThat((String) session.get("title")).startsWith("assistant 任务 · ");
    }
}
