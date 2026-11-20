package io.github.loopforge4j.server.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.core.runtime.browser.CdpBrowser;
import io.github.loopforge4j.server.mcp.RunTokens;
import io.github.loopforge4j.server.mcp.ServerPlatformMcp;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * The agent browser end to end against a real headless Chromium (skipped without one): the console's
 * API and the agents' MCP drive the same browser, its policy holds, and its tokens open nothing else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentBrowserIntegrationTest {

    static final String TOKEN = "agent-browser-token-0123456789-abcdef";
    static final Path data;
    static final HttpServer site;

    static {
        try {
            data = Files.createTempDirectory("lf-agent-browser-it");
            Path cli = data.resolve("fake-claude");
            Files.writeString(cli, """
                    #!/bin/sh
                    cat > /dev/null
                    echo '{"type":"system","subtype":"init","session_id":"s1"}'
                    echo '{"type":"result","session_id":"s1","is_error":false,"result":"好的","usage":{"input_tokens":1,"output_tokens":1}}'
                    """);
            cli.toFile().setExecutable(true);
            site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            site.createContext("/", exchange -> {
                byte[] body = ("<html><head><title>订单</title></head><body><h1>待处理</h1>"
                                + "<input placeholder=\"备注\"><button onclick=\"document.querySelector('h1').textContent="
                                + "'已提交 '+document.querySelector('input').value\">提交</button></body></html>")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            site.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    static void browser() {
        Assumptions.assumeTrue(CdpBrowser.detect().isPresent(), "no Chromium or Chrome installed");
    }

    @AfterAll
    static void stop() {
        site.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:agent-browser;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
        registry.add(
                "loopforge.executors.claude.binary",
                () -> data.resolve("fake-claude").toString());
        registry.add("loopforge.browser.no-sandbox", () -> "true");
        registry.add("loopforge.browser.max-sessions", () -> "2");
    }

    @LocalServerPort
    int port;

    @Autowired
    RunTokens tokens;

    @Autowired
    ServerPlatformMcp platform;

    @Autowired
    AgentBrowsers browsers;

    @Autowired
    RunEngine engine;

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
    Map<String, Object> tool(String token, String name, Map<String, Object> arguments, int status) {
        Map<String, Object> rpc = call(
                client(token),
                "POST",
                BrowserMcpController.PATH,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        1,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", name, "arguments", arguments)),
                status);
        return rpc == null ? null : (Map<String, Object>) rpc.get("result");
    }

    @SuppressWarnings("unchecked")
    static String text(Map<String, Object> result) {
        return (String)
                ((List<Map<String, Object>>) result.get("content")).getFirst().get("text");
    }

    @SuppressWarnings("unchecked")
    String running(RestClient admin, String workflow, String profile) {
        Map<String, Object> sop = new java.util.HashMap<>(Map.of(
                "name",
                "慢",
                "status",
                "published",
                "projectId",
                "p1",
                "nodes",
                List.of(Map.of("id", "wait", "type", "script", "script", "sleep 60"))));
        if (profile != null) {
            sop.put("sandboxProfileId", profile);
        }
        call(admin, "PUT", "/api/v1/workflows/" + workflow, sop, 200);
        Map<String, Object> run = call(
                admin, "POST", "/api/v1/runs", Map.of("workflowId", workflow, "projectId", "p1", "input", "x"), 201);
        return (String) run.get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void consoleAndAgentsDriveTheSameGuardedBrowser() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        String runId = running(admin, "slow", null);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> engine.workspace(runId).available());
        String page = "http://127.0.0.1:" + site.getAddress().getPort() + "/";
        String api = "/api/v1/runs/" + runId + "/browser";

        // nothing starts until asked to
        Map<String, Object> idle = call(admin, "POST", api, Map.of("operation", "status"), 200);
        assertThat(idle).containsEntry("available", false);
        assertThat((String) idle.get("reason")).contains("尚未启动");

        Map<String, Object> opened = call(
                admin,
                "POST",
                api,
                Map.of("operation", "navigate", "url", page, "ensure", true, "withSnapshot", true, "withFrame", true),
                200);
        assertThat(opened).containsEntry("available", true).containsEntry("title", "订单");
        assertThat((String) opened.get("frame")).isNotBlank();
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) opened.get("snapshot")).get("elements"))
                .extracting(e -> e.get("name"))
                .contains("备注", "提交");

        // the agent of this run drives the very same browser through MCP
        String browserToken = tokens.issue(RunTokens.Kind.BROWSER, runId, "p1", "wait", "dev", Duration.ofMinutes(5));
        Map<String, Object> list = call(
                client(browserToken),
                "POST",
                BrowserMcpController.PATH,
                Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"),
                200);
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) list.get("result")).get("tools"))
                .extracting(t -> t.get("name"))
                .containsExactly(
                        "browser_navigate",
                        "browser_snapshot",
                        "browser_click",
                        "browser_type",
                        "browser_press",
                        "browser_screenshot",
                        "browser_tabs");
        String snapshot = text(tool(browserToken, "browser_snapshot", Map.of(), 200));
        assertThat(snapshot)
                .contains("URL: " + page)
                .contains("[e1] text \"备注\"")
                .contains("[e2] button \"提交\"");
        assertThat(text(tool(browserToken, "browser_type", Map.of("ref", "e1", "text", "加急"), 200)))
                .contains("值=\"加急\"");
        assertThat(text(tool(browserToken, "browser_click", Map.of("ref", "e2"), 200)))
                .contains("已提交 加急");
        Map<String, Object> shot = tool(browserToken, "browser_screenshot", Map.of(), 200);
        assertThat((List<Map<String, Object>>) shot.get("content"))
                .anySatisfy(c -> assertThat(c).containsEntry("type", "image").containsEntry("mimeType", "image/jpeg"));
        assertThat(tool(browserToken, "browser_click", Map.of("ref", "e99"), 200))
                .containsEntry("isError", true);
        // the console sees what the agent did
        Map<String, Object> seen = call(admin, "POST", api, Map.of("operation", "snapshot"), 200);
        assertThat((String) ((Map<String, Object>) seen.get("snapshot")).get("text"))
                .contains("已提交 加急");

        // the workspace's own files open; the machine's metadata service and other files do not
        Path root = engine.workspace(runId).root();
        Files.writeString(root.resolve("report.html"), "<title>报告</title><p>ok</p>");
        Map<String, Object> file = call(
                admin,
                "POST",
                api,
                Map.of(
                        "operation",
                        "navigate",
                        "url",
                        root.resolve("report.html").toUri().toString()),
                200);
        assertThat(file).containsEntry("title", "报告");
        Map<String, Object> metadata = call(
                admin,
                "POST",
                api,
                Map.of("operation", "navigate", "url", "http://169.254.169.254/latest/meta-data/"),
                200);
        assertThat((String) metadata.get("error")).isNotBlank();
        Map<String, Object> passwd =
                call(admin, "POST", api, Map.of("operation", "navigate", "url", "file:///etc/passwd"), 200);
        assertThat((String) passwd.get("error")).isNotBlank();
        assertThat((List<String>) passwd.get("blocked"))
                .contains("http://169.254.169.254/latest/meta-data/", "file:///etc/passwd");

        // a browser token is good for nothing else, and other kinds of token do not drive the browser
        call(client(browserToken), "GET", "/api/v1/runs/" + runId, null, 401);
        String platformToken = tokens.issue(RunTokens.Kind.PLATFORM, runId, "p1", "wait", "dev", Duration.ofMinutes(5));
        call(
                client(platformToken),
                "POST",
                BrowserMcpController.PATH,
                Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"),
                403);

        // agents get it when they share this server's network, never in an isolated sandbox
        PlatformMcp.Invocation local =
                new PlatformMcp.Invocation(runId, "p1", "wait", "dev", "x", "local", false, false);
        assertThat(platform.bind(local).servers())
                .anySatisfy(s -> assertThat(s.id()).isEqualTo(BrowserMcpController.SERVER_ID));
        assertThat(platform.bind(local).rules())
                .anySatisfy(r -> assertThat(r.id()).isEqualTo("loopforge-browser-usage"));
        PlatformMcp.Invocation docker =
                new PlatformMcp.Invocation(runId, "p1", "wait", "dev", "x", "docker", false, false);
        assertThat(platform.bind(docker).servers())
                .noneSatisfy(s -> assertThat(s.id()).isEqualTo(BrowserMcpController.SERVER_ID));

        // a conversation has a browser of its own; the per-instance limit holds
        Map<String, Object> user =
                call(admin, "POST", "/api/v1/users", Map.of("username", "nina", "password", "nina-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + user.get("id"), Map.of("role", "developer"), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", "nina", "password", "nina-password-1"),
                200);
        RestClient nina = client((String) login.get("token"));
        Map<String, Object> session =
                call(nina, "POST", "/api/v1/assistant/sessions", Map.of("projectId", "p1", "title", "看页面"), 201);
        String chat = "/api/v1/assistant/sessions/" + session.get("id") + "/browser";
        assertThat((Map<String, Object>)
                        call(nina, "POST", chat, Map.of("operation", "navigate", "url", page, "ensure", true), 200))
                .containsEntry("available", true)
                .containsEntry("title", "订单");
        assertThat(browsers.size()).isEqualTo(2);
        String other = running(admin, "slow2", null);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> engine.workspace(other).available());
        Map<String, Object> refused = call(
                admin,
                "POST",
                "/api/v1/runs/" + other + "/browser",
                Map.of("operation", "status", "ensure", true),
                200);
        assertThat(refused).containsEntry("available", false);
        assertThat((String) refused.get("reason")).contains("上限");
        call(nina, "DELETE", chat, null, 200);

        // a sandbox isolated from this server's network has none
        assertThat(BrowserWorkspaces.isolated("docker")).contains("隔离");

        // settling the run revokes its agent's browser token; closing frees the browser
        call(admin, "POST", "/api/v1/runs/" + runId + "/cancel", null, 200);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> engine.require(runId).status().terminal());
        tool(browserToken, "browser_snapshot", Map.of(), 401);
        call(admin, "DELETE", api, null, 200);
        assertThat(browsers.size()).isZero();
        call(admin, "POST", "/api/v1/runs/" + other + "/cancel", null, 200);
    }
}
