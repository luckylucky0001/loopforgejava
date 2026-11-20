package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.executor.MockAgentExecutor;
import io.github.loopforge4j.core.runtime.git.GitCredentials;
import io.github.loopforge4j.runner.RunnerAgent;
import io.github.loopforge4j.runner.RunnerConfig;
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

/** A real execution machine claims and executes a member's run; the server only coordinates. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunnerIntegrationTest {

    static final String TOKEN = "runner-test-token-0123456789-abcdefg";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    static Path data;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        data = Files.createTempDirectory("lf-runner-it");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:runners;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root",
                () -> data.resolve("server-workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.engine.claim-interval", () -> "500ms");
    }

    @LocalServerPort
    int port;

    RestClient client(String token) {
        RestClient.Builder b = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {});
        if (token != null) {
            b.defaultHeader("Authorization", "Bearer " + token);
        }
        return b.build();
    }

    ResponseEntity<String> send(RestClient c, String method, String path, Object body) {
        var spec = c.method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }

    Map<String, Object> ok(RestClient c, String method, String path, Object body, int status) {
        ResponseEntity<String> r = send(c, method, path, body);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        return r.getBody() == null || !r.getBody().startsWith("{") ? Map.of() : Json.read(r.getBody(), Map.class);
    }

    String login(String user) {
        return (String) ok(
                        client(null),
                        "POST",
                        "/api/v1/auth/login",
                        Map.of("username", user, "password", user + "-password-1"),
                        200)
                .get("token");
    }

    @Test
    void aPersonalMachineExecutesItsOwnersRun() throws Exception {
        RestClient admin = client(TOKEN);
        ok(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        for (String user : List.of("alice", "bob")) {
            String id = (String)
                    ok(admin, "POST", "/api/v1/users", Map.of("username", user, "password", user + "-password-1"), 201)
                            .get("id");
            ok(admin, "PUT", "/api/v1/projects/p1/members/" + id, Map.of("role", "developer"), 200);
        }
        ok(admin, "PUT", "/api/v1/agents/dev", Map.of("executorId", "mock", "prompt", "实现"), 200);
        ok(
                admin,
                "PUT",
                "/api/v1/workflows/wf",
                Map.of(
                        "status",
                        "published",
                        "projectId",
                        "p1",
                        "nodes",
                        List.of(
                                Map.of("id", "code", "type", "agent", "agentId", "dev"),
                                Map.of("id", "review", "type", "approval"),
                                Map.of(
                                        "id",
                                        "ship",
                                        "type",
                                        "script",
                                        "script",
                                        "echo shipped-on-$LOOPFORGE_SANDBOX_ID > shipped.txt")),
                        "edges",
                        List.of(
                                Map.of("source", "code", "target", "review"),
                                Map.of("source", "review", "target", "ship"))),
                200);

        RestClient alice = client(login("alice"));
        RestClient bob = client(login("bob"));
        Map<String, Object> registered = ok(alice, "POST", "/api/v1/runners", Map.of("name", "alice-laptop"), 201);
        String runnerToken = (String) registered.get("token");
        String runnerId = (String) registered.get("id");
        assertThat(runnerToken).startsWith("lfx_");
        ok(alice, "POST", "/api/v1/runners", Map.of("name", "pool", "shared", true), 403);

        // the machine token is no API credential, and the machine API needs it
        assertThat(send(client(runnerToken), "GET", "/api/v1/projects", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(send(client(null), "GET", "/api/v1/runner-api/runs", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        // only the owner (or a pool) may run on a personal machine
        ok(bob, "POST", "/api/v1/runs", Map.of("workflowId", "wf", "projectId", "p1", "runnerId", runnerId), 403);

        Map<String, Object> run = ok(
                alice,
                "POST",
                "/api/v1/runs",
                Map.of("workflowId", "wf", "projectId", "p1", "runnerId", runnerId),
                201);
        String runId = (String) run.get("id");
        assertThat(run.get("status")).isEqualTo("queued");
        Thread.sleep(1500);
        assertThat(ok(alice, "GET", "/api/v1/runs/" + runId, null, 200).get("status"))
                .as("the server never executes it")
                .isEqualTo("queued");

        Path machine = data.resolve("alice-machine");
        ExecutorRegistry executors = ExecutorRegistry.withBuiltIns(CredentialProvider.NONE)
                .register(new MockAgentExecutor(Duration.ofMillis(5)));
        RunnerConfig config = new RunnerConfig(
                "http://localhost:" + port,
                runnerToken,
                machine,
                2,
                "mock",
                true,
                GitCredentials.NONE,
                Duration.ofMillis(300),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2));
        try (RunnerAgent agent = new RunnerAgent(config, executors).start()) {
            assertThat(agent.runnerId()).isEqualTo(runnerId);
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> "waiting"
                            .equals(ok(alice, "GET", "/api/v1/runs/" + runId, null, 200)
                                    .get("status")));
            List<Map<String, Object>> machines =
                    alice.get().uri("/api/v1/runners").retrieve().body(LIST);
            assertThat(machines).singleElement().satisfies(m -> {
                assertThat(m.get("online")).isEqualTo(true);
                assertThat((List<Object>) m.get("executors")).contains("mock");
            });
            // a person approves in the console; the machine continues
            ok(alice, "POST", "/api/v1/runs/" + runId + "/nodes/review/approve", Map.of(), 200);
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> "succeeded"
                            .equals(ok(alice, "GET", "/api/v1/runs/" + runId, null, 200)
                                    .get("status")));
        }
        assertThat(data.resolve("server-workspaces").resolve(runId)).doesNotExist();
        String events = alice.get()
                .uri("/api/v1/runs/{id}/events?limit=5000", runId)
                .retrieve()
                .body(String.class);
        // executed in the machine's workspace (released after success), reported through the server
        assertThat(events)
                .contains(
                        "node.succeeded",
                        "run.succeeded",
                        machine.resolve(runId).toString());
        assertThat(alice.get()
                        .uri("/api/v1/runs/{id}/artifacts", runId)
                        .retrieve()
                        .body(LIST))
                .isNotEmpty();

        // the machine sees only its own runs, and cannot rewrite what defines them
        RestClient machineApi = client(runnerToken);
        // a member may not start this local-sandbox SOP on the server, unlike on their own machine
        ok(alice, "POST", "/api/v1/runs", Map.of("workflowId", "wf", "projectId", "p1"), 403);
        Map<String, Object> plain =
                ok(admin, "POST", "/api/v1/runs", Map.of("workflowId", "wf", "projectId", "p1"), 201);
        assertThat(send(machineApi, "GET", "/api/v1/runner-api/runs/" + plain.get("id"), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        Map<String, Object> mine = Json.read(
                send(machineApi, "GET", "/api/v1/runner-api/runs/" + runId, null)
                        .getBody(),
                Map.class);
        Map<String, Object> context = new java.util.HashMap<>((Map<String, Object>) mine.get("context"));
        context.put("projectId", "elsewhere");
        Map<String, Object> forged = new java.util.HashMap<>(mine);
        forged.put("context", context);
        assertThat(send(
                                machineApi,
                                "POST",
                                "/api/v1/runner-api/runs/" + runId + "/commit",
                                Map.of(
                                        "expectedRevision",
                                        mine.get("revision"),
                                        "run",
                                        forged,
                                        "events",
                                        List.of(),
                                        "artifacts",
                                        List.of()))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        // nor may it point the run at other repositories (the server queries them with its own tokens)
        Map<String, Object> rerouted = new java.util.HashMap<>(mine);
        rerouted.put(
                "repositories",
                List.of(Map.of(
                        "repositoryId", "x",
                        "name", "x",
                        "provider", "github",
                        "cloneUrl", "https://github.com/victim/private.git",
                        "githubRepo", "victim/private",
                        "baseBranch", "main",
                        "workspaceDir", "x")));
        assertThat(send(
                                machineApi,
                                "POST",
                                "/api/v1/runner-api/runs/" + runId + "/commit",
                                Map.of(
                                        "expectedRevision",
                                        mine.get("revision"),
                                        "run",
                                        rerouted,
                                        "events",
                                        List.of(),
                                        "artifacts",
                                        List.of()))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(send(machineApi, "GET", "/api/v1/runner-api/catalog/workflows/wf", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        ok(admin, "POST", "/api/v1/runs/" + plain.get("id") + "/cancel", Map.of(), 200);

        // removing the machine revokes its token and fails the runs it would never finish
        Map<String, Object> stranded = ok(
                alice,
                "POST",
                "/api/v1/runs",
                Map.of("workflowId", "wf", "projectId", "p1", "runnerId", runnerId),
                201);
        ok(alice, "DELETE", "/api/v1/runners/" + runnerId, null, 204);
        assertThat(ok(alice, "GET", "/api/v1/runs/" + stranded.get("id"), null, 200))
                .containsEntry("status", "failed");
        assertThat(send(machineApi, "GET", "/api/v1/runner-api/runs", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMachineUploadsThePreviewItBuilt() throws Exception {
        RestClient admin = client(TOKEN);
        ok(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        String carolId = (String)
                ok(admin, "POST", "/api/v1/users", Map.of("username", "carol", "password", "carol-password-1"), 201)
                        .get("id");
        ok(admin, "PUT", "/api/v1/projects/p2/members/" + carolId, Map.of("role", "developer"), 200);
        ok(
                admin,
                "PUT",
                "/api/v1/workflows/fe",
                Map.of(
                        "status",
                        "published",
                        "projectId",
                        "p2",
                        "preview",
                        Map.of("enabled", true, "executorId", "mock"),
                        "nodes",
                        List.of(Map.of(
                                "id",
                                "impl",
                                "type",
                                "script",
                                "script",
                                "echo '{\"dependencies\": {\"vue\": \"3\"}}' > package.json"))),
                200);
        RestClient carol = client(login("carol"));
        Map<String, Object> registered = ok(carol, "POST", "/api/v1/runners", Map.of("name", "carol-pc"), 201);
        String runnerToken = (String) registered.get("token");
        Map<String, Object> run = ok(
                carol,
                "POST",
                "/api/v1/runs",
                Map.of("workflowId", "fe", "projectId", "p2", "runnerId", registered.get("id")),
                201);
        String runId = (String) run.get("id");
        RunnerConfig config = new RunnerConfig(
                "http://localhost:" + port,
                runnerToken,
                data.resolve("carol-machine"),
                1,
                "mock",
                true,
                GitCredentials.NONE,
                Duration.ofMillis(300),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2));
        ExecutorRegistry executors = ExecutorRegistry.withBuiltIns(CredentialProvider.NONE)
                .register(new MockAgentExecutor(Duration.ofMillis(5)));
        try (RunnerAgent agent = new RunnerAgent(config, executors).start()) {
            await().atMost(Duration.ofSeconds(30))
                    .until(() -> "succeeded"
                            .equals(ok(carol, "GET", "/api/v1/runs/" + runId, null, 200)
                                    .get("status")));
        }
        Map<String, Object> preview = ok(carol, "GET", "/api/v1/runs/" + runId + "/preview", null, 200);
        assertThat(preview)
                .containsEntry("status", "ready")
                .containsEntry("nodeId", "af-preview-build")
                .containsEntry("fileCount", 1);
        String url = (String) ok(carol, "POST", "/api/v1/runs/" + runId + "/preview/session", Map.of(), 200)
                .get("url");
        assertThat(client(null).get().uri(url).retrieve().toEntity(String.class).getBody())
                .contains("frontend-preview");

        // uploads are checked: no unsafe names, only the machine's own runs
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("../escape.html"));
            zip.write("x".getBytes());
            zip.closeEntry();
        }
        ResponseEntity<String> evil = client(runnerToken)
                .put()
                .uri("/api/v1/runner-api/runs/" + runId + "/preview?path=dist&index=index.html")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bytes.toByteArray())
                .retrieve()
                .toEntity(String.class);
        assertThat(evil.getStatusCode().value()).as(evil.getBody()).isEqualTo(422);
        assertThat(ok(carol, "GET", "/api/v1/runs/" + runId + "/preview", null, 200))
                .containsEntry("status", "failed");
        assertThat(Files.exists(data.resolve("escape.html"))).isFalse();
        Map<String, Object> other =
                ok(admin, "POST", "/api/v1/runs", Map.of("workflowId", "fe", "projectId", "p2"), 201);
        assertThat(client(runnerToken)
                        .put()
                        .uri("/api/v1/runner-api/runs/" + other.get("id") + "/preview?path=dist&index=index.html")
                        .contentType(MediaType.parseMediaType("application/zip"))
                        .body(bytes.toByteArray())
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        ok(admin, "POST", "/api/v1/runs/" + other.get("id") + "/cancel", null, 200);
    }
}
