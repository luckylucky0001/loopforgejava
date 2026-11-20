package io.github.loopforge4j.server.workspace;

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

/** People browse, diff, search and edit a run's workspace; agent-written Git config never runs here. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RunWorkspaceIntegrationTest {

    static final String TOKEN = "workspace-token-0123456789-abcdefgh";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-workspace-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:workspace;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
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

    @SuppressWarnings("unchecked")
    String waitingRun(RestClient c, String workflow, String script) {
        call(
                c,
                "PUT",
                "/api/v1/workflows/" + workflow,
                Map.of(
                        "name",
                        workflow,
                        "status",
                        "published",
                        "projectId",
                        "p1",
                        "nodes",
                        List.of(
                                Map.of("id", "work", "type", "script", "script", script),
                                Map.of("id", "review", "type", "approval")),
                        "edges",
                        List.of(Map.of("source", "work", "target", "review"))),
                200);
        Map<String, Object> run =
                call(c, "POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of("waiting", "failed")
                        .contains(
                                ((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        Map<String, Object> done = call(c, "GET", "/api/v1/runs/" + id, null, 200);
        assertThat(done).as(() -> Json.write(done.get("nodes"))).containsEntry("status", "waiting");
        return id;
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

    @Test
    @SuppressWarnings("unchecked")
    void peopleBrowseDiffSearchAndEditTheWorkspace() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        RestClient vic = client(member(admin, "vic", "viewer"));
        RestClient dev = client(member(admin, "dev", "developer"));
        String script = "git init -q . && git config user.email a@b.c && git config user.name tester"
                + " && echo v1 > app.txt && mkdir -p src && echo 'hello world' > src/main.txt"
                + " && git add app.txt src && git commit -qm init"
                + " && echo v2 > app.txt && echo new > added.txt && echo SECRET=1 > .env"
                + " && mkdir -p node_modules/x && echo x > node_modules/x/i.js && ln -s /etc/hostname link";
        String runId = waitingRun(admin, "ws", script);
        String base = "/api/v1/runs/" + runId + "/workspace";

        Map<String, Object> summary = call(vic, "GET", base, null, 200);
        assertThat(summary).containsEntry("available", true).containsEntry("readOnly", true);
        assertThat((Map<String, Object>) call(dev, "GET", base, null, 200)).containsEntry("readOnly", false);
        assertThat((List<Map<String, Object>>) summary.get("changes"))
                .extracting(c -> c.get("path") + ":" + c.get("index") + c.get("worktree"))
                .contains("app.txt: M", "added.txt:??")
                .noneMatch(c -> c.startsWith(".env") || c.startsWith(".loopforge"));
        assertThat((List<String>) summary.get("repositories")).containsExactly("workspace");

        Map<String, Object> tree = call(vic, "GET", base + "/tree", null, 200);
        assertThat((List<Map<String, Object>>) tree.get("entries"))
                .extracting(e -> (String) e.get("path"))
                .contains("app.txt", "added.txt", "src", "src/main.txt")
                .noneMatch(p -> p.startsWith(".git")
                        || p.startsWith(".env")
                        || p.startsWith("node_modules")
                        || p.equals("link")
                        || p.startsWith(".loopforge"));

        Map<String, Object> file = call(vic, "GET", base + "/file?path=app.txt", null, 200);
        assertThat(file).containsEntry("content", "v2\n");
        assertThat((Map<String, Object>) call(vic, "GET", base + "/file?path=app.txt&ref=HEAD", null, 200))
                .containsEntry("content", "v1\n");
        call(vic, "GET", base + "/file?path=.env", null, 400);
        call(vic, "GET", base + "/file?path=../../etc/passwd", null, 400);
        call(vic, "GET", base + "/file?path=link", null, 400);
        call(vic, "GET", base + "/file?path=nope.txt", null, 404);
        call(vic, "GET", base + "/file?path=app.txt&ref=HEAD;rm", null, 400);

        Map<String, Object> diff = call(vic, "GET", base + "/diff?path=app.txt", null, 200);
        assertThat(diff).containsEntry("original", "v1\n").containsEntry("modified", "v2\n");
        assertThat((String) diff.get("diff")).contains("-v1", "+v2");
        assertThat((String) ((Map<String, Object>) call(vic, "GET", base + "/diff", null, 200)).get("diff"))
                .contains("app.txt");

        Map<String, Object> log = call(vic, "GET", base + "/log", null, 200);
        List<Map<String, Object>> commits = (List<Map<String, Object>>) log.get("commits");
        assertThat(commits)
                .singleElement()
                .satisfies(c -> assertThat(c).containsEntry("message", "init").containsKey("author"));
        Map<String, Object> commit =
                call(vic, "GET", base + "/commit?id=" + commits.getFirst().get("id"), null, 200);
        assertThat((List<Map<String, Object>>) commit.get("files"))
                .extracting(f -> f.get("path") + ":" + f.get("status"))
                .containsExactlyInAnyOrder("app.txt:A", "src/main.txt:A");

        Map<String, Object> search = call(vic, "GET", base + "/search?q=HELLO", null, 200);
        assertThat((List<Map<String, Object>>) search.get("results"))
                .singleElement()
                .satisfies(
                        r -> assertThat(r).containsEntry("path", "src/main.txt").containsEntry("line", 1));
        call(vic, "GET", base + "/search?q=h", null, 400);

        // edits: developers only, never over a change they did not see
        Map<String, Object> edit = Map.of("path", "app.txt", "content", "v3\n", "expectedSha256", file.get("sha256"));
        call(vic, "PUT", base + "/file", edit, 403);
        call(
                dev,
                "PUT",
                base + "/file",
                Map.of("path", "app.txt", "content", "x", "expectedSha256", "0".repeat(64)),
                400);
        Map<String, Object> written = call(dev, "PUT", base + "/file", edit, 200);
        assertThat((String) written.get("sha256")).hasSize(64);
        call(dev, "PUT", base + "/file", edit, 400);
        call(
                dev,
                "PUT",
                base + "/file",
                Map.of("path", "docs/notes.md", "content", "# 记录\n", "expectedSha256", "missing"),
                200);
        call(
                dev,
                "PUT",
                base + "/file",
                Map.of("path", ".git/config", "content", "x", "expectedSha256", "missing"),
                400);
        call(dev, "PUT", base + "/file", Map.of("path", "link", "content", "x", "expectedSha256", "missing"), 400);
        assertThat((Map<String, Object>) call(vic, "GET", base + "/file?path=docs/notes.md", null, 200))
                .containsEntry("content", "# 记录\n");
        assertThat(call(admin, "GET", "/api/v1/runs/" + runId + "/events?limit=500", null, 200)
                        .toString())
                .contains("workspace.changed", "app.txt 已被 dev 修改");
    }

    @Test
    @SuppressWarnings("unchecked")
    void gitConfigThatRunsProgramsIsNeverUsed() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        Path marker = data.resolve("pwned");
        String script = "git init -q . && echo a > a.txt && git -c user.email=a@b -c user.name=t add a.txt"
                + " && git -c user.email=a@b -c user.name=t commit -qm one && echo b > a.txt"
                + " && git config core.fsmonitor 'touch " + marker + "'";
        String runId = waitingRun(admin, "evil", script);
        String base = "/api/v1/runs/" + runId + "/workspace";
        Map<String, Object> summary = call(admin, "GET", base, null, 200);
        assertThat((String) summary.get("warning")).contains("fsmonitor");
        assertThat((List<Object>) summary.get("changes")).isEmpty();
        call(admin, "GET", base + "/log", null, 400);
        call(admin, "GET", base + "/diff?path=a.txt", null, 400);
        // plain files are still readable
        assertThat((Map<String, Object>) call(admin, "GET", base + "/file?path=a.txt", null, 200))
                .containsEntry("content", "b\n");
        assertThat(marker).doesNotExist();

        // a settled run's released workspace is reported, not served
        call(admin, "POST", "/api/v1/runs/" + runId + "/nodes/review/approve", Map.of(), 200);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) call(admin, "GET", "/api/v1/runs/" + runId, null, 200))
                                .get("status")));
        Map<String, Object> after = call(admin, "GET", base, null, 200);
        if (Boolean.FALSE.equals(after.get("available"))) {
            assertThat((String) after.get("reason")).contains("回收");
            call(admin, "GET", base + "/tree", null, 409);
        }
    }
}
