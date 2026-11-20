package io.github.loopforge4j.server.assistant;

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
 * The assistant's platform writes wait for the speaker (upstream approval modes): confirm turns
 * every write into a draft, assisted only the risky ones, and confirming runs the write with the
 * speaker's own rights.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AssistantApprovalIntegrationTest {

    static final String TOKEN = "approval-test-token-0123456789-abcdef";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-approval-it");
            Path cli = data.resolve("fake-claude");
            // the "assistant" makes the platform calls the prompt names and reports what came back
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
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": "s-1"}), flush=True)
                    out = []
                    if "建需求" in prompt:
                        r = call("requirements_create", {"path": {"projectId": "p1"}, "body": {"title": "支付超时"}})
                        out.append("create=" + r["content"][0]["text"][:40])
                    if "改别的项目" in prompt:
                        r = call("projects_save", {"path": {"id": "p2"}, "body": {"name": "taken"}})
                        out.append("other=" + str(r["isError"]))
                    if "删需求" in prompt:
                        r = call("requirements_delete", {"path": {"projectId": "p1", "number": "1"}})
                        out.append("delete=" + r["content"][0]["text"][:40])
                    print(json.dumps({"type": "result", "session_id": "s-1", "result": " ".join(out), "is_error": False,
                                      "usage": {"input_tokens": 10, "output_tokens": 5}}), flush=True)
                    """);
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:approvals;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add(
                "loopforge.executors.claude.binary",
                () -> data.resolve("fake-claude").toString());
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

    RestClient member(RestClient admin, String name, String role) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + created.get("id"), Map.of("role", role), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return client((String) login.get("token"));
    }

    @SuppressWarnings("unchecked")
    String say(RestClient c, String session, String text) {
        call(c, "POST", "/api/v1/assistant/sessions/" + session + "/messages", Map.of("content", text), 202);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "idle"
                        .equals(((Map<String, Object>)
                                        call(c, "GET", "/api/v1/assistant/sessions/" + session, null, 200))
                                .get("status")));
        List<Map<String, Object>> messages =
                call(c, "GET", "/api/v1/assistant/sessions/" + session + "/messages", null, 200);
        return (String) messages.getLast().get("content");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesWaitForTheSpeakerWhoConfirmsThemWithTheirOwnRights() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        call(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        RestClient ann = member(admin, "ann", "developer");
        RestClient max = member(admin, "max", "maintainer");
        String id = (String)
                ((Map<String, Object>) call(ann, "POST", "/api/v1/assistant/sessions", Map.of("projectId", "p1"), 201))
                        .get("id");
        String drafts = "/api/v1/assistant/sessions/" + id + "/drafts";

        // confirm (the default): nothing is written until ann confirms
        assertThat(say(ann, id, "建需求，再改别的项目")).contains("create=已提交待确认草稿").contains("other=False");
        assertThat((List<Object>) call(ann, "GET", "/api/v1/projects/p1/requirements", null, 200))
                .isEmpty();
        List<Map<String, Object>> pending = call(ann, "GET", drafts, null, 200);
        assertThat(pending)
                .hasSize(2)
                .allSatisfy(
                        d -> assertThat(d).containsEntry("status", "pending").containsEntry("mine", true));
        Map<String, Object> create = pending.stream()
                .filter(d -> "requirements_create".equals(d.get("tool")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> other = pending.stream()
                .filter(d -> "projects_save".equals(d.get("tool")))
                .findFirst()
                .orElseThrow();
        assertThat((String) create.get("body")).contains("支付超时");

        // another member sees the cards but cannot decide them
        assertThat((List<Map<String, Object>>) call(max, "GET", drafts, null, 200))
                .allSatisfy(d -> assertThat(d).containsEntry("mine", false));
        call(max, "POST", drafts + "/" + create.get("id") + "/confirm", null, 403);

        Map<String, Object> applied = call(ann, "POST", drafts + "/" + create.get("id") + "/confirm", null, 200);
        assertThat(applied).containsEntry("status", "applied").containsEntry("decidedBy", "ann");
        assertThat((List<Map<String, Object>>) call(ann, "GET", "/api/v1/projects/p1/requirements", null, 200))
                .singleElement()
                .satisfies(r -> assertThat(r).containsEntry("title", "支付超时").containsEntry("createdBy", "ann"));
        call(ann, "POST", drafts + "/" + create.get("id") + "/confirm", null, 409);

        // confirming runs with ann's own rights: she cannot change another project
        Map<String, Object> refused = call(ann, "POST", drafts + "/" + other.get("id") + "/confirm", null, 200);
        assertThat(refused).containsEntry("status", "failed").containsEntry("resultStatus", 403);
        assertThat((Map<String, Object>) call(admin, "GET", "/api/v1/projects/p2", null, 200))
                .containsEntry("name", "P2");

        // discarding drops a write
        say(ann, id, "建需求");
        Map<String, Object> again = ((List<Map<String, Object>>) call(ann, "GET", drafts, null, 200))
                .stream()
                        .filter(d -> "pending".equals(d.get("status")))
                        .findFirst()
                        .orElseThrow();
        assertThat((Map<String, Object>) call(ann, "POST", drafts + "/" + again.get("id") + "/discard", null, 200))
                .containsEntry("status", "discarded");
        assertThat((List<Object>) call(ann, "GET", "/api/v1/projects/p1/requirements", null, 200))
                .hasSize(1);

        // assisted: ordinary writes run, risky ones still wait
        call(ann, "PUT", "/api/v1/assistant/sessions/preferences", Map.of("approvalMode", "assisted"), 200);
        assertThat((Map<String, Object>) call(ann, "GET", "/api/v1/assistant/sessions/preferences", null, 200))
                .containsEntry("approvalMode", "assisted");
        String assisted = say(ann, id, "建需求，然后删需求");
        assertThat(assisted).doesNotContain("create=已提交").contains("delete=已提交待确认草稿");
        assertThat((List<Object>) call(ann, "GET", "/api/v1/projects/p1/requirements", null, 200))
                .hasSize(2);
    }
}
