package io.github.loopforge4j.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.GateDecision;
import io.github.loopforge4j.core.domain.GateResult;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.engine.GateResults;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateMcpServerTest {

    @TempDir
    Path dir;

    @Test
    void servesTheMcpHandshakeAndWritesTheResultExactlyOnce() throws Exception {
        Path result = dir.resolve(".loopforge/gate-results/n/attempt-1.json");
        List<Map<String, Object>> responses = serve(
                result,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"submit_gate_result\","
                        + "\"arguments\":{\"decision\":\"rework\",\"reason\":\"  测试失败  \",\"actionRequired\":\"修复\","
                        + "\"findings\":[{\"severity\":\"high\",\"title\":\"NPE\",\"evidence\":\"stack\"}]}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"submit_gate_result\","
                        + "\"arguments\":{\"decision\":\"pass\",\"reason\":\"again\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"nope\"}",
                "not json");
        assertThat(responses).hasSize(6);
        assertThat(result(responses.get(0)))
                .containsEntry("protocolVersion", "2025-03-26")
                .containsKey("serverInfo");
        assertThat(String.valueOf(result(responses.get(1)))).contains("submit_gate_result", "actionRequired");
        assertThat(String.valueOf(result(responses.get(2)))).contains("Gate result submitted");
        assertThat(error(responses.get(3))).isEqualTo("gate result already submitted");
        assertThat(error(responses.get(4))).isEqualTo("method not found");
        assertThat(responses.get(5)).containsEntry("id", null).containsKey("error");

        GateResult parsed = GateResults.read(dir, ".loopforge/gate-results/n/attempt-1.json")
                .orElseThrow();
        assertThat(parsed.decision()).isEqualTo(GateDecision.REWORK);
        assertThat(parsed.reason()).isEqualTo("测试失败");
        assertThat(parsed.findings()).singleElement().satisfies(f -> {
            assertThat(f.title()).isEqualTo("NPE");
            assertThat(f.severity()).isEqualTo("high");
        });
    }

    @Test
    void argumentsAreValidatedLikeUpstream() {
        assertThat(decode(Map.of("decision", "maybe", "reason", "x"))).startsWith("decision must be");
        assertThat(decode(Map.of("decision", "pass", "reason", " "))).isEqualTo("reason is required");
        assertThat(decode(Map.of("decision", "pass", "reason", "x", "extra", 1)))
                .isEqualTo("unknown fields: extra");
        assertThat(decode(Map.of("decision", "human", "reason", "x")))
                .isEqualTo("actionRequired is required when decision is human");
        assertThat(decode(Map.of("decision", "pass", "reason", "x".repeat(4001))))
                .isEqualTo("reason is too long");
        assertThat(decode(Map.of("decision", "pass", "reason", "x", "findings", List.of(Map.of("title", "")))))
                .isEqualTo("each finding requires a title");
        assertThat(decode(Map.of("decision", "pass", "reason", "x", "findings", List.of(Map.of("t", "a")))))
                .isEqualTo("finding contains unknown fields");
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(Map.of("title", "f" + i));
        }
        assertThat(decode(Map.of("decision", "pass", "reason", "x", "findings", many)))
                .startsWith("findings must be an array");
        assertThat(decode(Map.of("decision", "fail", "reason", "x", "summary", 3)))
                .startsWith("summary must be a string");
        assertThat(decode(Map.of("decision", "pass", "reason", "ok"))).isNull();
    }

    @Test
    void packagedJarRunsInASeparateJvm() throws Exception {
        Path jar = dir.resolve(".loopforge/bin/gate-mcp.jar");
        Path result = dir.resolve("result.json");
        McpServerDefinition server = GateMcpPackage.materialize(jar, GateMcpPackage.currentJava(), result);
        assertThat(server.args()).contains(jar.toString());
        // idempotent: an unchanged jar is not rewritten
        long modified = Files.getLastModifiedTime(jar).toMillis();
        GateMcpPackage.materialize(jar, GateMcpPackage.currentJava(), result);
        assertThat(Files.getLastModifiedTime(jar).toMillis()).isEqualTo(modified);

        List<String> command = new ArrayList<>();
        command.add(server.command());
        command.addAll(server.args());
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.environment().clear();
        builder.environment().putAll(server.environment());
        Process process = builder.start();
        try (OutputStream in = process.getOutputStream()) {
            in.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n"
                            + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
                            + "\"submit_gate_result\",\"arguments\":{\"decision\":\"pass\",\"reason\":\"全部通过\"}}}\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        List<String> lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .toList();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"protocolVersion\":\"2025-06-18\"");
        assertThat(Files.readString(result)).contains("\"decision\":\"pass\"", "全部通过");
    }

    @Test
    void miniJsonRoundTripsEscapes() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("s", "a\"b\\c\n\u0001é");
        value.put("n", -12L);
        value.put("d", 1.5);
        value.put("b", true);
        value.put("z", null);
        value.put("l", List.of(1L, "x"));
        assertThat(MiniJson.parse(MiniJson.write(value))).isEqualTo(value);
        assertThat(MiniJson.parse("\"\\u4e2d\"")).isEqualTo("中");
    }

    private static String decode(Map<String, Object> arguments) {
        return GateMcpServer.decode(arguments, new LinkedHashMap<>());
    }

    private List<Map<String, Object>> serve(Path result, String... lines) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new GateMcpServer(result, out)
                .serve(new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8)));
        List<Map<String, Object>> responses = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                responses.add(cast(MiniJson.parse(line)));
            }
        }
        return responses;
    }

    private static Map<String, Object> result(Map<String, Object> response) {
        return cast(response.get("result"));
    }

    private static String error(Map<String, Object> response) {
        return String.valueOf(cast(response.get("error")).get("message"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
