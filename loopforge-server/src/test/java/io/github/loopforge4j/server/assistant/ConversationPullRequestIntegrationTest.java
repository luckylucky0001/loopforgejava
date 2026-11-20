package io.github.loopforge4j.server.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.json.Json;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Pull requests the speaker opens from a project conversation are registered on a requirement. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationPullRequestIntegrationTest {

    static final String TOKEN = "conversation-pr-token-0123456789-abcd";
    static final Path data;
    static final HttpServer github;
    static final AtomicBoolean merged = new AtomicBoolean();

    static {
        try {
            data = Files.createTempDirectory("lf-conv-pr-it");
            Path cli = data.resolve("fake-claude");
            // "开 PR" leaves a checkout whose feature branch is what the fake GitHub reports as the PR head
            Files.writeString(cli, """
                    #!/bin/sh
                    prompt=$(cat)
                    session=s-pr
                    echo '{"type":"system","subtype":"init","session_id":"s-pr"}'
                    case "$prompt" in
                      *"开 PR"*)
                        git init -q -b main app && cd app
                        git -c user.email=a@b -c user.name=x commit --allow-empty -q -m init
                        git checkout -q -b feature/refund
                        git -c user.email=a@b -c user.name=x commit --allow-empty -q -m refund
                        git remote add origin https://127.0.0.1/acme/app.git
                        git rev-parse HEAD > ../head.txt
                        cd .. ;;
                    esac
                    echo '{"type":"result","session_id":"s-pr","is_error":false,"result":"好的","usage":{"input_tokens":1,"output_tokens":1}}'
                    """);
            cli.toFile().setExecutable(true);
            github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            github.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                boolean authorised =
                        "Bearer ghp_owen".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                String body;
                int status = 200;
                if (!authorised) {
                    status = 401;
                    body = "{}";
                } else if (path.equals("/user")) {
                    body = "{\"login\":\"owen\"}";
                } else if (path.equals("/repos/acme/app/pulls")) {
                    body = Json.write(List.of(
                            pr(7, "owen", Instant.now().toString(), head()),
                            pr(6, "someone-else", Instant.now().toString(), head()),
                            pr(5, "owen", "2000-01-01T00:00:00Z", head()),
                            pr(4, "owen", Instant.now().toString(), "0".repeat(40))));
                } else if (path.equals("/repos/acme/app/pulls/7")) {
                    body = Json.write(pr(7, "owen", Instant.now().toString(), head()));
                } else {
                    status = 404;
                    body = "{}";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            github.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String head() {
        try {
            Path file = data.resolve("w").toFile().listFiles()[0].toPath().resolve("head.txt");
            return Files.readString(file).strip();
        } catch (Exception e) {
            return "f".repeat(40);
        }
    }

    static Map<String, Object> pr(int number, String author, String created, String sha) {
        Map<String, Object> pr = new java.util.HashMap<>();
        pr.put("number", number);
        pr.put("html_url", "https://127.0.0.1/acme/app/pull/" + number);
        pr.put("title", "退款接口 #" + number);
        pr.put("body", "新增退款");
        pr.put("state", merged.get() && number == 7 ? "closed" : "open");
        pr.put("merged_at", merged.get() && number == 7 ? Instant.now().toString() : null);
        pr.put("created_at", created);
        pr.put("user", Map.of("login", author));
        pr.put("head", Map.of("ref", "feature/refund", "sha", sha));
        pr.put("base", Map.of("ref", "main"));
        return pr;
    }

    @AfterAll
    static void stop() {
        github.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:conversation-prs;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
        registry.add(
                "loopforge.executors.claude.binary",
                () -> data.resolve("fake-claude").toString());
        registry.add(
                "loopforge.github.api-url",
                () -> "http://127.0.0.1:" + github.getAddress().getPort());
    }

    @LocalServerPort
    int port;

    @Autowired
    ConversationPullRequests registrar;

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
    String turn(RestClient c, String id, String text) {
        int before =
                ((List<Object>) call(c, "GET", "/api/v1/assistant/sessions/" + id + "/messages", null, 200)).size();
        call(c, "POST", "/api/v1/assistant/sessions/" + id + "/messages", Map.of("content", text), 202);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            List<Map<String, Object>> m = call(c, "GET", "/api/v1/assistant/sessions/" + id + "/messages", null, 200);
            return m.size() >= before + 2;
        });
        List<Map<String, Object>> messages =
                call(c, "GET", "/api/v1/assistant/sessions/" + id + "/messages", null, 200);
        return (String) messages.getLast().get("content");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pullRequestsOpenedInAConversationLandOnARequirement() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        Map<String, Object> user =
                call(admin, "POST", "/api/v1/users", Map.of("username", "owen", "password", "owen-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + user.get("id"), Map.of("role", "developer"), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", "owen", "password", "owen-password-1"),
                200);
        RestClient owen = client((String) login.get("token"));
        call(owen, "PUT", "/api/v1/me/credentials/git:127.0.0.1", Map.of("token", "ghp_owen"), 200);
        Map<String, Object> session =
                call(owen, "POST", "/api/v1/assistant/sessions", Map.of("projectId", "p1", "title", "退款"), 201);
        String id = (String) session.get("id");

        // only the PR the speaker opened after the conversation started, on a local branch tip, counts
        String reply = turn(owen, id, "开 PR");
        assertThat(reply).contains("好的").contains("已登记 PR：acme/app#7 → 需求 #1");
        List<Map<String, Object>> requirements = call(owen, "GET", "/api/v1/projects/p1/requirements", null, 200);
        assertThat(requirements).singleElement().satisfies(r -> {
            assertThat(r)
                    .containsEntry("title", "退款接口 #7")
                    .containsEntry("status", "in_review")
                    .containsEntry("createdBy", "owen");
            assertThat((String) r.get("description")).contains("新增退款").contains("来自对话「退款」");
            assertThat((List<Map<String, Object>>) r.get("deliveries"))
                    .singleElement()
                    .satisfies(p -> assertThat(p)
                            .containsEntry("url", "https://127.0.0.1/acme/app/pull/7")
                            .containsEntry("state", "open"));
        });
        String requirementId = (String) requirements.getFirst().get("id");
        assertThat((Map<String, Object>) call(owen, "GET", "/api/v1/assistant/sessions/" + id, null, 200))
                .containsEntry("requirementId", requirementId);

        // a tracked PR is not registered again
        assertThat(turn(owen, id, "再看看")).doesNotContain("已登记");

        // merging completes the requirement
        merged.set(true);
        registrar.refresh();
        Map<String, Object> done = call(owen, "GET", "/api/v1/projects/p1/requirements/1", null, 200);
        assertThat(done).containsEntry("status", "done");
        assertThat((List<Map<String, Object>>) done.get("deliveries"))
                .singleElement()
                .satisfies(p -> assertThat(p).containsEntry("state", "merged"));
    }
}
