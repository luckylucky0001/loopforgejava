package io.github.loopforge4j.core.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stdio MCP server with one tool, {@code submit_gate_result}, that writes the node's terminal
 * decision to {@code LOOPFORGE_GATE_RESULT_PATH} exactly once (port of upstream {@code gatemcp}).
 * It depends on nothing but the JDK and {@link MiniJson} so it runs from a small jar the engine
 * writes into the workspace.
 */
public final class GateMcpServer {

    public static final String VERSION = "1.0.0";
    public static final String SERVER_ID = "loopforge-gate";
    public static final String TOOL = "submit_gate_result";
    public static final String RESULT_PATH_ENV = "LOOPFORGE_GATE_RESULT_PATH";
    private static final int MAX_LINE = 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("decision", "reason", "actionRequired", "summary", "findings");
    private static final Set<String> FINDING_FIELDS = Set.of("severity", "title", "evidence");
    private static final Set<String> DECISIONS = Set.of("pass", "rework", "fail", "human");

    private final Path resultPath;
    private final PrintStream out;

    GateMcpServer(Path resultPath, OutputStream out) {
        this.resultPath = resultPath;
        this.out = new PrintStream(out, true, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws IOException {
        String path = System.getenv(RESULT_PATH_ENV);
        if (path == null || path.isBlank()) {
            System.err.println(RESULT_PATH_ENV + " is required");
            System.exit(2);
        }
        new GateMcpServer(Path.of(path.strip()), System.out).serve(System.in);
    }

    /** Serves newline-delimited JSON-RPC until stdin closes. */
    void serve(InputStream in) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            if (line.length() > MAX_LINE) {
                respond(null, null, "request too large");
                continue;
            }
            Map<String, Object> request;
            try {
                Object parsed = MiniJson.parse(line);
                if (!(parsed instanceof Map<?, ?>)) {
                    respond(null, null, "request must be an object");
                    continue;
                }
                request = cast(parsed);
            } catch (IllegalArgumentException e) {
                respond(null, null, e.getMessage());
                continue;
            }
            String method = String.valueOf(request.get("method"));
            if (method.startsWith("notifications/")) {
                continue;
            }
            handle(request.get("id"), method, request.get("params"));
        }
    }

    private void handle(Object id, String method, Object params) {
        switch (method) {
            case "initialize" -> {
                Object version = params instanceof Map<?, ?> p ? p.get("protocolVersion") : null;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("protocolVersion", version instanceof String s && !s.isBlank() ? s : "2025-06-18");
                result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
                result.put("serverInfo", Map.of("name", SERVER_ID, "version", VERSION));
                result.put("instructions", "Call submit_gate_result exactly once to finish this node.");
                respond(id, result, null);
            }
            case "ping" -> respond(id, Map.of(), null);
            case "tools/list" -> {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", TOOL);
                tool.put(
                        "description",
                        "Submit exactly one terminal node decision. reason explains why. human also requires"
                                + " actionRequired to state exactly what a person must confirm.");
                tool.put("inputSchema", inputSchema());
                respond(id, Map.of("tools", List.of(tool)), null);
            }
            case "tools/call" -> call(id, params);
            default -> respond(id, null, "method not found");
        }
    }

    private void call(Object id, Object params) {
        if (!(params instanceof Map<?, ?> p)) {
            respond(id, null, "invalid tool parameters");
            return;
        }
        if (!TOOL.equals(p.get("name"))) {
            respond(id, null, "unknown tool");
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        String problem = decode(p.get("arguments"), result);
        if (problem != null) {
            respond(id, null, problem);
            return;
        }
        try {
            writeExclusive(MiniJson.write(result) + "\n");
        } catch (FileAlreadyExistsException e) {
            respond(id, null, "gate result already submitted");
            return;
        } catch (IOException e) {
            respond(id, null, "cannot write gate result: " + e.getMessage());
            return;
        }
        respond(
                id,
                Map.of(
                        "content",
                        List.of(Map.of(
                                "type",
                                "text",
                                "text",
                                "Gate result submitted. Stop now; this node has reached its terminal decision.")),
                        "isError",
                        false),
                null);
    }

    /** Validates the tool arguments into {@code out}; returns a problem or {@code null}. */
    static String decode(Object arguments, Map<String, Object> out) {
        if (!(arguments instanceof Map<?, ?> raw)) {
            return "arguments must be an object";
        }
        Set<String> unknown = new TreeSet<>();
        for (Object key : raw.keySet()) {
            if (!FIELDS.contains(String.valueOf(key))) {
                unknown.add(String.valueOf(key));
            }
        }
        if (!unknown.isEmpty()) {
            return "unknown fields: " + String.join(", ", unknown);
        }
        Object decision = raw.get("decision");
        if (!(decision instanceof String d) || !DECISIONS.contains(d)) {
            return "decision must be pass, rework, fail, or human";
        }
        String reason = text(raw.get("reason"));
        if (reason == null || reason.isEmpty()) {
            return "reason is required";
        }
        if (bytes(reason) > 4000) {
            return "reason is too long";
        }
        String action = text(raw.get("actionRequired"));
        if (action == null || bytes(action) > 4000) {
            return "actionRequired must be a string with at most 4000 characters";
        }
        if ("human".equals(decision) && action.isEmpty()) {
            return "actionRequired is required when decision is human";
        }
        String summary = text(raw.get("summary"));
        if (summary == null || bytes(summary) > 4000) {
            return "summary must be a string with at most 4000 characters";
        }
        List<Object> findings = new ArrayList<>();
        if (raw.containsKey("findings")) {
            if (!(raw.get("findings") instanceof List<?> items) || items.size() > 50) {
                return "findings must be an array with at most 50 items";
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> finding)) {
                    return "findings must be an array with at most 50 items";
                }
                for (Object key : finding.keySet()) {
                    if (!FINDING_FIELDS.contains(String.valueOf(key))) {
                        return "finding contains unknown fields";
                    }
                }
                String title = text(finding.get("title"));
                if (title == null || title.isEmpty()) {
                    return "each finding requires a title";
                }
                if (bytes(title) > 500) {
                    return "finding title is too long";
                }
                String severity = raw(finding.get("severity"));
                if (severity == null || bytes(severity) > 64) {
                    return "finding severity is invalid";
                }
                String evidence = raw(finding.get("evidence"));
                if (evidence == null || bytes(evidence) > 4000) {
                    return "finding evidence is invalid";
                }
                Map<String, Object> f = new LinkedHashMap<>();
                if (!severity.isEmpty()) {
                    f.put("severity", severity);
                }
                f.put("title", title);
                if (!evidence.isEmpty()) {
                    f.put("evidence", evidence);
                }
                findings.add(f);
            }
        }
        out.put("decision", decision);
        out.put("reason", reason);
        out.put("actionRequired", action);
        out.put("summary", summary);
        out.put("findings", findings);
        return null;
    }

    /** Trimmed string, empty for absent, {@code null} for a non-string. */
    private static String text(Object value) {
        String s = raw(value);
        return s == null ? null : s.strip();
    }

    private static String raw(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof String s ? s : null;
    }

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private void writeExclusive(String content) throws IOException {
        Path parent = resultPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean ok = false;
        try (FileChannel channel =
                FileChannel.open(resultPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            ok = true;
        } finally {
            if (!ok) {
                try {
                    if (Files.size(resultPath) == 0) {
                        Files.deleteIfExists(resultPath);
                    }
                } catch (IOException ignored) {
                    // the original error is reported
                }
            }
        }
    }

    private void respond(Object id, Object result, String problem) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        if (problem != null) {
            response.put("error", Map.of("code", -32602, "message", problem));
        } else {
            response.put("result", result);
        }
        out.println(MiniJson.write(response));
    }

    static Map<String, Object> inputSchema() {
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("type", "object");
        finding.put("additionalProperties", false);
        finding.put("required", List.of("title"));
        finding.put(
                "properties",
                Map.of(
                        "severity", Map.of("type", "string", "maxLength", 64),
                        "title", Map.of("type", "string", "minLength", 1, "maxLength", 500),
                        "evidence", Map.of("type", "string", "maxLength", 4000)));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("decision", Map.of("type", "string", "enum", List.of("pass", "rework", "fail", "human")));
        properties.put("reason", Map.of("type", "string", "minLength", 1, "maxLength", 4000));
        properties.put("actionRequired", Map.of("type", "string", "maxLength", 4000));
        properties.put("summary", Map.of("type", "string", "maxLength", 4000));
        properties.put("findings", Map.of("type", "array", "maxItems", 50, "items", finding));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("decision", "reason"));
        schema.put("properties", properties);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
