package io.github.loopforge4j.server.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.work.Requirement;
import io.github.loopforge4j.server.work.RequirementStore;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

/** Pulls, imports, pushes and write-back against fake GitHub, GitLab/工蜂 and TAPD APIs. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrackerIntegrationTest {

    static final String TOKEN = "tracker-test-token-0123456789-abcdef";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    static final HttpServer fake;
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final List<String> comments = new CopyOnWriteArrayList<>();
    static volatile String githubTitle = "登录页报错";
    static volatile boolean poolHasBug = true;

    static {
        try {
            fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fake.createContext("/gh", ex -> {
                calls.add("gh " + ex.getRequestMethod() + " " + ex.getRequestURI() + " "
                        + ex.getRequestHeaders().getFirst("Authorization"));
                String path = ex.getRequestURI().getPath();
                if (ex.getRequestMethod().equals("POST") && path.endsWith("/comments")) {
                    comments.add("github " + body(ex));
                    reply(ex, 201, Map.of("id", 1));
                } else if (ex.getRequestMethod().equals("POST")) {
                    Map<?, ?> in = Json.read(body(ex), Map.class);
                    reply(ex, 201, issue(110, 10, (String) in.get("title"), "open"));
                } else {
                    List<Object> items = new ArrayList<>();
                    items.add(issue(101, 1, githubTitle, "open"));
                    items.add(issue(102, 2, "很久以前关闭的", "closed"));
                    Map<String, Object> pr = new java.util.HashMap<>(issue(103, 3, "a pull request", "open"));
                    pr.put("pull_request", Map.of("url", "x"));
                    items.add(pr);
                    reply(ex, 200, items);
                }
            });
            fake.createContext("/gl", ex -> {
                calls.add(
                        "gl " + ex.getRequestMethod() + " " + ex.getRequestURI().getRawPath() + " "
                                + ex.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
                reply(
                        ex,
                        200,
                        List.of(Map.of(
                                "id",
                                501,
                                "iid",
                                5,
                                "title",
                                "工蜂 issue",
                                "description",
                                "d",
                                "state",
                                "opened",
                                "priority",
                                3,
                                "web_url",
                                "https://gitlab.test/group/app/issues/5",
                                "updated_at",
                                "2026-11-20T08:00:00+08:00")));
            });
            fake.createContext("/tapd", ex -> {
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                calls.add("tapd " + ex.getRequestMethod() + " " + ex.getRequestURI() + " " + auth);
                if (!("Basic " + Base64.getEncoder().encodeToString("api-u:api-p".getBytes())).equals(auth)) {
                    reply(ex, 401, Map.of());
                    return;
                }
                String path = ex.getRequestURI().getPath();
                String query = ex.getRequestURI().getQuery() == null
                        ? ""
                        : ex.getRequestURI().getQuery();
                if (path.endsWith("/comments")) {
                    comments.add("tapd " + body(ex));
                    reply(ex, 200, Map.of("status", 1, "data", Map.of("Comment", Map.of("id", "9"))));
                } else if (path.endsWith("/stories") && query.contains("id=333")) {
                    reply(
                            ex,
                            200,
                            Map.of(
                                    "status",
                                    1,
                                    "data",
                                    List.of(Map.of(
                                            "Story",
                                            Map.of(
                                                    "id",
                                                    "333",
                                                    "name",
                                                    "导入的需求",
                                                    "description",
                                                    "来自链接",
                                                    "status",
                                                    "planned",
                                                    "owner",
                                                    "Alice(alice);bob;",
                                                    "priority",
                                                    "High")))));
                } else if (path.endsWith("/stories")) {
                    reply(
                            ex,
                            200,
                            Map.of(
                                    "status",
                                    1,
                                    "data",
                                    List.of(Map.of(
                                            "Story",
                                            Map.of(
                                                    "id",
                                                    "1001",
                                                    "name",
                                                    "迭代需求",
                                                    "description",
                                                    "",
                                                    "status",
                                                    "开发中",
                                                    "owner",
                                                    "alice;",
                                                    "priority",
                                                    "Middle",
                                                    "modified",
                                                    "2026-11-20 10:00:00")))));
                } else if (path.endsWith("/bugs")) {
                    reply(
                            ex,
                            200,
                            Map.of(
                                    "status",
                                    1,
                                    "data",
                                    poolHasBug
                                            ? List.of(Map.of(
                                                    "Bug",
                                                    Map.of(
                                                            "id",
                                                            "2001",
                                                            "title",
                                                            "迭代缺陷",
                                                            "status",
                                                            "new",
                                                            "severity",
                                                            "serious")))
                                            : List.of()));
                } else {
                    reply(ex, 200, Map.of("status", 0, "info", "unknown"));
                }
            });
            fake.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Map<String, Object> issue(int id, int number, String title, String state) {
        return Map.of(
                "id",
                id,
                "number",
                number,
                "title",
                title,
                "body",
                "正文 " + number,
                "state",
                state,
                "html_url",
                "https://github.com/acme/app/issues/" + number,
                "updated_at",
                "2026-11-20T00:00:00Z",
                "assignee",
                Map.of("login", "alice"));
    }

    static String body(HttpExchange ex) throws java.io.IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static void reply(HttpExchange ex, int status, Object body) throws java.io.IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @AfterAll
    static void stop() {
        fake.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-tracker");
        String base = "http://127.0.0.1:" + fake.getAddress().getPort();
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:tracker;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.github.api-url", () -> base + "/gh");
        registry.add("loopforge.git.https[0].host", () -> "127.0.0.1");
        registry.add("loopforge.git.https[0].token", () -> "gh-token");
        registry.add("loopforge.git.https[1].host", () -> "gitlab.test");
        registry.add("loopforge.git.https[1].token", () -> "gl-token");
        registry.add("loopforge.gitlab[0].host", () -> "gitlab.test");
        registry.add("loopforge.gitlab[0].api-url", () -> base + "/gl/api/v4");
        registry.add("loopforge.tapd.api-url", () -> base + "/tapd");
        registry.add("loopforge.tapd.api-user", () -> "api-u");
        registry.add("loopforge.tapd.api-password", () -> "api-p");
        registry.add("loopforge.trackers.auto-sync", () -> "false");
    }

    @LocalServerPort
    int port;

    @Autowired
    RequirementStore requirementStore;

    @Autowired
    TrackerService trackerService;

    @Autowired
    TrackerStore trackerStore;

    RestClient api() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    String send(String method, String path, Object body, int status) {
        var spec = api().method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> response = spec.retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().value())
                .as(method + " " + path + ": " + response.getBody())
                .isEqualTo(status);
        return response.getBody();
    }

    List<Map<String, Object>> requirements() {
        return api().get().uri("/api/v1/projects/p1/requirements").retrieve().body(LIST);
    }

    Map<String, Object> bySource(String source, String title) {
        return requirements().stream()
                .filter(r -> source.equals(r.get("source")) && title.equals(r.get("title")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(source + " " + title + " not in " + requirements()));
    }

    @Test
    void pullsImportsPushesAndWritesBack() {
        send("PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        send("PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        send("POST", "/api/v1/users", Map.of("username", "alice", "password", "alice-password-1"), 201);
        send(
                "PUT",
                "/api/v1/repositories/gh-app",
                Map.of("name", "app", "cloneUrl", "https://github.com/acme/app.git", "projectId", "p1"),
                200);
        send(
                "PUT",
                "/api/v1/repositories/gl-app",
                Map.of(
                        "name",
                        "gl",
                        "provider",
                        "gitlab",
                        "cloneUrl",
                        "https://gitlab.test/group/app.git",
                        "projectId",
                        "p1"),
                200);
        send(
                "PUT",
                "/api/v1/repositories/other",
                Map.of("name", "o", "cloneUrl", "https://github.com/acme/other.git", "projectId", "p2"),
                200);

        // another project's repository and half a TAPD pool are refused
        send("PUT", "/api/v1/projects/p1/tracker", Map.of("issueRepositoryIds", List.of("other")), 422);
        send("PUT", "/api/v1/projects/p1/tracker", Map.of("tapdWorkspaceId", "111"), 422);
        send(
                "PUT",
                "/api/v1/projects/p1/tracker",
                Map.of(
                        "issueRepositoryIds",
                        List.of("gh-app", "gl-app"),
                        "tapdWorkspaceId",
                        "111",
                        "tapdIterationId",
                        "222",
                        "autoSync",
                        true,
                        "writeBack",
                        true),
                200);

        List<Map<String, Object>> logs =
                Json.read(send("POST", "/api/v1/projects/p1/tracker/sync", null, 200), List.class);
        assertThat(logs)
                .hasSize(3)
                .allSatisfy(l -> assertThat(l.get("status")).as(l.toString()).isEqualTo("ok"));
        assertThat(calls)
                .anyMatch(c -> c.startsWith("gh GET /gh/repos/acme/app/issues") && c.endsWith("Bearer gh-token"));
        assertThat(calls)
                .anyMatch(c -> c.startsWith("gl GET /gl/api/v4/projects/group%2Fapp/issues") && c.endsWith("gl-token"));

        Map<String, Object> gh = bySource("github", "登录页报错");
        assertThat(gh)
                .containsEntry("status", "todo")
                .containsEntry("assignee", "alice")
                .containsEntry("repositoryIds", List.of("gh-app"));
        assertThat((Map<String, Object>) gh.get("external"))
                .containsEntry("number", 1)
                .containsEntry("url", "https://github.com/acme/app/issues/1");
        assertThat(requirements())
                .noneMatch(r -> "很久以前关闭的".equals(r.get("title")) || "a pull request".equals(r.get("title")));
        assertThat(bySource("gitlab", "工蜂 issue")).containsEntry("priority", "high");
        assertThat(bySource("tapd", "迭代需求"))
                .containsEntry("status", "in_progress")
                .containsEntry("assignee", "alice");
        assertThat(bySource("tapd", "迭代缺陷")).containsEntry("status", "backlog").containsEntry("priority", "high");

        // the platform owns the lifecycle after intake; pulls refresh content only
        int ghNumber = (int) gh.get("number");
        Map<String, Object> edit = new java.util.HashMap<>(gh);
        edit.put("status", "in_review");
        send("PUT", "/api/v1/projects/p1/requirements/" + ghNumber, edit, 200);
        githubTitle = "登录页报错（已复现）";
        poolHasBug = false;
        send("POST", "/api/v1/projects/p1/tracker/sync", null, 200);
        assertThat(bySource("github", "登录页报错（已复现）"))
                .containsEntry("status", "in_review")
                .containsEntry("number", ghNumber);
        // the bug left the iteration before anyone started it
        assertThat(requirements()).noneMatch(r -> "迭代缺陷".equals(r.get("title")));
        assertThat(requirements().stream().filter(r -> "github".equals(r.get("source"))))
                .hasSize(1);

        // a pasted TAPD link is imported and kept although it is not in the pool
        Map<String, Object> imported = Json.read(
                send(
                        "POST",
                        "/api/v1/projects/p1/tracker/import-tapd",
                        Map.of("url", "需求：导入的需求 https://www.tapd.cn/111/prong/stories/view/333"),
                        200),
                Map.class);
        assertThat(imported)
                .containsEntry("title", "导入的需求")
                .containsEntry("status", "todo")
                .containsEntry("priority", "high");
        send(
                "POST",
                "/api/v1/projects/p1/tracker/import-tapd",
                Map.of("url", "https://evil.example/111/prong/stories/view/333"),
                422);
        send("POST", "/api/v1/projects/p1/tracker/sync", null, 200);
        assertThat(requirements()).anyMatch(r -> "导入的需求".equals(r.get("title")));

        // a local requirement is pushed to GitHub once
        Map<String, Object> local = Json.read(
                send("POST", "/api/v1/projects/p1/requirements", Map.of("title", "本地需求", "description", "说明"), 201),
                Map.class);
        int localNumber = (int) local.get("number");
        Map<String, Object> pushed = Json.read(
                send(
                        "POST",
                        "/api/v1/projects/p1/requirements/" + localNumber + "/external",
                        Map.of("target", "gh-app"),
                        200),
                Map.class);
        assertThat((Map<String, Object>) pushed.get("external"))
                .containsEntry("number", 10)
                .containsEntry("imported", true);
        send("POST", "/api/v1/projects/p1/requirements/" + localNumber + "/external", Map.of("target", "gh-app"), 409);

        // deliveries are written back once per pull request
        Requirement synced = requirementStore.find("p1", ghNumber).orElseThrow();
        PullRequest pr = new PullRequest(
                "gh-app", 7, "https://github.com/acme/app/pull/7", "open", "feature/x", "main", "abc", null, null);
        Requirement delivered = requirementStore
                .update(
                        synced.id(),
                        r -> new Requirement(
                                r.id(),
                                r.projectId(),
                                r.number(),
                                r.title(),
                                r.description(),
                                r.status(),
                                r.priority(),
                                r.assignee(),
                                r.workflowId(),
                                r.repositoryIds(),
                                r.baseBranch(),
                                r.runIds(),
                                r.latestRunId(),
                                List.of(pr),
                                r.source(),
                                r.external(),
                                r.createdBy(),
                                r.createdAt(),
                                r.updatedAt()))
                .orElseThrow();
        trackerService.deliveriesChanged(delivered);
        await().atMost(Duration.ofSeconds(10)).until(() -> comments.stream().anyMatch(c -> c.contains("pull/7")));
        trackerService.deliveriesChanged(requirementStore.find("p1", ghNumber).orElseThrow());
        trackerService.writeBack(delivered.id(), "p1", ghNumber);
        assertThat(comments.stream().filter(c -> c.contains("pull/7"))).hasSize(1);
        assertThat(requirementStore
                        .find("p1", ghNumber)
                        .orElseThrow()
                        .external()
                        .notified())
                .containsExactly("https://github.com/acme/app/pull/7");

        assertThat(api().get()
                        .uri("/api/v1/projects/p1/tracker/logs")
                        .retrieve()
                        .body(LIST))
                .hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    void oneInstanceHoldsTheSyncLease() {
        long now = System.currentTimeMillis() + 1_000_000;
        assertThat(trackerStore.acquireLease(now, now + 60_000)).isTrue();
        assertThat(trackerStore.acquireLease(now + 1, now + 120_000)).isFalse();
        assertThat(trackerStore.acquireLease(now + 60_001, now + 180_000)).isTrue();
    }

    @Test
    void tapdLinksAreRecognisedStrictly() {
        assertThat(TapdLinks.parse("https://www.tapd.cn/tapd_fe/111/story/detail/1122334455", java.util.Set.of()))
                .isEqualTo(new TapdLinks.Ref("111", "1122334455", "story"));
        assertThat(TapdLinks.parse("缺陷 https://www.tapd.cn/111/bugtrace/bugs/view/2002?x=1", java.util.Set.of())
                        .kind())
                .isEqualTo("bug");
        assertThat(TapdLinks.parse(
                        "https://www.tapd.cn/111/prong/stories/stories_list?dialog_preview_id=task_77",
                        java.util.Set.of()))
                .isEqualTo(new TapdLinks.Ref("111", "77", "task"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TapdLinks.parse(
                        "https://www.tapd.cn/111/prong/stories/view/1 https://www.tapd.cn/111/prong/stories/view/2",
                        java.util.Set.of()))
                .hasMessageContaining("多个");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TapdLinks.parse("https://nottapd.cn/111/prong/stories/view/1", java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
