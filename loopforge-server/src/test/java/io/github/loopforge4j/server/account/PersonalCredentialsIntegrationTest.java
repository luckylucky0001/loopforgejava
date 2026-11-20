package io.github.loopforge4j.server.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.mcp.RunTokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

/**
 * Personal credentials: stored encrypted and never returned; the person's key, base URL and Git
 * token reach the work done for them (their runs, the scheduled runs they created), everyone else
 * keeps the deployment's; agents (even the assistant acting for a person) cannot manage durable
 * credentials.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonalCredentialsIntegrationTest {

    static final String TOKEN = "personal-cred-token-0123456789-abcdef";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-personal-it");
            Path cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import json, os, subprocess, sys
                    sys.stdin.read()
                    argv = sys.argv[1:]
                    seen = "key=%s base=%s model=%s" % (os.environ.get("ANTHROPIC_API_KEY"),
                        os.environ.get("ANTHROPIC_BASE_URL"), os.environ.get("ANTHROPIC_MODEL"))
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": "s"}), flush=True)
                    if "--mcp-config" in argv:
                        gate = json.load(open(argv[argv.index("--mcp-config") + 1]))["mcpServers"]["loopforge-gate"]
                        env = dict(os.environ); env.update(gate["env"])
                        req = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
                               {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "submit_gate_result",
                                "arguments": {"decision": "pass", "reason": "ok"}}}]
                        subprocess.run([gate["command"]] + gate["args"], input="\\n".join(json.dumps(r) for r in req) + "\\n",
                                       text=True, env=env, capture_output=True, timeout=60)
                    print(json.dumps({"type": "result", "session_id": "s", "result": seen, "is_error": False,
                                      "usage": {"input_tokens": 1, "output_tokens": 1}}), flush=True)
                    """);
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:personal;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
        registry.add(
                "loopforge.executors.claude.binary",
                () -> data.resolve("fake-claude").toString());
        registry.add("loopforge.credentials.claude-code.ANTHROPIC_API_KEY", () -> "deployment-key");
        registry.add("loopforge.git.https[0].host", () -> "git.example.com");
        registry.add("loopforge.git.https[0].token", () -> "deployment-git");
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RunTokens runTokens;

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
        return r.getBody() == null || r.getBody().isBlank() ? null : (T) Json.read(r.getBody(), Object.class);
    }

    String user(RestClient admin, String name) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        return (String) created.get("id");
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

    @SuppressWarnings("unchecked")
    String runAndRead(RestClient c, String workflow) {
        Map<String, Object> run =
                call(c, "POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", "p1", "input", "x"), 201);
        return awaitOutput(c, (String) run.get("id"));
    }

    @SuppressWarnings("unchecked")
    String awaitOutput(RestClient c, String runId) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of("succeeded", "failed", "cancelled", "waiting")
                        .contains(((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + runId, null, 200))
                                .get("status")));
        Map<String, Object> run = call(c, "GET", "/api/v1/runs/" + runId, null, 200);
        assertThat(run.get("status")).as(() -> Json.write(run.get("nodes"))).isEqualTo("succeeded");
        return (String) ((Map<String, Object>) ((Map<String, Object>) run.get("nodes")).get("ask")).get("output");
    }

    @Test
    @SuppressWarnings("unchecked")
    void personalCredentialsAreUsedForTheirOwnersWork() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        String carolId = user(admin, "carol");
        String daveId = user(admin, "dave");
        call(admin, "PUT", "/api/v1/projects/p1/members/" + carolId, Map.of("role", "maintainer"), 200);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + daveId, Map.of("role", "developer"), 200);
        RestClient carol = client(login("carol"));
        RestClient dave = client(login("dave"));
        call(admin, "PUT", "/api/v1/agents/asker", Map.of("name", "问", "prompt", "p", "executorId", "claude"), 200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/ask",
                Map.of(
                        "name",
                        "问",
                        "status",
                        "published",
                        "projectId",
                        "p1",
                        "nodes",
                        List.of(
                                Map.of("id", "ask", "type", "agent", "agentId", "asker"),
                                Map.of(
                                        "id",
                                        "git",
                                        "type",
                                        "script",
                                        "script",
                                        "echo git=$LOOPFORGE_GIT_TOKEN__git_example_com")),
                        "edges",
                        List.of(Map.of("source", "ask", "target", "git"))),
                200);

        // saving: validated, the secret is never returned and is encrypted at rest
        Map<String, Object> saved = call(
                carol,
                "PUT",
                "/api/v1/me/credentials/executor:claude-code",
                Map.of(
                        "apiKey", "sk-carol-0123456789",
                        "baseUrl", "https://gw.example/anthropic",
                        "variables", Map.of("ANTHROPIC_MODEL", "carol-model")),
                200);
        assertThat(saved)
                .containsEntry("kind", "executor:claude-code")
                .containsEntry("hint", "…6789")
                .containsEntry("variables", List.of("ANTHROPIC_MODEL"))
                .doesNotContainKey("apiKey");
        call(carol, "PUT", "/api/v1/me/credentials/git:git.example.com", Map.of("token", "carol-git-token"), 200);
        ResponseEntity<String> listed = send(carol, "GET", "/api/v1/me/credentials", null);
        assertThat(listed.getBody()).doesNotContain("sk-carol", "carol-git-token", "carol-model");
        assertThat((List<Map<String, Object>>) Json.read(listed.getBody(), List.class))
                .extracting(m -> m.get("kind"))
                .containsExactly("executor:claude-code", "git:git.example.com");
        List<String> stored = jdbc.sql("SELECT secret FROM user_credentials")
                .query(String.class)
                .list();
        assertThat(stored)
                .hasSize(2)
                .allSatisfy(s -> assertThat(s).startsWith("v1:").doesNotContain("carol"));
        call(dave, "GET", "/api/v1/me/credentials", null, 200);
        assertThat((List<Object>) call(dave, "GET", "/api/v1/me/credentials", null, 200))
                .isEmpty();

        // rejected input
        call(
                carol,
                "PUT",
                "/api/v1/me/credentials/executor:claude-code",
                Map.of("variables", Map.of("PATH", "/x")),
                422);
        call(carol, "PUT", "/api/v1/me/credentials/executor:claude-code", Map.of("baseUrl", "ftp://x"), 422);
        call(carol, "PUT", "/api/v1/me/credentials/executor:claude-code", Map.of("baseUrl", "https://u:p@x"), 422);
        call(carol, "PUT", "/api/v1/me/credentials/executor:claude-code", Map.of("apiKey", "a\nb"), 422);
        call(carol, "PUT", "/api/v1/me/credentials/executor:nope", Map.of("apiKey", "k"), 422);
        call(carol, "PUT", "/api/v1/me/credentials/git:Bad_Host", Map.of("token", "t"), 422);
        call(carol, "PUT", "/api/v1/me/credentials/git:other.example.com", Map.of("username", "u"), 422);
        call(admin, "GET", "/api/v1/me/credentials", null, 403);

        // carol's run uses her key, base URL, extra variable and Git token; dave's the deployment's
        String carolRun = runAndRead(carol, "ask");
        assertThat(carolRun).isEqualTo("key=sk-carol-0123456789 base=https://gw.example/anthropic model=carol-model");
        assertThat(daveRunGit(dave)).contains("git=deployment-git");
        assertThat(runAndRead(dave, "ask")).isEqualTo("key=deployment-key base=None model=None");
        Map<String, Object> carolFull =
                call(carol, "POST", "/api/v1/runs", Map.of("workflowId", "ask", "projectId", "p1", "input", "x"), 201);
        String carolFullId = (String) carolFull.get("id");
        awaitOutput(carol, carolFullId);
        assertThat(gitOutput(carol, carolFullId)).contains("git=carol-git-token");

        // a scheduled run works for the person who created the schedule
        Map<String, Object> schedule = call(
                carol,
                "POST",
                "/api/v1/projects/p1/schedules",
                Map.of("name", "定时", "cron", "0 3 * * *", "zone", "UTC", "workflowId", "ask", "enabled", true),
                201);
        call(dave, "POST", "/api/v1/projects/p1/schedules/" + schedule.get("id") + "/run", null, 200);
        Map<String, Object> fired = call(dave, "GET", "/api/v1/projects/p1/schedules/" + schedule.get("id"), null, 200);
        assertThat(awaitOutput(dave, (String) fired.get("lastRunId"))).startsWith("key=sk-carol-0123456789");

        // a partial update keeps the stored key; deleting falls back to the deployment's
        call(carol, "PUT", "/api/v1/me/credentials/executor:claude-code", Map.of("baseUrl", ""), 200);
        assertThat(runAndRead(carol, "ask")).isEqualTo("key=sk-carol-0123456789 base=None model=carol-model");
        call(carol, "DELETE", "/api/v1/me/credentials/executor:claude-code", null, 204);
        call(carol, "DELETE", "/api/v1/me/credentials/executor:claude-code", null, 404);
        assertThat(runAndRead(carol, "ask")).isEqualTo("key=deployment-key base=None model=None");

        // a personal endpoint never receives the deployment's key
        call(
                carol,
                "PUT",
                "/api/v1/me/credentials/executor:claude-code",
                Map.of("variables", Map.of("ANTHROPIC_BASE_URL", "https://collector.example")),
                200);
        assertThat(runAndRead(carol, "ask")).isEqualTo("key=None base=https://collector.example model=None");
        call(carol, "DELETE", "/api/v1/me/credentials/executor:claude-code", null, 204);

        // a ciphertext moved onto another account does not open there
        jdbc.sql("INSERT INTO user_credentials (user_id, kind, secret, public_info, updated_at)"
                        + " SELECT :dave, kind, secret, public_info, updated_at FROM user_credentials"
                        + " WHERE user_id = :carol AND kind = 'git:git.example.com'")
                .param("dave", daveId)
                .param("carol", carolId)
                .update();
        assertThat((List<Object>) call(dave, "GET", "/api/v1/me/credentials", null, 200))
                .isEmpty();
        assertThat(daveRunGit(dave)).contains("git=deployment-git");
    }

    @SuppressWarnings("unchecked")
    String daveRunGit(RestClient dave) {
        Map<String, Object> run =
                call(dave, "POST", "/api/v1/runs", Map.of("workflowId", "ask", "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        awaitOutput(dave, id);
        return gitOutput(dave, id);
    }

    @SuppressWarnings("unchecked")
    String gitOutput(RestClient c, String runId) {
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                                                        call(c, "GET", "/api/v1/runs/" + runId, null, 200))
                                                .get("nodes"))
                                        .get("git"))
                                .get("status")));
        Map<String, Object> run = call(c, "GET", "/api/v1/runs/" + runId, null, 200);
        return (String) ((Map<String, Object>) ((Map<String, Object>) run.get("nodes")).get("git")).get("output");
    }

    @Test
    void theAssistantActingForAPersonCannotCreateDurableCredentials() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        String erinId = user(admin, "erin");
        call(admin, "PUT", "/api/v1/projects/p2/members/" + erinId, Map.of("role", "developer"), 200);
        jdbc.sql("INSERT INTO assistant_sessions (id, project_id, owner_id, status, payload, created_at, updated_at)"
                        + " VALUES ('chat_x', 'p2', :u, 'generating', '{}', :now, :now)")
                .param("u", erinId)
                .param("now", OffsetDateTime.now())
                .update();
        String token = runTokens.issue(
                RunTokens.Kind.CONVERSATION,
                RunTokens.CONVERSATION_PREFIX + "chat_x",
                "p2",
                null,
                null,
                erinId,
                Duration.ofMinutes(5));
        RestClient assistant = client(token);
        // it acts for erin in her project...
        call(assistant, "GET", "/api/v1/me", null, 200);
        call(assistant, "GET", "/api/v1/projects/p2/requirements", null, 200);
        // ...but cannot mint access that outlives the turn
        call(assistant, "POST", "/api/v1/me/tokens", Map.of("name", "persist"), 403);
        call(assistant, "GET", "/api/v1/me/tokens", null, 403);
        call(assistant, "GET", "/api/v1/me/credentials", null, 403);
        call(assistant, "PUT", "/api/v1/me/credentials/git:git.example.com", Map.of("token", "t"), 403);
        call(assistant, "POST", "/api/v1/runners", Map.of("name", "mine", "shared", false), 403);
        call(
                assistant,
                "POST",
                "/api/v1/me/password",
                Map.of("currentPassword", "erin-password-1", "newPassword", "changed-password-1"),
                403);
        // erin herself can
        call(client(login("erin")), "POST", "/api/v1/me/tokens", Map.of("name", "mine"), 201);
    }
}
