package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.server.work.ScheduleService;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Iteration 5 work items: requirements, schedules and webhook notifications. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkIntegrationTest {

    static final String TOKEN = "work-test-token-0123456789-abcdefghijk";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    static final Path data;
    static final HttpServer hooks;
    static final List<String[]> received = new CopyOnWriteArrayList<>();

    static {
        try {
            data = Files.createTempDirectory("lf-work-it");
            hooks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            hooks.createContext("/hook", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                received.add(new String[] {
                    exchange.getRequestHeaders().getFirst("X-LoopForge-Event"),
                    exchange.getRequestHeaders().getFirst("X-LoopForge-Signature"),
                    body
                });
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
            });
            hooks.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:work;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.schedules.poll-interval", () -> "1h");
    }

    @AfterAll
    static void stop() {
        hooks.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    ScheduleService schedules;

    @Autowired
    JdbcClient jdbc;

    RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + TOKEN)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
        if (api.get()
                        .uri("/api/v1/projects/work")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value()
                == 404) {
            send("PUT", "/api/v1/projects/work", Map.of("name", "Work"), 200);
            send(
                    "PUT",
                    "/api/v1/workflows/echo",
                    Map.of(
                            "status",
                            "published",
                            "nodes",
                            List.of(Map.of(
                                    "id",
                                    "a",
                                    "type",
                                    "script",
                                    "script",
                                    "cat \"$LOOPFORGE_WORKSPACE/.loopforge/input.md\" 2>/dev/null; echo ok"))),
                    200);
            send(
                    "PUT",
                    "/api/v1/workflows/broken",
                    Map.of(
                            "status",
                            "published",
                            "nodes",
                            List.of(Map.of("id", "a", "type", "script", "script", "exit 1"))),
                    200);
        }
    }

    @Test
    void requirementsFollowTheirRuns() {
        Map<String, Object> created = send(
                "POST",
                "/api/v1/projects/work/requirements",
                Map.of("title", "登录超时", "description", "会话 5 分钟就过期", "priority", "high", "workflowId", "broken"),
                201);
        int number = ((Number) created.get("number")).intValue();
        assertThat(created).containsEntry("status", "backlog");
        Map<String, Object> second = send("POST", "/api/v1/projects/work/requirements", Map.of("title", "第二个"), 201);
        assertThat(((Number) second.get("number")).intValue()).isEqualTo(number + 1);
        send("POST", "/api/v1/projects/work/requirements", Map.of("title", " "), 422);
        send("POST", "/api/v1/projects/work/requirements", Map.of("title", "x", "status", "weird"), 422);

        Map<String, Object> run = send("POST", "/api/v1/projects/work/requirements/" + number + "/runs", Map.of(), 201);
        assertThat(String.valueOf(run.get("input"))).contains("需求 #" + number, "登录超时", "会话 5 分钟就过期");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "blocked".equals(requirement(number).get("status")));
        assertThat(String.valueOf(requirement(number).get("runIds"))).isEqualTo("[" + run.get("id") + "]");

        Map<String, Object> retry = send(
                "POST",
                "/api/v1/projects/work/requirements/" + number + "/runs",
                Map.of("workflowId", "echo", "input", "请重试"),
                201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "in_review".equals(requirement(number).get("status")));
        assertThat(requirement(number)).containsEntry("latestRunId", retry.get("id"));
        assertThat(String.valueOf(retry.get("input"))).contains("## 补充说明", "请重试");

        send("PUT", "/api/v1/projects/work/requirements/" + number, Map.of("title", "登录超时", "status", "closed"), 200);
        send("POST", "/api/v1/projects/work/requirements/" + number + "/runs", Map.of("workflowId", "echo"), 409);
        assertThat(api.get()
                        .uri("/api/v1/projects/work/requirements?status=closed")
                        .retrieve()
                        .body(LIST))
                .extracting(r -> r.get("number"))
                .contains(number);
    }

    @Test
    void schedulesFireOncePerSlotAndCanRunNow() {
        send(
                "POST",
                "/api/v1/projects/work/schedules",
                Map.of("name", "bad", "cron", "every day", "workflowId", "echo"),
                422);
        Map<String, Object> schedule = send(
                "POST",
                "/api/v1/projects/work/schedules",
                Map.of(
                        "name",
                        "nightly",
                        "cron",
                        "0 3 * * *",
                        "zone",
                        "Asia/Shanghai",
                        "workflowId",
                        "echo",
                        "input",
                        "定时任务",
                        "enabled",
                        true,
                        "skipIfRunning",
                        true),
                201);
        String id = (String) schedule.get("id");
        assertThat(String.valueOf(schedule.get("nextRunAt"))).endsWith("T19:00:00Z");

        // make it due, then fire twice (two instances polling at the same time): one run only
        jdbc.sql("UPDATE schedules SET next_run_at = next_run_at - INTERVAL '2' DAY WHERE id = :id")
                .param("id", id)
                .update();
        String payload = jdbc.sql("SELECT payload FROM schedules WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .single();
        java.time.Instant due = jdbc.sql("SELECT next_run_at FROM schedules WHERE id = :id")
                .param("id", id)
                .query((rs, i) ->
                        rs.getObject(1, java.time.OffsetDateTime.class).toInstant())
                .single();
        jdbc.sql("UPDATE schedules SET payload = :p WHERE id = :id")
                .param("p", payload.replaceAll("\"nextRunAt\":\"[^\"]+\"", "\"nextRunAt\":\"" + due + "\""))
                .param("id", id)
                .update();
        schedules.fireDue();
        schedules.fireDue();
        Map<String, Object> fired = send("GET", "/api/v1/projects/work/schedules/" + id, null, 200);
        assertThat(fired.get("lastRunId")).isNotNull();
        assertThat(String.valueOf(fired.get("nextRunAt"))).isGreaterThan(due.toString());
        List<Map<String, Object>> runs =
                api.get().uri("/api/v1/runs?workflowId=echo").retrieve().body(LIST);
        assertThat(runs.stream().filter(r -> fired.get("lastRunId").equals(r.get("id"))))
                .hasSize(1);
        Map<String, Object> started = api.get()
                .uri("/api/v1/runs/{id}", fired.get("lastRunId"))
                .retrieve()
                .body(MAP);
        assertThat(String.valueOf(started.get("context"))).contains("triggeredBy=schedule:" + id, "projectId=work");

        Map<String, Object> now = send("POST", "/api/v1/projects/work/schedules/" + id + "/run", Map.of(), 200);
        assertThat(now.get("lastRunId")).isNotEqualTo(fired.get("lastRunId"));
    }

    @Test
    void webhooksReceiveSignedRunEvents() throws Exception {
        String url = "http://127.0.0.1:" + hooks.getAddress().getPort() + "/hook";
        send("POST", "/api/v1/notifications", Map.of("name", "bad", "url", "ftp://x"), 422);
        Map<String, Object> channel = send(
                "POST",
                "/api/v1/notifications",
                Map.of(
                        "projectId",
                        "work",
                        "name",
                        "ci",
                        "url",
                        url,
                        "secret",
                        "s3cr3t",
                        "events",
                        List.of("run.started", "run.succeeded", "run.failed"),
                        "enabled",
                        true),
                201);
        assertThat(channel).doesNotContainKey("secret");
        received.clear();
        Map<String, Object> run = send("POST", "/api/v1/runs", Map.of("workflowId", "echo", "projectId", "work"), 201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> received.stream().anyMatch(r -> "run.succeeded".equals(r[0])));
        assertThat(received)
                .extracting(r -> r[0])
                .contains("run.started", "run.succeeded")
                .doesNotContain("run.waiting");
        String[] succeeded = received.stream()
                .filter(r -> "run.succeeded".equals(r[0]))
                .findFirst()
                .orElseThrow();
        assertThat(succeeded[2]).contains(String.valueOf(run.get("id")), "\"status\":\"succeeded\"");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("s3cr3t".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256="
                + java.util.HexFormat.of().formatHex(mac.doFinal(succeeded[2].getBytes(StandardCharsets.UTF_8)));
        assertThat(succeeded[1]).isEqualTo(expected);
        await().atMost(Duration.ofSeconds(10))
                .until(() -> String.valueOf(api.get()
                                .uri("/api/v1/notifications?projectId=work")
                                .retrieve()
                                .body(LIST)
                                .getFirst()
                                .get("lastStatus"))
                        .contains("http 204"));
        // runs of other projects do not reach the project channel
        received.clear();
        send("PUT", "/api/v1/projects/other", Map.of("name", "Other"), 200);
        Map<String, Object> other =
                send("POST", "/api/v1/runs", Map.of("workflowId", "echo", "projectId", "other"), 201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(api.get()
                                .uri("/api/v1/runs/{id}", other.get("id"))
                                .retrieve()
                                .body(MAP)
                                .get("status")));
        Thread.sleep(500);
        assertThat(received).isEmpty();
    }

    private Map<String, Object> requirement(int number) {
        return api.get()
                .uri("/api/v1/projects/work/requirements/{n}", number)
                .retrieve()
                .body(MAP);
    }

    private Map<String, Object> send(String method, String path, Map<String, Object> body, int status) {
        var spec =
                api.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<Map<String, Object>> response = spec.retrieve().toEntity(MAP);
        assertThat(response.getStatusCode().value())
                .as(method + " " + path + ": " + response.getBody())
                .isEqualTo(status);
        return response.getBody();
    }
}
