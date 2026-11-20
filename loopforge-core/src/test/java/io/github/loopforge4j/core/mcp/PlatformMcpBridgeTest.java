package io.github.loopforge4j.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlatformMcpBridgeTest {

    HttpServer server;
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            if (body.contains("notifications/")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            Map<?, ?> request = (Map<?, ?>) MiniJson.parse(body);
            // pretty-printed on purpose: stdout must still get one line per message
            byte[] reply = ("{\n  \"jsonrpc\": \"2.0\",\n  \"id\": " + MiniJson.write(request.get("id"))
                            + ",\n  \"result\": {\"echo\": " + MiniJson.write(request.get("method")) + "}\n}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private List<String> run(String input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PlatformMcpBridge(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                        "lfu_token",
                        HttpClient.newHttpClient(),
                        new PrintStream(out, true, StandardCharsets.UTF_8))
                .serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    void relaysEachMessageAndPrintsOneLinePerReply() throws Exception {
        List<String> lines = run("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}

                {"jsonrpc":"2.0","id":"two","method":"tools/list"}
                """);
        assertThat(lines).hasSize(2);
        assertThat(((Map<?, ?>) MiniJson.parse(lines.get(0))).get("id")).isEqualTo(1L);
        assertThat(lines.get(1)).contains("\"id\":\"two\"").contains("tools/list");
        assertThat(bodies).hasSize(3);
        assertThat(authorizations).containsOnly("Bearer lfu_token");
    }

    @Test
    void rejectedTokensAndBadInputBecomeJsonRpcErrors() throws Exception {
        status = 401;
        List<String> lines = run("""
                {"jsonrpc":"2.0","id":7,"method":"tools/list"}
                not json
                """);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"id\":7").contains("rejected the token");
        assertThat(lines.get(1)).contains("-32700");
    }

    @Test
    void anUnreachableServerIsReportedPerRequest() throws Exception {
        server.stop(0);
        List<String> lines = run("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n");
        assertThat(lines)
                .singleElement()
                .satisfies(l -> assertThat(l).contains("\"id\":3").contains("unreachable"));
    }
}
