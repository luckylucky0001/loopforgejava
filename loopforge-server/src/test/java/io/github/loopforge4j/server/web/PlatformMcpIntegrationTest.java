package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.json.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
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
import tools.jackson.databind.JsonNode;

/**
 * Platform MCP and knowledge base end to end: a fake Claude Code CLI inside a run reaches the
 * platform MCP and the knowledge proxy with the run tokens it was given, against a fake upstream
 * knowledge base MCP server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformMcpIntegrationTest {

    static final String TOKEN = "platform-mcp-test-token-0123456789";
    static final String KB_TOKEN = "upstream-kb-token-secret-42";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    static final Path data;
    static final Path cli;
    static final HttpServer kb;
    static final List<JsonNode> kbCalls = new CopyOnWriteArrayList<>();
    static final List<String> kbAuth = new CopyOnWriteArrayList<>();

    static {
        try {
            data = Files.createTempDirectory("lf-platform-mcp");
            kb = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            kb.createContext("/mcp", exchange -> {
                JsonNode request =
                        Json.mapper().readTree(exchange.getRequestBody().readAllBytes());
                kbAuth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                String method = request.path("method").asString("");
                if (method.startsWith("notifications/")) {
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    return;
                }
                Object result;
                if (method.equals("initialize")) {
                    exchange.getResponseHeaders().add("Mcp-Session-Id", "kb-session");
                    result = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of());
                } else if (method.equals("tools/list")) {
                    result = Map.of(
                            "tools",
                            List.of("kb_search", "kb_fetch", "kb_list_sources", "kb_get_source", "kb_write_document")
                                    .stream()
                                    .map(n -> Map.of(
                                            "name", n, "description", n, "inputSchema", Map.of("type", "object")))
                                    .toList());
                } else {
                    kbCalls.add(request.path("params"));
                    String tool = request.path("params").path("name").asString("");
                    Object payload = switch (tool) {
                        case "kb_list_sources" ->
                            Map.of(
                                    "sources",
                                    List.of(Map.of("id", "s1", "name", "One"), Map.of("id", "s2", "name", "Two")));
                        case "kb_search" ->
                            Map.of(
                                    "query",
                                    "q",
                                    "results",
                                    List.of(Map.of("source", "s1", "path", "a.md", "citation", "s1@c:a.md#L1-L2")));
                        case "kb_write_document" ->
                            Map.of("source", "s1", "path", "guides/deploy.md", "commit", "abc123");
                        default -> Map.of("source", "s1", "lines", List.of());
                    };
                    result = Map.of("content", List.of(Map.of("type", "text", "text", Json.write(payload))));
                }
                byte[] body = Json.write(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result))
                        .getBytes(StandardCharsets.UTF_8);
                if (method.equals("tools/call")) {
                    // the upstream may answer as an event stream
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    byte[] sse = ("event: message\ndata: " + new String(body, StandardCharsets.UTF_8) + "\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, sse.length);
                    exchange.getResponseBody().write(sse);
                } else {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            kb.start();
            cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import json, os, subprocess, sys, urllib.request, urllib.error
                    argv = sys.argv[1:]
                    prompt = sys.stdin.read()
                    config = json.load(open(argv[argv.index("--mcp-config") + 1]))["mcpServers"]
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    def http(url, method="GET", body=None, headers=None):
                        req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                                     method=method, headers=dict(headers or {}, **{"Content-Type": "application/json"}))
                        try:
                            r = opener.open(req, timeout=30)
                            return r.status, json.loads(r.read() or b"null")
                        except urllib.error.HTTPError as e:
                            return e.code, None
                    def rpc(server, method, params):
                        return http(server["url"], "POST", {"jsonrpc": "2.0", "id": 1, "method": method, "params": params},
                                    server.get("headers"))[1]
                    out = {"system": prompt.split("</system_instructions>")[0]}
                    platform = config["loopforge-platform"]
                    knowledge = config["loopforge-knowledge"]
                    base = platform["url"][:-len("/mcp")]
                    auth = platform["headers"]
                    out["platformToken"] = auth["Authorization"]
                    out["knowledgeToken"] = knowledge["headers"]["Authorization"]
                    out["tools"] = [t["name"] for t in rpc(platform, "tools/list", {})["result"]["tools"]]
                    runs = json.loads(rpc(platform, "tools/call", {"name": "runs_list",
                              "arguments": {"query": {"limit": "5"}}})["result"]["content"][0]["text"])
                    got = rpc(platform, "tools/call", {"name": "runs_get",
                              "arguments": {"path": {"runId": runs[0]["id"]}}})
                    out["runStatus"] = json.loads(got["result"]["content"][0]["text"])["status"]
                    out["projects"] = [p["id"] for p in json.loads(rpc(platform, "tools/call",
                              {"name": "projects_list", "arguments": {}})["result"]["content"][0]["text"])]
                    out["start"] = rpc(platform, "tools/call", {"name": "runs_start", "arguments": {"body": {"workflowId": "wf"}}})
                    out["put"] = http(base + "/api/v1/projects/p1", "PUT", {"name": "x"}, auth)[0]
                    out["validate"] = http(base + "/api/v1/workflows/validate", "POST", {"id": "x", "nodes": []}, auth)[0]
                    out["otherProject"] = http(base + "/api/v1/projects/p2", "GET", None, auth)[0]
                    out["knowledgeAsApi"] = http(base + "/api/v1/projects", "GET", None, knowledge["headers"])[0]
                    out["platformAsKnowledge"] = http(knowledge["url"], "POST",
                              {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}, auth)[0]
                    out["kbTools"] = sorted(t["name"] for t in rpc(knowledge, "tools/list", {})["result"]["tools"])
                    out["kbSearch"] = rpc(knowledge, "tools/call", {"name": "kb_search", "arguments": {"query": "deploy"}})["result"]
                    out["kbOther"] = rpc(knowledge, "tools/call", {"name": "kb_fetch",
                              "arguments": {"source": "s2", "path": "x.md"}})["result"]
                    out["kbWrite"] = rpc(knowledge, "tools/call", {"name": "kb_write_document", "arguments": {}})
                    open("platform.json", "w").write(json.dumps(out))
                    open("knowledge.md", "w").write("---\\ntitle: 部署排障\\nsource: s1\\npath: guides/deploy.md\\n---\\n先看日志。\\n")
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": "s"}), flush=True)
                    gate = config["loopforge-gate"]
                    env = dict(os.environ); env.update(gate["env"])
                    req = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
                           {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "submit_gate_result",
                            "arguments": {"decision": "pass", "reason": "ok"}}}]
                    subprocess.run([gate["command"]] + gate["args"], input="\\n".join(json.dumps(r) for r in req) + "\\n",
                                   text=True, env=env, capture_output=True, timeout=60)
                    print(json.dumps({"type": "result", "session_id": "s", "result": "done", "is_error": False}), flush=True)
                    """);
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopKnowledgeBase() {
        kb.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:platform-mcp;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.engine.release-workspace-on-finish", () -> "false");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.executors.claude.binary", cli::toString);
    }

    @LocalServerPort
    int port;

    RestClient api;

    @BeforeEach
    void setUp() {
        api = client(TOKEN);
    }

    RestClient client(String token) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {});
        if (token != null) {
            builder.defaultHeader("Authorization", token.startsWith("Bearer ") ? token : "Bearer " + token);
        }
        return builder.build();
    }

    @Test
    void agentsReadThePlatformAndTheKnowledgeBaseWithRunScopedTokens() throws Exception {
        send("PUT", "/api/v1/projects/p1", Map.of("name", "One"), 200);
        send("PUT", "/api/v1/projects/p2", Map.of("name", "Two"), 200);
        send(
                "PUT",
                "/api/v1/knowledge/settings",
                Map.of(
                        "enabled",
                        true,
                        "url",
                        "http://127.0.0.1:" + kb.getAddress().getPort() + "/mcp",
                        "token",
                        KB_TOKEN,
                        "writeEnabled",
                        true,
                        "projectSources",
                        Map.of("p1", List.of("s1"))),
                200);
        String settings = api.get().uri("/api/v1/knowledge/settings").retrieve().body(String.class);
        assertThat(settings).contains("\"tokenConfigured\":true").doesNotContain(KB_TOKEN);
        assertThat(api.post().uri("/api/v1/knowledge/check").retrieve().body(MAP))
                .containsEntry("ok", true);

        send(
                "PUT",
                "/api/v1/agents/dev",
                Map.of("executorId", "claude", "prompt", "实现", "platformMcp", true, "knowledgeBase", true),
                200);
        send(
                "PUT",
                "/api/v1/workflows/wf",
                Map.of("status", "published", "nodes", List.of(Map.of("id", "a", "type", "agent", "agentId", "dev"))),
                200);
        String runId = (String) api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workflowId", "wf", "projectId", "p1"))
                .retrieve()
                .body(MAP)
                .get("id");
        await().atMost(Duration.ofSeconds(60))
                .until(() -> !"running".equals(run(runId).get("status"))
                        && !"queued".equals(run(runId).get("status")));
        Map<String, Object> run = run(runId);
        String events = api.get()
                .uri("/api/v1/runs/{id}/events?limit=5000", runId)
                .retrieve()
                .body(String.class);
        assertThat(run.get("status")).as(events).isEqualTo("succeeded");

        JsonNode out = Json.mapper()
                .readTree(Path.of((String) run.get("workspaceRoot"))
                        .resolve("platform.json")
                        .toFile());
        // platform MCP: read-only tools of the run's own project
        List<String> tools = Json.mapper().convertValue(out.get("tools"), List.class);
        assertThat(tools)
                .contains("runs_get", "projects_list", "analytics_get")
                .doesNotContain("runs_start", "projects_save");
        assertThat(out.path("runStatus").asString()).isEqualTo("running");
        assertThat(Json.mapper().convertValue(out.get("projects"), List.class)).containsExactly("p1");
        assertThat(out.path("start").path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(out.path("put").asInt()).isEqualTo(403);
        // any mutating method is refused before a controller sees it, even where the role would do
        assertThat(out.path("validate").asInt()).isEqualTo(403);
        assertThat(out.path("otherProject").asInt()).isIn(403, 404);
        // the two token kinds are not interchangeable
        assertThat(out.path("knowledgeAsApi").asInt()).isEqualTo(401);
        assertThat(out.path("platformAsKnowledge").asInt()).isIn(401, 403);
        // knowledge proxy: read-only tools, scoped to the project's sources
        assertThat(Json.mapper().convertValue(out.get("kbTools"), List.class))
                .containsExactly("kb_fetch", "kb_get_source", "kb_list_sources", "kb_search");
        assertThat(out.path("kbSearch").path("isError").asBoolean()).isFalse();
        assertThat(out.path("kbOther").path("isError").asBoolean()).isTrue();
        assertThat(out.path("kbWrite").path("error").path("code").asInt()).isEqualTo(-32602);
        JsonNode search = kbCalls.stream()
                .filter(c -> c.path("name").asString("").equals("kb_search"))
                .findFirst()
                .orElseThrow();
        assertThat(search.path("arguments").path("sources").toString()).isEqualTo("[\"s1\"]");
        assertThat(kbCalls)
                .noneMatch(c -> c.path("arguments").path("source").asString("").equals("s2"));
        assertThat(kbAuth).containsOnly("Bearer " + KB_TOKEN);
        assertThat(out.path("system").asString()).contains("知识库使用规范");

        // tokens die with the run and never reach run data
        String platformToken = out.path("platformToken").asString();
        assertThat(client(platformToken)
                        .get()
                        .uri("/api/v1/projects")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        String state = api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(String.class);
        assertThat(state + events).doesNotContain(platformToken.substring(7)).doesNotContain(KB_TOKEN);

        // activity shows the agent's calls
        List<Map<String, Object>> activity = api.get()
                .uri("/api/v1/knowledge/activity?projectId=p1")
                .retrieve()
                .body(LIST);
        assertThat(activity).anySatisfy(a -> {
            assertThat(a.get("tool")).isEqualTo("kb_search");
            assertThat(a.get("runId")).isEqualTo(runId);
            assertThat(a.get("hits")).isEqualTo(1);
        });

        // the distilled knowledge.md becomes a reviewable, publishable submission
        List<Map<String, Object>> candidates = api.get()
                .uri("/api/v1/knowledge/candidates?projectId=p1")
                .retrieve()
                .body(LIST);
        assertThat(candidates)
                .singleElement()
                .satisfies(c -> assertThat(c.get("runId")).isEqualTo(runId));
        Map<String, Object> created = send(
                "POST",
                "/api/v1/knowledge/submissions",
                Map.of("runId", runId, "artifactId", candidates.getFirst().get("artifactId")),
                201);
        assertThat(created)
                .containsEntry("title", "部署排障")
                .containsEntry("source", "s1")
                .containsEntry("path", "guides/deploy.md")
                .containsEntry("status", "pending");
        send(
                "POST",
                "/api/v1/knowledge/submissions",
                Map.of("runId", runId, "artifactId", candidates.getFirst().get("artifactId")),
                409);
        Map<String, Object> published =
                send("POST", "/api/v1/knowledge/submissions/" + created.get("id") + "/publish", null, 200);
        assertThat(published).containsEntry("status", "published").containsEntry("commit", "abc123");
        JsonNode write = kbCalls.stream()
                .filter(c -> c.path("name").asString("").equals("kb_write_document"))
                .findFirst()
                .orElseThrow();
        assertThat(write.path("arguments").path("path").asString()).isEqualTo("guides/deploy.md");
        assertThat(write.path("arguments").path("content").asString()).startsWith("# 部署排障\n\n先看日志。");
        send("POST", "/api/v1/knowledge/submissions/" + created.get("id") + "/publish", null, 409);
        send("PUT", "/api/v1/knowledge/submissions/" + created.get("id"), Map.of("title", "x"), 409);

        // console reads are scoped too
        send("GET", "/api/v1/knowledge/document?projectId=p1&source=s2&path=x.md", null, 403);
        JsonNode sources = Json.mapper()
                .readTree(api.get()
                        .uri("/api/v1/knowledge/sources?projectId=p1")
                        .retrieve()
                        .body(String.class));
        assertThat(sources.path("sources")).hasSize(1);

        // analytics over the same run
        Map<String, Object> report =
                api.get().uri("/api/v1/analytics?projectId=p1").retrieve().body(MAP);
        assertThat((Map<String, Object>) report.get("summary"))
                .containsEntry("totalRuns", 1)
                .containsEntry("succeededRuns", 1);
        assertThat((List<Map<String, Object>>) report.get("byWorkflow"))
                .singleElement()
                .satisfies(b -> assertThat(b.get("key")).isEqualTo("wf"));
        List<Map<String, Object>> metrics =
                api.get().uri("/api/v1/workflow-metrics").retrieve().body(LIST);
        assertThat(metrics).anySatisfy(m -> {
            assertThat(m.get("workflowId")).isEqualTo("wf");
            assertThat((List<?>) m.get("nodes")).hasSize(1);
        });
        send("GET", "/api/v1/analytics?grain=year", null, 422);
        send("GET", "/api/v1/analytics?from=2026-01-01&to=2024-01-01", null, 422);
    }

    @Test
    void accountsUseThePlatformMcpWithTheirOwnRights() {
        Map<String, Object> init = rpc(api, "initialize", Map.of("protocolVersion", "2025-03-26"));
        assertThat((Map<String, Object>) init.get("result"))
                .containsEntry("protocolVersion", "2025-03-26")
                .extractingByKey("serverInfo")
                .satisfies(s -> assertThat((Map<String, Object>) s).containsEntry("name", "loopforge-platform"));
        List<Map<String, Object>> tools = (List<Map<String, Object>>)
                ((Map<String, Object>) rpc(api, "tools/list", Map.of()).get("result")).get("tools");
        assertThat(tools).extracting(t -> t.get("name")).contains("runs_start", "projects_save", "knowledge_search");

        Map<String, Object> saved = toolResult(rpc(
                api,
                "tools/call",
                Map.of(
                        "name",
                        "projects_save",
                        "arguments",
                        Map.of("path", Map.of("id", "mcp-made"), "body", Map.of("name", "Made by MCP")))));
        assertThat(saved).containsEntry("isError", false);
        Map<String, Object> listed =
                toolResult(rpc(api, "tools/call", Map.of("name", "projects_list", "arguments", Map.of())));
        assertThat(listed.toString()).contains("mcp-made");
        // path parameters are single plain segments
        Map<String, Object> traversal = toolResult(rpc(
                api,
                "tools/call",
                Map.of("name", "projects_get", "arguments", Map.of("path", Map.of("id", "../users")))));
        assertThat(traversal).containsEntry("isError", true);
        assertThat(rpc(api, "nope", Map.of())).extractingByKey("error").isNotNull();
        // unauthenticated callers get nothing
        assertThat(client(null)
                        .post()
                        .uri("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(api.get()
                        .uri("/mcp")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(405);
        ResponseEntity<Void> notification = api.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                .retrieve()
                .toBodilessEntity();
        assertThat(notification.getStatusCode().value()).isEqualTo(202);
        Map<String, Object> capabilities =
                api.get().uri("/api/v1/capabilities").retrieve().body(MAP);
        assertThat((List<?>) capabilities.get("operations")).hasSizeGreaterThan(50);
    }

    private Map<String, Object> rpc(RestClient client, String method, Map<String, Object> params) {
        return client.post()
                .uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
                .retrieve()
                .body(MAP);
    }

    private static Map<String, Object> toolResult(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    private Map<String, Object> send(String method, String path, Object body, int status) {
        var spec =
                api.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> response = spec.retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value())
                .as(method + " " + path + ": " + response.getBody())
                .isEqualTo(status);
        if (response.getBody() == null
                || response.getBody().isBlank()
                || !response.getBody().startsWith("{")) {
            return Map.of();
        }
        return Json.read(response.getBody(), Map.class);
    }

    private Map<String, Object> run(String runId) {
        return api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(MAP);
    }
}
