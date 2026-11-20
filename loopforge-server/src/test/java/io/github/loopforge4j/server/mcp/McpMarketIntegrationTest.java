package io.github.loopforge4j.server.mcp;

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

/** The MCP market, project installs and personal servers; roles bind market ids. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpMarketIntegrationTest {

    static final String TOKEN = "mcp-market-token-0123456789-abcdefg";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-mcp-market-it");
            Path cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import json, sys
                    argv = sys.argv[1:]
                    sys.stdin.read()
                    config = json.load(open(argv[argv.index("--mcp-config") + 1]))["mcpServers"]
                    seen = {name: {"url": s.get("url"), "auth": s.get("headers", {}).get("Authorization")}
                            for name, s in config.items() if not name.startswith("loopforge-")}
                    json.dump(seen, open("mcp-seen.json", "w"), sort_keys=True)
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": "m"}), flush=True)
                    print(json.dumps({"type": "result", "session_id": "m", "is_error": False, "result": "ok",
                                      "usage": {"input_tokens": 1, "output_tokens": 1}}), flush=True)
                    """);
            cli.toFile().setExecutable(true);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:mcp-market;DB_CLOSE_DELAY=-1");
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

    RestClient member(RestClient admin, String name, String project, String role) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        if (project != null) {
            call(
                    admin,
                    "PUT",
                    "/api/v1/projects/" + project + "/members/" + created.get("id"),
                    Map.of("role", role),
                    200);
        }
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return client((String) login.get("token"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void marketInstallsAndPersonalServersReachOnlyTheirWork() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        call(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        RestClient mia = member(admin, "mia", "p1", "maintainer");
        RestClient alice = member(admin, "alice", "p1", "developer");
        RestClient bob = member(admin, "bob", "p2", "maintainer");

        Map<String, Object> market = call(alice, "GET", "/api/v1/mcp-market?q=git", null, 200);
        assertThat((List<Map<String, Object>>) market.get("items"))
                .singleElement()
                .satisfies(i -> assertThat(i).containsEntry("id", "github").containsEntry("authType", "github-token"));
        assertThat((List<Object>)
                        ((Map<String, Object>) call(alice, "GET", "/api/v1/mcp-market?q=nothing-like-it", null, 200))
                                .get("items"))
                .isEmpty();

        // project installs are for maintainers, once per project
        call(alice, "POST", "/api/v1/mcp-market/install", Map.of("marketId", "github", "projectId", "p1"), 403);
        call(mia, "POST", "/api/v1/mcp-market/install", Map.of("marketId", "nope", "projectId", "p1"), 404);
        call(mia, "POST", "/api/v1/mcp-market/install", Map.of("marketId", "github"), 422);
        Map<String, Object> installed =
                call(mia, "POST", "/api/v1/mcp-market/install", Map.of("marketId", "github", "projectId", "p1"), 201);
        assertThat(installed).containsEntry("id", "mcp-github-project-p1");
        assertThat((Map<String, Object>) installed.get("placement"))
                .containsEntry("scope", "project")
                .containsEntry("projectId", "p1")
                .containsEntry("marketId", "github");
        call(mia, "POST", "/api/v1/mcp-market/install", Map.of("marketId", "github", "projectId", "p1"), 409);
        assertThat((List<Object>) call(alice, "GET", "/api/v1/projects/p1/mcp-servers", null, 200))
                .hasSize(1);
        call(bob, "GET", "/api/v1/projects/p1/mcp-servers", null, 403);
        assertThat((List<Map<String, Object>>) call(bob, "GET", "/api/v1/mcp-servers", null, 200))
                .noneMatch(m -> "mcp-github-project-p1".equals(m.get("id")));
        call(bob, "GET", "/api/v1/mcp-servers/mcp-github-project-p1", null, 404);

        // personal servers: HTTP only, visible to their owner
        call(alice, "POST", "/api/v1/me/mcp-servers", Map.of("name", "notes", "url", "file:///etc/passwd"), 422);
        // secrets are never stored in a server definition
        call(
                alice,
                "POST",
                "/api/v1/me/mcp-servers",
                Map.of(
                        "name",
                        "notes",
                        "url",
                        "https://notes.example/mcp",
                        "headers",
                        Map.of("Authorization", "Bearer x")),
                422);
        Map<String, Object> mine = call(
                alice,
                "POST",
                "/api/v1/me/mcp-servers",
                Map.of("name", "notes", "url", "https://notes.example/mcp", "headers", Map.of("X-Team", "core")),
                201);
        String mineId = (String) mine.get("id");
        assertThat(mineId).isEqualTo("mcp-notes-personal-alice");
        call(alice, "POST", "/api/v1/me/mcp-servers", Map.of("name", "notes", "url", "https://notes.example/mcp"), 409);
        assertThat((List<Object>) call(bob, "GET", "/api/v1/me/mcp-servers", null, 200))
                .isEmpty();
        assertThat((List<Map<String, Object>>) call(bob, "GET", "/api/v1/mcp-servers", null, 200))
                .noneMatch(m -> mineId.equals(m.get("id")));
        call(bob, "DELETE", "/api/v1/me/mcp-servers/" + mineId, null, 404);

        // a role binds the market id and a personal server; each run gets what it may use
        call(
                admin,
                "PUT",
                "/api/v1/agents/dev",
                Map.of("name", "开发", "prompt", "写代码", "executorId", "claude", "mcpServers", List.of("github", mineId)),
                200);
        for (String p : List.of("p1", "p2")) {
            call(
                    admin,
                    "PUT",
                    "/api/v1/workflows/wf-" + p,
                    Map.of(
                            "name",
                            "wf",
                            "status",
                            "published",
                            "projectId",
                            p,
                            "nodes",
                            List.of(Map.of("id", "a", "type", "agent", "agentId", "dev"))),
                    200);
        }
        call(alice, "PUT", "/api/v1/me/credentials/git:github.com", Map.of("token", "ghp_alice"), 200);
        Map<String, Object> seen = seen(alice, "wf-p1", "p1");
        assertThat(seen).containsOnlyKeys("mcp-github-project-p1", mineId);
        assertThat((Map<String, Object>) seen.get("mcp-github-project-p1"))
                .containsEntry("url", "https://api.githubcopilot.com/mcp/")
                .containsEntry("auth", "Bearer ghp_alice");
        assertThat((Map<String, Object>) seen.get(mineId)).containsEntry("url", "https://notes.example/mcp");
        assertThat(seen(bob, "wf-p2", "p2")).isEmpty();
        call(alice, "DELETE", "/api/v1/me/mcp-servers/" + mineId, null, 204);
        call(bob, "DELETE", "/api/v1/projects/p1/mcp-servers/mcp-github-project-p1", null, 403);
        call(mia, "DELETE", "/api/v1/projects/p1/mcp-servers/mcp-github-project-p1", null, 204);
    }

    /** The MCP servers (url and authorization) the CLI of a run's agent was given. */
    @SuppressWarnings("unchecked")
    Map<String, Object> seen(RestClient c, String workflow, String project) {
        Map<String, Object> run = call(
                c, "POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", project, "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of("succeeded", "failed", "waiting")
                        .contains(
                                ((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        Map<String, Object> file =
                call(c, "GET", "/api/v1/runs/" + id + "/workspace/file?path=mcp-seen.json", null, 200);
        return Json.read((String) file.get("content"), Map.class);
    }
}
