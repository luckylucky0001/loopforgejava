package io.github.loopforge4j.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
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
 * Requirement clarification end to end: a fake analyst CLI reads the read-only checkout of the
 * requirement's repository, asks a question, recovers from an invalid reply, concludes; the
 * conclusion lands in the description and as a comment on the linked GitHub issue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClarificationIntegrationTest {

    static final String TOKEN = "clarify-test-token-0123456789-abcdef";
    static final Path data;
    static final Path remote;
    static final HttpServer github;
    static final List<String> comments = new CopyOnWriteArrayList<>();

    static {
        try {
            data = Files.createTempDirectory("lf-clarify-it");
            remote = data.resolve("remote.git");
            git(data, "init", "-q", "--bare", "--initial-branch=main", "remote.git");
            Path seed = Files.createDirectories(data.resolve("seed"));
            git(seed, "init", "-q", "--initial-branch=main");
            Files.createDirectories(seed.resolve("src"));
            Files.writeString(seed.resolve("src/Login.java"), "class Login { /* shows errors in a toast */ }\n");
            git(seed, "add", ".");
            git(seed, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");
            git(seed, "push", "-q", remote.toString(), "main");
            Files.writeString(data.resolve("deploy_key"), "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n");

            Path cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/usr/bin/env python3
                    import glob, json, sys, time, traceback
                    sys.excepthook = lambda *e: (open("err.txt", "w").write("".join(traceback.format_exception(*e))), sys.exit(1))
                    argv = sys.argv[1:]
                    prompt = sys.stdin.read()
                    resume = argv[argv.index("--resume") + 1] if "--resume" in argv else None
                    session = resume or "c-1"
                    print(json.dumps({"type": "system", "subtype": "init", "session_id": session}), flush=True)
                    def result(text):
                        print(json.dumps({"type": "result", "session_id": session, "result": text, "is_error": False,
                                          "usage": {"input_tokens": 900, "output_tokens": 40}}), flush=True)
                    def fenced(obj):
                        return "分析如下。\\n```json\\n" + json.dumps(obj, ensure_ascii=False) + "\\n```"
                    login = glob.glob("*/src/Login.java")
                    if "睡一会" in prompt:
                        time.sleep(60)
                    import os
                    planted = os.path.exists(".loopforge/ssh/id_loopforge")
                    if resume is None:
                        code = open(login[0]).read() if login else "missing"
                        open(login[0], "w").write("tampered")  # the next round must see a clean checkout
                        # repository-local and HOME git configuration that would run during the next fetch
                        for config in glob.glob("*/.git/config") + [".loopforge/home/git/.gitconfig"]:
                            os.makedirs(os.path.dirname(config), exist_ok=True)
                            open(config, "a").write("\\n[core]\\n\\tfsmonitor = touch " + os.path.abspath("pwned") + "\\n")
                        fresh = "## 需求" in prompt and "登录页报错" in prompt and "## 代码" in prompt and "ready" in prompt
                        result(fenced({"ready": False, "summary": "fresh=" + str(fresh) + " key=" + str(planted) + " code=" + code.strip(),
                                       "questions": [{"id": "q1", "question": "错误提示放在哪里？",
                                                      "options": ["表单顶部", "输入框下方", "表单顶部"]},
                                                     {"question": "提示文案由谁提供？", "options": ["产品"]}]}))
                    elif prompt.startswith("刚才的回复里没有有效"):
                        clean = (open(login[0]).read().strip() if login else "missing") + " pwned=" + str(os.path.exists("pwned")) + " key=" + str(planted)
                        result(fenced({"ready": True, "summary": "本次范围：错误提示放在表单顶部。clean=" + clean}))
                    elif "表单顶部" in prompt:
                        result("我觉得已经清楚了")
                    else:
                        result("prompt=" + prompt[-40:])
                    """);
            cli.toFile().setExecutable(true);

            github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            github.createContext("/gh", ex -> {
                String path = ex.getRequestURI().getPath();
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (path.endsWith("/comments")) {
                    comments.add(Json.read(body, Map.class).get("body").toString());
                    reply(ex, 201, Map.of("id", 1));
                } else {
                    reply(
                            ex,
                            201,
                            Map.of(
                                    "number", 12,
                                    "title", Json.read(body, Map.class).get("title"),
                                    "body", "",
                                    "state", "open",
                                    "html_url", "https://github.com/acme/app/issues/12",
                                    "updated_at", "2026-11-20T00:00:00Z"));
                }
            });
            github.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
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
        github.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:clarify;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add(
                "loopforge.executors.claude.binary",
                () -> data.resolve("fake-claude").toString());
        registry.add("loopforge.security.members-require-isolation", () -> "false");
        registry.add("loopforge.git.allow-local-remotes", () -> "true");
        registry.add(
                "loopforge.github.api-url",
                () -> "http://127.0.0.1:" + github.getAddress().getPort() + "/gh");
        registry.add("loopforge.git.https[0].host", () -> "127.0.0.1");
        registry.add("loopforge.git.https[0].token", () -> "gh-token");
        registry.add(
                "loopforge.git.ssh-private-key-file",
                () -> data.resolve("deploy_key").toString());
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
    Map<String, Object> call(RestClient c, String method, String path, Object body, int status) {
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
        return (String) call(
                        client("none"),
                        "POST",
                        "/api/v1/auth/login",
                        Map.of("username", user, "password", user + "-password-1"),
                        200)
                .get("token");
    }

    static final String C = "/api/v1/projects/p1/requirements/1/clarification";

    Map<String, Object> awaitIdle(RestClient c) {
        await().atMost(Duration.ofSeconds(60))
                .until(() -> !List.of("analyzing", "cancelling")
                        .contains(call(c, "GET", C, null, 200).get("status")));
        return call(c, "GET", C, null, 200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void analystAsksThenConcludesAndTheConclusionIsWrittenBack() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        call(
                admin,
                "PUT",
                "/api/v1/repositories/app",
                Map.of("name", "App", "cloneUrl", remote.toString(), "githubRepo", "acme/app"),
                200);
        String carolId = (String)
                call(admin, "POST", "/api/v1/users", Map.of("username", "carol", "password", "carol-password-1"), 201)
                        .get("id");
        String daveId = (String)
                call(admin, "POST", "/api/v1/users", Map.of("username", "dave", "password", "dave-password-1"), 201)
                        .get("id");
        call(admin, "PUT", "/api/v1/projects/p1/members/" + carolId, Map.of("role", "developer"), 200);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + daveId, Map.of("role", "viewer"), 200);
        RestClient carol = client(login("carol"));
        RestClient dave = client(login("dave"));
        call(
                carol,
                "POST",
                "/api/v1/projects/p1/requirements",
                Map.of("title", "登录页报错", "description", "登录失败时提示不明显", "repositoryIds", List.of("app")),
                201);
        call(admin, "POST", "/api/v1/projects/p1/requirements/1/external", Map.of("target", "app"), 200);

        assertThat(call(dave, "GET", C, null, 200)).containsEntry("status", "new");
        call(dave, "POST", C, Map.of(), 403);

        // round 1: the analyst reads the checkout and asks; duplicate and single options are normalized.
        // The console follows it on the stream, authenticated by the query token (EventSource has no headers).
        String carolToken = login("carol");
        List<String> events = new CopyOnWriteArrayList<>();
        java.net.http.HttpClient sse = java.net.http.HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .build();
        sse.sendAsync(
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                        "http://localhost:" + port + C + "/stream?access_token=" + carolToken))
                                .build(),
                        java.net.http.HttpResponse.BodyHandlers.ofLines())
                .thenAccept(r -> {
                    events.add("status " + r.statusCode());
                    r.body().forEach(events::add);
                });
        await().atMost(Duration.ofSeconds(10)).until(() -> events.contains("status 200"));
        call(carol, "POST", C, Map.of(), 202);
        call(carol, "POST", C, Map.of("message", "再来"), 409);
        Map<String, Object> first = awaitIdle(carol);
        assertThat(first).as(first::toString).containsEntry("status", "in_progress");
        assertThat((String) first.get("summary")).contains("fresh=True", "key=False", "shows errors in a toast");
        List<Map<String, Object>> questions = (List<Map<String, Object>>) first.get("questions");
        assertThat(questions).hasSize(2);
        assertThat(questions.get(0))
                .containsEntry("id", "q1")
                .containsEntry("options", List.of("表单顶部", "输入框下方"))
                .containsEntry("freeText", false);
        assertThat(questions.get(1)).containsEntry("id", "q2").containsEntry("freeText", true);
        assertThat(((Number) first.get("totalTokens")).longValue()).isGreaterThan(0);
        await().atMost(Duration.ofSeconds(10)).until(() -> events.contains("event:done"));
        assertThat(events).contains("event:activity", "data:repository · App @ main → App");

        // round 2: an invalid reply is retried in the same session; the conclusion is written back
        call(carol, "POST", C, Map.of("message", "表单顶部"), 202);
        Map<String, Object> second = awaitIdle(carol);
        assertThat(second)
                .as(second::toString)
                .containsEntry("status", "ready")
                .containsEntry("writeBack", "succeeded");
        // the next round cloned afresh: nothing the agent planted ran, and the deploy key was not left behind
        assertThat((String) second.get("summary"))
                .contains("本次范围：错误提示放在表单顶部", "clean=class Login", "pwned=False", "key=False");
        assertThat((List<Map<String, Object>>) second.get("history"))
                .extracting(m -> m.get("role") + ":" + m.get("author"))
                .containsExactly("assistant:需求分析师", "user:carol", "assistant:需求分析师");
        Map<String, Object> requirement = call(carol, "GET", "/api/v1/projects/p1/requirements/1", null, 200);
        assertThat(requirement).containsEntry("status", "todo");
        assertThat((String) requirement.get("description"))
                .startsWith("登录失败时提示不明显\n\n## LoopForge 澄清结论\n\n本次范围：错误提示放在表单顶部");
        assertThat(comments)
                .singleElement()
                .satisfies(c -> assertThat(c).startsWith("## LoopForge 澄清结论").contains("GitHub Issue #1"));

        // an edited conclusion replaces the block and is written back once
        call(carol, "PUT", C, Map.of("summary", "修改后的结论", "ready", true), 200);
        call(carol, "PUT", C, Map.of("summary", "修改后的结论", "ready", true), 200);
        call(carol, "PUT", C, Map.of("summary", "", "ready", true), 422);
        assertThat((String) call(carol, "GET", "/api/v1/projects/p1/requirements/1", null, 200)
                        .get("description"))
                .isEqualTo("登录失败时提示不明显\n\n## LoopForge 澄清结论\n\n修改后的结论");
        assertThat(comments).hasSize(2);

        // a round can be stopped; the answer stays pending and the status returns to ready
        call(carol, "POST", C, Map.of("message", "睡一会"), 202);
        Thread.sleep(1500);
        call(carol, "POST", C + "/cancel", null, 202);
        Map<String, Object> stopped = awaitIdle(carol);
        assertThat(stopped)
                .containsEntry("status", "ready")
                .containsEntry("error", "已停止")
                .containsEntry("pending", "睡一会");

        // starting over forgets the dialogue and removes the workspace; the description keeps the conclusion
        Path workspace = data.resolve("workspaces").resolve("conv-clarify-" + requirement.get("id"));
        assertThat(workspace).isDirectory();
        call(dave, "DELETE", C, null, 403);
        call(carol, "DELETE", C, null, 204);
        assertThat(call(carol, "GET", C, null, 200)).containsEntry("status", "new");
        await().atMost(Duration.ofSeconds(10)).until(() -> !Files.exists(workspace));
        assertThat((String) call(carol, "GET", "/api/v1/projects/p1/requirements/1", null, 200)
                        .get("description"))
                .contains("修改后的结论");
        sse.shutdownNow();
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
