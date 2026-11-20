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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Conversations with the assistant through a fake Claude Code CLI that uses the platform MCP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssistantIntegrationTest {

    static final String TOKEN = "assistant-test-token-0123456789-abcdef";
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    static final Path data;
    static final Path cli;

    static {
        try {
            data = Files.createTempDirectory("lf-assistant-it");
            cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import json, os, sys, time, traceback, urllib.request, urllib.error
                    sys.excepthook = lambda *e: (open("err.txt", "w").write("".join(traceback.format_exception(*e))), sys.exit(1))
                    argv = sys.argv[1:]
                    prompt = sys.stdin.read()
                    resume = argv[argv.index("--resume") + 1] if "--resume" in argv else None
                    config = json.load(open(argv[argv.index("--mcp-config") + 1]))["mcpServers"]
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    def call(name, arguments):
                        p = config["loopforge-platform"]
                        req = urllib.request.Request(p["url"], method="POST", data=json.dumps({"jsonrpc": "2.0", "id": 1,
                              "method": "tools/call", "params": {"name": name, "arguments": arguments}}).encode(),
                              headers=dict(p["headers"], **{"Content-Type": "application/json"}))
                        return json.load(opener.open(req, timeout=30))["result"]
                    session = resume or "s-1"
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": session}), flush=True)
                    out = []
                    if "睡一会" in prompt:
                        time.sleep(60)
                    if resume is None:
                        open("auth.txt", "w").write(config["loopforge-platform"]["headers"]["Authorization"])
                        created = call("requirements_create", {"path": {"projectId": "p1"}, "body": {"title": "登录页报错"}})
                        out.append("created=" + str(not created["isError"]))
                        other = call("projects_save", {"path": {"id": "p2"}, "body": {"name": "taken"}})
                        out.append("other=" + str(other["isError"]))
                        admin = call("me_get", {})
                        out.append("me=" + json.loads(admin["content"][0]["text"])["user"]["username"])
                        open("notes.txt", "w").write("turn1")
                        out.append("fresh=" + str("登录页" in prompt and "LoopForge 平台助手" in prompt))
                    else:
                        out.append("resumed=" + resume + " notes=" + open("notes.txt").read())
                        out.append("prompt=" + prompt.strip())
                    print(json.dumps({"type": "result", "session_id": session, "result": " ".join(out), "is_error": False,
                                      "usage": {"input_tokens": 1000, "output_tokens": 50}}), flush=True)
                    """);
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:assistant;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.executors.claude.binary", cli::toString);
        // this test is about the assistant, not the member sandbox policy (covered elsewhere)
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

    Map<String, Object> ok(RestClient c, String method, String path, Object body, int status) {
        var spec = c.method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> r = spec.retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        return r.getBody() == null || !r.getBody().startsWith("{") ? Map.of() : Json.read(r.getBody(), Map.class);
    }

    String login(String user) {
        return (String) ok(
                        client("none"),
                        "POST",
                        "/api/v1/auth/login",
                        Map.of("username", user, "password", user + "-password-1"),
                        200)
                .get("token");
    }

    List<Map<String, Object>> messages(RestClient c, String id) {
        return c.get()
                .uri("/api/v1/assistant/sessions/{id}/messages", id)
                .retrieve()
                .body(LIST);
    }

    void awaitIdle(RestClient c, String id) {
        await().atMost(Duration.ofSeconds(60))
                .until(() -> "idle"
                        .equals(ok(c, "GET", "/api/v1/assistant/sessions/" + id, null, 200)
                                .get("status")));
    }

    @Test
    void theAssistantWorksForThePersonSpeakingAndKeepsItsWorkspace() throws Exception {
        RestClient admin = client(TOKEN);
        ok(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        ok(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        String carolId = (String)
                ok(admin, "POST", "/api/v1/users", Map.of("username", "carol", "password", "carol-password-1"), 201)
                        .get("id");
        ok(admin, "POST", "/api/v1/users", Map.of("username", "dave", "password", "dave-password-1"), 201);
        ok(admin, "PUT", "/api/v1/projects/p1/members/" + carolId, Map.of("role", "developer"), 200);
        RestClient carol = client(login("carol"));
        RestClient dave = client(login("dave"));

        String id = (String)
                ok(carol, "POST", "/api/v1/assistant/sessions", Map.of("projectId", "p1", "title", "排查登录"), 201)
                        .get("id");
        ok(dave, "POST", "/api/v1/assistant/sessions", Map.of("projectId", "p1"), 403);
        ok(dave, "GET", "/api/v1/assistant/sessions/" + id, null, 404);
        // project members see the conversation but do not speak in it: the workspace and CLI session
        // persist across turns and run with the owner's rights
        String erinId = (String)
                ok(admin, "POST", "/api/v1/users", Map.of("username", "erin", "password", "erin-password-1"), 201)
                        .get("id");
        ok(admin, "PUT", "/api/v1/projects/p1/members/" + erinId, Map.of("role", "maintainer"), 200);
        RestClient erin = client(login("erin"));
        ok(erin, "GET", "/api/v1/assistant/sessions/" + id, null, 200);
        ok(erin, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "我也说一句"), 403);

        // writes run directly in this test (approval drafts are covered by AssistantApprovalIntegrationTest)
        assertThat(ok(carol, "GET", "/api/v1/assistant/sessions/preferences", null, 200))
                .containsEntry("approvalMode", "confirm");
        ok(carol, "PUT", "/api/v1/assistant/sessions/preferences", Map.of("approvalMode", "auto"), 200);
        ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "帮我建一个需求：登录页报错"), 202);
        // a message sent while the assistant answers waits; its author can take it back
        Map<String, Object> waiting =
                ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "再来"), 202);
        @SuppressWarnings("unchecked")
        Map<String, Object> queued = (Map<String, Object>) waiting.get("queued");
        assertThat(queued).containsEntry("content", "再来").containsEntry("priority", 0);
        ok(erin, "DELETE", "/api/v1/assistant/sessions/" + id + "/queue/" + queued.get("id"), null, 404);
        ok(carol, "DELETE", "/api/v1/assistant/sessions/" + id + "/queue/" + queued.get("id"), null, 204);
        awaitIdle(carol, id);
        List<Map<String, Object>> first = messages(carol, id);
        Path ws = data.resolve("workspaces").resolve("conv-" + id);
        assertThat(first)
                .as(() -> first + "\n" + (Files.exists(ws.resolve("err.txt")) ? read(ws.resolve("err.txt")) : "no err"))
                .extracting(m -> m.get("role"))
                .containsExactly("user", "assistant");
        String reply = (String) first.get(1).get("content");
        // acting as carol: she may create requirements in p1, not touch p2, and is not an administrator
        assertThat(reply).contains("created=True", "other=True", "me=carol", "fresh=True");
        List<Map<String, Object>> requirements =
                carol.get().uri("/api/v1/projects/p1/requirements").retrieve().body(LIST);
        assertThat(requirements)
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("title", "登录页报错").containsEntry("createdBy", "carol"));
        assertThat(((Number) first.get(1).get("tokens")).longValue()).isGreaterThan(0);

        // the token died with the turn
        Path workspace = data.resolve("workspaces").resolve("conv-" + id);
        String auth = Files.readString(workspace.resolve("auth.txt"));
        ok(client(auth.substring(7)), "GET", "/api/v1/projects", null, 401);

        // the next turn continues the CLI session in the same workspace
        ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "继续"), 202);
        awaitIdle(carol, id);
        assertThat((String) messages(carol, id).getLast().get("content"))
                .contains("resumed=s-1", "notes=turn1", "prompt=[carol] 继续");

        // a reply can be stopped
        ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "睡一会"), 202);
        Thread.sleep(1500);
        ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/cancel", null, 202);
        awaitIdle(carol, id);
        assertThat(messages(carol, id).getLast()).containsEntry("role", "error").containsEntry("content", "已停止回复");

        // waiting messages run in order after the reply; a steering one stops it and goes first
        ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "睡一会"), 202);
        ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "排队一"), 202);
        ok(
                carol,
                "POST",
                "/api/v1/assistant/sessions/" + id + "/messages",
                Map.of("content", "排队二", "mode", "queue"),
                202);
        ok(
                carol,
                "POST",
                "/api/v1/assistant/sessions/" + id + "/messages",
                Map.of("content", "x", "mode", "nope"),
                422);
        assertThat(carol.get()
                        .uri("/api/v1/assistant/sessions/{id}/queue", id)
                        .retrieve()
                        .body(LIST))
                .extracting(q -> q.get("content"))
                .containsExactly("排队一", "排队二");
        ok(
                carol,
                "POST",
                "/api/v1/assistant/sessions/" + id + "/messages",
                Map.of("content", "转向", "mode", "steer"),
                202);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> messages(carol, id).stream()
                        .anyMatch(m -> String.valueOf(m.get("content")).contains("prompt=[carol] 排队二")));
        List<Object> spoken = messages(carol, id).stream()
                .filter(m -> "user".equals(m.get("role")))
                .map(m -> m.get("content"))
                .toList();
        assertThat(spoken.subList(spoken.size() - 4, spoken.size())).containsExactly("睡一会", "转向", "排队一", "排队二");
        assertThat(carol.get()
                        .uri("/api/v1/assistant/sessions/{id}/queue", id)
                        .retrieve()
                        .body(LIST))
                .isEmpty();

        // the preference makes steering the default
        assertThat(ok(carol, "GET", "/api/v1/assistant/sessions/preferences", null, 200))
                .containsEntry("steerOnSend", false);
        ok(carol, "PUT", "/api/v1/assistant/sessions/preferences", Map.of("steerOnSend", true), 200);
        awaitIdle(carol, id);
        ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "睡一会"), 202);
        @SuppressWarnings("unchecked")
        Map<String, Object> steering = (Map<String, Object>)
                ok(carol, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", "停下"), 202)
                        .get("queued");
        assertThat(steering).containsEntry("priority", 1);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> messages(carol, id).stream()
                        .anyMatch(m -> String.valueOf(m.get("content")).contains("prompt=[carol] 停下")));

        // replies someone has not seen yet are marked for them until they read the conversation
        assertThat(String.valueOf(ok(erin, "GET", "/api/v1/assistant/sessions/unread", null, 200)
                        .get("sessionIds")))
                .contains(id);
        messages(erin, id);
        assertThat(String.valueOf(ok(erin, "GET", "/api/v1/assistant/sessions/unread", null, 200)
                        .get("sessionIds")))
                .doesNotContain(id);
        assertThat(ok(carol, "GET", "/api/v1/assistant/sessions/" + id, null, 200))
                .containsEntry("source", "chat");

        // deleting the conversation removes its workspace
        awaitIdle(carol, id);
        ok(carol, "DELETE", "/api/v1/assistant/sessions/" + id, null, 204);
        await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(workspace));
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return e.toString();
        }
    }
}
