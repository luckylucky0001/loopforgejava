package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

/**
 * Iteration 2 end to end: repository catalog, run checkout on a work branch, push through the run's
 * Git environment, MR gate and delivery tracking against a fake GitHub REST API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GitDeliveryIntegrationTest {

    static final String TOKEN = "git-test-token-0123456789-abcdefghijkl";
    static final String GITHUB_TOKEN = "ghp_fake_token_for_tests";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    static final Path data;
    static final Path remote;
    static final HttpServer github;
    static final List<String> githubRequests = new CopyOnWriteArrayList<>();
    static final AtomicReference<String> prState = new AtomicReference<>("open");

    static {
        try {
            data = Files.createTempDirectory("lf-git-it");
            remote = data.resolve("remote.git");
            git(data, "init", "-q", "--bare", "--initial-branch=main", "remote.git");
            Path seed = Files.createDirectories(data.resolve("seed"));
            git(seed, "init", "-q", "--initial-branch=main");
            Files.writeString(seed.resolve("README.md"), "# app\n");
            git(seed, "add", "README.md");
            git(seed, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
            git(seed, "push", "-q", remote.toString(), "main");
            github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            github.createContext("/repos/acme/app/pulls", exchange -> {
                githubRequests.add(exchange.getRequestHeaders().getFirst("Authorization") + " "
                        + exchange.getRequestURI().getRawQuery());
                String query =
                        java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
                String head = query.replaceAll(".*head=acme:([^&]+).*", "$1");
                String sha = tip(head);
                String body = sha == null
                        ? "[]"
                        : "[{\"number\":7,\"html_url\":\"https://github.com/acme/app/pull/7\",\"state\":\""
                                + ("open".equals(prState.get()) ? "open" : "closed") + "\",\"merged_at\":"
                                + ("merged".equals(prState.get()) ? "\"2026-01-02T03:04:05Z\"" : "null")
                                + ",\"updated_at\":\"2026-01-01T00:00:00Z\",\"head\":{\"ref\":\"" + head
                                + "\",\"sha\":\"" + sha + "\"},\"base\":{\"ref\":\"main\"}}]";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            github.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:git;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.engine.release-workspace-on-finish", () -> "false");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.git.allow-local-remotes", () -> "true");
        registry.add("loopforge.git.https[0].host", () -> "127.0.0.1");
        registry.add("loopforge.git.https[0].token", () -> GITHUB_TOKEN);
        registry.add(
                "loopforge.github.api-url",
                () -> "http://127.0.0.1:" + github.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        github.stop(0);
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
    void runPushesWorkBranchAndTracksItsPullRequestUntilMerged() {
        put("/api/v1/repositories/app", Map.of("name", "App", "cloneUrl", remote.toString(), "githubRepo", "acme/app"));
        put(
                "/api/v1/agents/deliverer",
                Map.of(
                        "name", "交付",
                        "executorId", "mock",
                        "prompt", "交付",
                        "verification",
                                List.of(Map.of("id", "pr", "name", "PR 已创建", "mode", "mr", "onFailure", "fail"))));
        put(
                "/api/v1/workflows/deliver",
                Map.of(
                        "status", "published",
                        "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "push",
                                                "type",
                                                "script",
                                                "executionScope",
                                                "repositories",
                                                "script",
                                                "echo x > X.md && git add X.md && git commit -qm x"
                                                        + " && git push -q origin \"$LOOPFORGE_WORK_BRANCH\""),
                                        Map.of("id", "agent", "type", "agent", "agentId", "deliverer")),
                        "edges", List.of(Map.of("source", "push", "target", "agent"))));

        ResponseEntity<Map<String, Object>> started = api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workflowId", "deliver", "repositories", List.of(Map.of("repositoryId", "app"))))
                .retrieve()
                .toEntity(MAP);
        assertThat(started.getStatusCode().value()).isEqualTo(201);
        String runId = (String) started.getBody().get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen =
                ((List<Map<String, Object>>) started.getBody().get("repositories")).getFirst();
        String workBranch = (String) frozen.get("workBranch");
        assertThat(workBranch).startsWith("feature/fix-");

        await().atMost(Duration.ofSeconds(60))
                .until(() -> !"running".equals(run(runId).get("status")));
        assertThat(run(runId).get("status"))
                .as(() -> api.get()
                        .uri("/api/v1/runs/{id}/events?limit=5000", runId)
                        .retrieve()
                        .body(String.class))
                .isEqualTo("succeeded");
        assertThat(tip(workBranch)).isNotNull();
        List<Map<String, Object>> deliveries =
                api.get().uri("/api/v1/runs/{id}/deliveries", runId).retrieve().body(LIST);
        assertThat(deliveries)
                .singleElement()
                .satisfies(pr -> assertThat(pr)
                        .containsEntry("number", 7)
                        .containsEntry("state", "open")
                        .containsEntry("headBranch", workBranch));
        assertThat(githubRequests)
                .allSatisfy(r -> assertThat(r).startsWith("Bearer " + GITHUB_TOKEN + " "))
                .anySatisfy(r -> assertThat(r).contains("head=acme%3A" + workBranch.replace("/", "%2F")));

        prState.set("merged");
        List<Map<String, Object>> refreshed = api.post()
                .uri("/api/v1/runs/{id}/deliveries/refresh", runId)
                .retrieve()
                .body(LIST);
        assertThat(refreshed).singleElement().satisfies(pr -> assertThat(pr).containsEntry("state", "merged"));

        // credentials never reach run state, events or the workspace
        String state = api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(String.class);
        String events = api.get()
                .uri("/api/v1/runs/{id}/events?limit=5000", runId)
                .retrieve()
                .body(String.class);
        assertThat(state + events).doesNotContain(GITHUB_TOKEN);
    }

    @Test
    void repositoriesAreValidatedAndUnknownOnesAreRejectedAtStart() {
        ResponseEntity<Map<String, Object>> embedded = api.put()
                .uri("/api/v1/repositories/bad")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("cloneUrl", "https://user:secret@github.com/acme/app.git"))
                .retrieve()
                .toEntity(MAP);
        assertThat(embedded.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(embedded.getBody())).doesNotContain("secret");

        put(
                "/api/v1/workflows/noop",
                Map.of("status", "published", "nodes", List.of(Map.of("id", "a", "type", "script", "script", "true"))));
        ResponseEntity<Map<String, Object>> unknown = api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("workflowId", "noop", "repositories", List.of(Map.of("repositoryId", "ghost"))))
                .retrieve()
                .toEntity(MAP);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<Map<String, Object>> badBranch = api.post()
                .uri("/api/v1/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "workflowId",
                        "noop",
                        "repositories",
                        List.of(Map.of("repositoryId", "app2", "workBranch", "main"))))
                .retrieve()
                .toEntity(MAP);
        assertThat(badBranch.getStatusCode().value()).isIn(404, 409);
    }

    @Test
    void repositoryCrudAndCatalogExport() {
        put(
                "/api/v1/repositories/lib",
                Map.of("cloneUrl", "https://github.com/acme/lib.git", "defaultBranch", "trunk"));
        Map<String, Object> lib =
                api.get().uri("/api/v1/repositories/lib").retrieve().body(MAP);
        assertThat(lib)
                .containsEntry("provider", "github")
                .containsEntry("githubRepo", "acme/lib")
                .containsEntry("defaultBranch", "trunk");
        Map<String, Object> export =
                api.get().uri("/api/v1/catalog/export").retrieve().body(MAP);
        assertThat(String.valueOf(export.get("repositories"))).contains("acme/lib");
        assertThat(api.delete()
                        .uri("/api/v1/repositories/lib")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(api.get()
                        .uri("/api/v1/repositories/lib")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    private void put(String path, Map<String, Object> body) {
        ResponseEntity<String> response = api.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
    }

    private Map<String, Object> run(String runId) {
        return api.get().uri("/api/v1/runs/{id}", runId).retrieve().body(MAP);
    }

    private static String tip(String branch) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--verify", "-q", "refs/heads/" + branch)
                    .directory(remote.toFile())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor() == 0 && !out.isEmpty() ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", command) + ": " + out);
        }
    }
}
