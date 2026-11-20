package io.github.loopforge4j.server.eval;

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

/** Evaluating a settled run and applying its recommendations to the current definitions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunEvalIntegrationTest {

    static final String TOKEN = "run-eval-token-0123456789-abcdefgh";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-eval-it");
            Path cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import json, sys, time
                    argv = sys.argv[1:]
                    prompt = sys.stdin.read()
                    resume = argv[argv.index("--resume") + 1] if "--resume" in argv else None
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": "e-1"}), flush=True)
                    time.sleep(1)
                    if resume is None:
                        ok = "运行评测员" in prompt or ("准入线 70 分" in prompt and '"nodeId":"a"' in prompt)
                        text = "看起来还行 ok=%s" % ok
                    else:
                        text = "```json\\n" + json.dumps({
                            "dimensions": {"control": 50, "latency": 90, "tools": 80, "tokens": 90, "recovery": 60, "delivery": 100},
                            "findings": [{"severity": "warn", "dimension": "control", "nodeId": "a", "message": "a 返工过多"}],
                            "summary": "运行成功但 a 返工偏多",
                            "recommendations": [
                                {"id": "r1", "title": "限制 a 的返工", "target": "workflow", "action": "update_node", "nodeId": "a", "set": {"maxReworks": 2}},
                                {"id": "r2", "title": "加单测门禁", "target": "agent", "targetId": "dev", "action": "upsert_gate",
                                 "gate": {"id": "unit", "name": "单测", "commands": ["true"], "required": True, "onFailure": "loop"}},
                                {"id": "r3", "title": "补充提示", "target": "agent", "targetId": "dev", "action": "append_task_prompt", "text": "先写测试。"},
                                {"id": "r4", "title": "不存在的节点", "target": "workflow", "action": "update_node", "nodeId": "ghost", "set": {"maxAttempts": 1}}]
                        }, ensure_ascii=False) + "\\n```"
                    print(json.dumps({"type": "result", "session_id": "e-1", "result": text, "is_error": False,
                                      "usage": {"input_tokens": 100, "output_tokens": 20}}), flush=True)
                    """);
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:run-eval;DB_CLOSE_DELAY=-1");
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
    String settledRun(RestClient c, String workflow) {
        Map<String, Object> run =
                call(c, "POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        return id;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSettledRunIsScoredAndItsRecommendationsApplyToTheCurrentDefinitions() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        RestClient carol = client(member(admin, "carol", "maintainer"));
        RestClient vic = client(member(admin, "vic", "viewer"));
        call(admin, "PUT", "/api/v1/agents/dev", Map.of("name", "开发", "prompt", "写代码", "executorId", "mock"), 200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/wf",
                Map.of(
                        "name", "流程",
                        "status", "published",
                        "projectId", "p1",
                        "nodes",
                                List.of(
                                        Map.of("id", "a", "type", "script", "script", "echo hi"),
                                        Map.of("id", "b", "type", "agent", "agentId", "dev")),
                        "edges", List.of(Map.of("source", "a", "target", "b"))),
                200);
        String runId = settledRun(carol, "wf");
        String base = "/api/v1/runs/" + runId;
        assertThat((Map<String, Object>) call(vic, "GET", base + "/eval", null, 200))
                .isEmpty();

        // anyone who can read the run may evaluate it, once at a time
        Map<String, Object> started = call(vic, "POST", base + "/eval", null, 202);
        assertThat(started).containsEntry("status", "running").containsEntry("requestedBy", "vic");
        call(carol, "POST", base + "/eval", null, 409);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> !"running"
                        .equals(((Map<String, Object>) call(vic, "GET", base + "/eval", null, 200)).get("status")));
        Map<String, Object> eval = call(vic, "GET", base + "/eval", null, 200);
        assertThat(eval).as(eval::toString).containsEntry("status", "done").containsEntry("summary", "运行成功但 a 返工偏多");
        // (50*25 + 90*20 + 80*20 + 90*15 + 60*10 + 100*10) / 100 = 76
        assertThat(eval)
                .containsEntry("score", 76)
                .containsEntry("passed", true)
                .containsEntry("threshold", 70);
        assertThat((List<Map<String, Object>>) eval.get("recommendations"))
                .extracting(r -> r.get("id") + ":" + r.get("status"))
                .containsExactly("r1:pending", "r2:pending", "r3:pending", "r4:pending");
        assertThat(((Number) eval.get("totalTokens")).longValue()).isEqualTo(240);
        String evalId = (String) eval.get("id");

        // applying changes the current definitions: SOP edits need maintainers, role edits administrators
        call(vic, "POST", base + "/eval/apply", Map.of("evalId", evalId, "recommendationIds", List.of("r1")), 403);
        Map<String, Object> afterCarol = call(
                carol,
                "POST",
                base + "/eval/apply",
                Map.of("evalId", evalId, "recommendationIds", List.of("r1", "r4")),
                200);
        assertThat((List<Map<String, Object>>) afterCarol.get("recommendations"))
                .extracting(r -> r.get("id") + ":" + r.get("status"))
                .containsExactly("r1:applied", "r2:pending", "r3:pending", "r4:failed");
        call(carol, "POST", base + "/eval/apply", Map.of("evalId", evalId, "recommendationIds", List.of("r2")), 403);
        call(
                admin,
                "POST",
                base + "/eval/apply",
                Map.of("evalId", evalId, "recommendationIds", List.of("r2", "r3")),
                200);

        Map<String, Object> wf = call(admin, "GET", "/api/v1/workflows/wf", null, 200);
        assertThat(((List<Map<String, Object>>) wf.get("nodes")).getFirst()).containsEntry("maxReworks", 2);
        List<Map<String, Object>> revisions = call(admin, "GET", "/api/v1/workflows/wf/revisions", null, 200);
        assertThat(revisions.getFirst()).containsEntry("author", "carol").containsEntry("summary", "节点 ~a");
        Map<String, Object> dev = call(admin, "GET", "/api/v1/agents/dev", null, 200);
        assertThat(dev).containsEntry("prompt", "写代码\n\n先写测试。");
        assertThat((List<Map<String, Object>>) dev.get("verification"))
                .extracting(g -> g.get("id"))
                .containsExactly("unit");
        // the run's frozen snapshot is untouched
        Map<String, Object> run = call(admin, "GET", base, null, 200);
        assertThat(((List<Map<String, Object>>) ((Map<String, Object>) run.get("workflowSnapshot")).get("nodes"))
                        .getFirst()
                        .get("maxReworks"))
                .isNotEqualTo(2);
        assertThat((List<Object>) call(vic, "GET", base + "/evals", null, 200)).hasSize(1);

        // only settled runs can be evaluated
        call(
                admin,
                "PUT",
                "/api/v1/workflows/slow",
                Map.of(
                        "name", "慢",
                        "status", "published",
                        "projectId", "p1",
                        "nodes", List.of(Map.of("id", "s", "type", "script", "script", "sleep 20"))),
                200);
        Map<String, Object> slow =
                call(carol, "POST", "/api/v1/runs", Map.of("workflowId", "slow", "projectId", "p1", "input", "x"), 201);
        call(carol, "POST", "/api/v1/runs/" + slow.get("id") + "/eval", null, 409);
        call(carol, "POST", "/api/v1/runs/" + slow.get("id") + "/cancel", null, 200);
    }
}
