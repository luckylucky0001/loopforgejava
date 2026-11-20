package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Iteration 3 through the API: rules, skills and MCP servers bound to a role and used by a run. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssetIntegrationTest {

    static final String TOKEN = "asset-test-token-0123456789-abcdefghij";
    static final String SECRET = "mcp-secret-value-42";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final Path data;
    static final Path cli;

    static {
        try {
            data = Files.createTempDirectory("lf-asset-it");
            cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import json, os, subprocess, sys
                    argv = sys.argv[1:]
                    prompt = sys.stdin.read()
                    config = json.load(open(argv[argv.index("--mcp-config") + 1]))["mcpServers"]
                    open("saw-secret.txt", "w").write(str(config["tracker"]["env"]["TRACKER_TOKEN"] == "%s"))
                    open("system.txt", "w").write(prompt.split("</system_instructions>")[0])
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": "s"}), flush=True)
                    gate = config["loopforge-gate"]
                    env = dict(os.environ); env.update(gate["env"])
                    req = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
                           {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "submit_gate_result",
                            "arguments": {"decision": "pass", "reason": "ok via mcp"}}}]
                    subprocess.run([gate["command"]] + gate["args"], input="\\n".join(json.dumps(r) for r in req) + "\\n",
                                   text=True, env=env, capture_output=True, timeout=60)
                    print(json.dumps({"type": "result", "session_id": "s", "result": "done", "is_error": False}), flush=True)
                    """.formatted(SECRET));
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:assets;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.engine.release-workspace-on-finish", () -> "false");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.executors.claude.binary", cli::toString);
        registry.add("loopforge.mcp.secrets.TRACKER", () -> SECRET);
    }

    @LocalServerPort
    int port;

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
    void roleAssetsReachTheCliAndSecretsStayOutOfRunData() {
        put("/api/v1/rules/no-delete", Map.of("name", "禁止删除", "content", "不得删除生产数据", "priority", 5), 200);
        put(
                "/api/v1/skills/tdd",
                Map.of("description", "测试先行", "content", "先写失败测试", "files", Map.of("scripts/run.sh", "echo run\n")),
                200);
        put(
                "/api/v1/mcp-servers/tracker",
                Map.of("command", "tracker-mcp", "environment", Map.of("TRACKER_TOKEN", "${secret:TRACKER}")),
                200);
        put(
                "/api/v1/agents/dev",
                Map.of(
                        "name",
                        "开发",
                        "executorId",
                        "claude",
                        "prompt",
                        "实现",
                        "rules",
                        List.of("no-delete", "保持提交小而清晰"),
                        "skills",
                        List.of("tdd"),
                        "mcpServers",
                        List.of("tracker")),
                200);
        put(
                "/api/v1/workflows/wf",
                Map.of("status", "published", "nodes", List.of(Map.of("id", "a", "type", "agent", "agentId", "dev"))),
                200);

        ResponseEntity<Map<String, Object>> started = api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workflowId", "wf"))
                .retrieve()
                .toEntity(MAP);
        String runId = (String) started.getBody().get("id");
        await().atMost(Duration.ofSeconds(60))
                .until(() -> !"running".equals(run(runId).get("status")));
        Map<String, Object> run = run(runId);
        assertThat(run.get("status"))
                .as(() -> api.get()
                        .uri("/api/v1/runs/{id}/events?limit=5000", runId)
                        .retrieve()
                        .body(String.class))
                .isEqualTo("succeeded");
        Path ws = Path.of((String) run.get("workspaceRoot"));
        assertThat(read(ws.resolve("saw-secret.txt"))).isEqualTo("True");
        assertThat(read(ws.resolve("system.txt"))).contains("不得删除生产数据", "tdd", "tracker");

        String state = api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(String.class);
        String events = api.get()
                .uri("/api/v1/runs/{id}/events?limit=5000", runId)
                .retrieve()
                .body(String.class);
        String export = api.get().uri("/api/v1/catalog/export").retrieve().body(String.class);
        assertThat(state + events + export).doesNotContain(SECRET);
        assertThat(export).contains("${secret:TRACKER}", "no-delete", "scripts/run.sh");
        assertThat(events).contains("agent.materialized");
    }

    @Test
    void assetsAreValidated() {
        put("/api/v1/mcp-servers/leaky", Map.of("command", "x", "environment", Map.of("API_TOKEN", "plain")), 422);
        put(
                "/api/v1/mcp-servers/web",
                Map.of("transport", "http", "url", "https://x/mcp", "headers", Map.of("Authorization", "Bearer plain")),
                422);
        put("/api/v1/mcp-servers/loopforge-gate", Map.of("command", "x"), 422);
        put("/api/v1/skills/bad", Map.of("content", "x", "files", Map.of("../escape.sh", "x")), 422);
        put("/api/v1/rules/empty", Map.of("content", " "), 422);
        put(
                "/api/v1/agents/hooky",
                Map.of("hooks", List.of(Map.of("id", "h", "event", "Whenever", "script", "x"))),
                422);
        put("/api/v1/rules/ok", Map.of("content", "规则"), 200);
        assertThat(api.delete()
                        .uri("/api/v1/rules/ok")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(api.get()
                        .uri("/api/v1/rules/ok")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    private void put(String path, Map<String, Object> body, int status) {
        ResponseEntity<String> response = api.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value())
                .as(path + ": " + response.getBody())
                .isEqualTo(status);
    }

    private Map<String, Object> run(String runId) {
        return api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(MAP);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
