package io.github.loopforge4j.server.mcp;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The platform MCP (upstream {@code agentflow-mcp}): stateless Streamable HTTP, every tool one API
 * operation called as the caller. Account tokens get every operation (the API still decides what
 * the account may do); an agent's run token gets the read-only ones of its own project.
 */
@RestController
public class PlatformMcpController {

    public static final String PATH = "/mcp";

    private static final Logger log = LoggerFactory.getLogger(PlatformMcpController.class);
    /** Path parameters are single, plain segments: no traversal into other endpoints. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@:-]{0,199}");
    /** What a tool result shows of a response body. */
    private static final int MAX_TEXT = 256 * 1024;

    private final InternalApi api;
    private final AccessPolicy access;
    private final boolean enabled;
    private final String version;

    public PlatformMcpController(InternalApi api, AccessPolicy access, LoopForgeProperties properties) {
        this.api = api;
        this.access = access;
        this.enabled = properties.mcp().platformEnabled();
        String v = PlatformMcpController.class.getPackage().getImplementationVersion();
        this.version = v == null ? "dev" : v;
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> post(@RequestBody String raw, HttpServletRequest request, Principal principal) {
        if (!enabled) {
            return ResponseEntity.notFound().build();
        }
        JsonNode message;
        try {
            message = Json.mapper().readTree(raw);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().body(McpRpc.error(null, McpRpc.PARSE_ERROR, "parse error"));
        }
        if (message == null || !message.isObject()) {
            return ResponseEntity.badRequest()
                    .body(McpRpc.error(null, McpRpc.INVALID_REQUEST, "one JSON-RPC request object per POST"));
        }
        String method = message.path("method").asString("");
        if (method.startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }
        Object id = McpRpc.id(message);
        JsonNode params = message.get("params");
        CurrentUser user = access.current(principal);
        Object response = switch (method) {
            case "initialize" -> McpRpc.result(id, initialize(params));
            case "ping" -> McpRpc.result(id, Map.of());
            case "tools/list" -> McpRpc.result(id, Map.of("tools", tools(user)));
            case "tools/call" -> call(id, params, user, request.getHeader("Authorization"));
            case "resources/list" -> McpRpc.result(id, Map.of("resources", List.of()));
            case "resources/templates/list" -> McpRpc.result(id, Map.of("resourceTemplates", templates()));
            case "resources/read" -> read(id, params, request.getHeader("Authorization"));
            default -> McpRpc.error(id, McpRpc.METHOD_NOT_FOUND, "method not found: " + clip(method));
        };
        return ResponseEntity.ok()
                .header("MCP-Protocol-Version", McpRpc.protocolVersion(params))
                .body(response);
    }

    @GetMapping(PATH)
    ResponseEntity<Object> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Allow", "POST, DELETE")
                .body(Map.of("error", "stateless Streamable HTTP accepts JSON-RPC POST requests"));
    }

    @DeleteMapping(PATH)
    ResponseEntity<Void> delete() {
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> initialize(JsonNode params) {
        return Map.of(
                "protocolVersion",
                McpRpc.protocolVersion(params),
                "capabilities",
                Map.of(
                        "tools", Map.of("listChanged", false),
                        "resources", Map.of("subscribe", false, "listChanged", false)),
                "serverInfo",
                Map.of("name", PlatformMcp.PLATFORM_ID, "version", version),
                "instructions",
                "LoopForge platform operations: projects, requirements, SOP runs, catalog and analytics."
                        + " Mutating tools can start agents or change saved definitions.");
    }

    static List<PlatformOperations.Operation> visible(CurrentUser user) {
        return PlatformOperations.all().stream()
                .filter(op -> op.readOnly() || !user.readOnly())
                .toList();
    }

    private static List<Map<String, Object>> tools(CurrentUser user) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (PlatformOperations.Operation op : visible(user)) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", op.name());
            tool.put("description", op.description() + " (" + op.method() + " " + op.path() + ")");
            tool.put("inputSchema", inputSchema(op));
            tool.put(
                    "annotations",
                    Map.of(
                            "readOnlyHint",
                            op.readOnly(),
                            "destructiveHint",
                            !op.readOnly()
                                    && ("DELETE".equals(op.method())
                                            || op.name().contains("cancel")
                                            || op.name().contains("reject")),
                            "idempotentHint",
                            op.readOnly() || "PUT".equals(op.method()) || "DELETE".equals(op.method())));
            tools.add(tool);
        }
        return tools;
    }

    static Map<String, Object> inputSchema(PlatformOperations.Operation op) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<String> names = op.pathParameters();
        if (!names.isEmpty()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            names.forEach(n -> fields.put(n, Map.of("type", "string", "minLength", 1)));
            properties.put(
                    "path",
                    Map.of("type", "object", "properties", fields, "required", names, "additionalProperties", false));
            required.add("path");
        }
        properties.put(
                "query",
                Map.of(
                        "type", "object",
                        "description", "query parameters, see the tool description",
                        "additionalProperties", Map.of("type", "string")));
        if (op.hasBody()) {
            properties.put("body", Map.of("description", "JSON request body of the endpoint"));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> call(Object id, JsonNode params, CurrentUser user, String authorization) {
        String name = params == null ? "" : params.path("name").asString("");
        Optional<PlatformOperations.Operation> found =
                PlatformOperations.find(name).filter(op -> op.readOnly() || !user.readOnly());
        if (found.isEmpty()) {
            return McpRpc.error(id, McpRpc.INVALID_PARAMS, "unknown tool: " + clip(name));
        }
        PlatformOperations.Operation op = found.get();
        JsonNode arguments = params.get("arguments");
        if (arguments != null && !arguments.isNull() && !arguments.isObject()) {
            return McpRpc.result(id, failure("arguments must be an object", 400));
        }
        String target;
        try {
            target = target(op, arguments);
        } catch (IllegalArgumentException e) {
            return McpRpc.result(id, failure(e.getMessage(), 400));
        }
        String body = null;
        if (op.hasBody()) {
            JsonNode b = arguments == null ? null : arguments.get("body");
            body = b == null || b.isNull() ? "{}" : Json.write(b);
        }
        try {
            InternalApi.Response response = api.call(op.method(), target, body, authorization);
            return McpRpc.result(id, toolResult(response));
        } catch (IOException e) {
            log.warn("platform MCP call {} failed: {}", op.name(), e.toString());
            return McpRpc.result(id, failure("LoopForge API is unavailable", 0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpRpc.result(id, failure("interrupted", 0));
        }
    }

    /** The operation's path with encoded path and query parameters. */
    static String target(PlatformOperations.Operation op, JsonNode arguments) {
        JsonNode path = arguments == null ? null : arguments.get("path");
        String target = op.path();
        for (String name : op.pathParameters()) {
            JsonNode value = path == null ? null : path.get(name);
            String v = value == null || value.isNull() ? "" : value.asString("").strip();
            if (!SEGMENT.matcher(v).matches()) {
                throw new IllegalArgumentException("path." + name + " must be a plain identifier");
            }
            target = target.replace("{" + name + "}", UriUtils.encodePathSegment(v, StandardCharsets.UTF_8));
        }
        JsonNode query = arguments == null ? null : arguments.get("query");
        if (query != null && query.isObject()) {
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, JsonNode> e : query.properties()) {
                List<JsonNode> values = new ArrayList<>();
                if (e.getValue().isArray()) {
                    e.getValue().forEach(values::add);
                } else {
                    values.add(e.getValue());
                }
                for (JsonNode value : values) {
                    if (value.isNull() || value.isContainer()) {
                        continue;
                    }
                    q.append(q.isEmpty() ? '?' : '&')
                            .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                            .append('=')
                            .append(URLEncoder.encode(value.asString(""), StandardCharsets.UTF_8));
                }
            }
            target += q;
        }
        return target;
    }

    private static Map<String, Object> toolResult(InternalApi.Response response) {
        String text = response.body();
        boolean cut = response.truncated() || text.length() > MAX_TEXT;
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT);
        }
        if (text.isBlank()) {
            text = "HTTP " + response.status();
        }
        if (cut) {
            text += "\n…[truncated]";
        }
        Object structured = null;
        if (!cut && response.contentType().contains("json")) {
            try {
                structured = Map.of(
                        "status", response.status(), "data", Json.mapper().readTree(response.body()));
            } catch (JacksonException e) {
                // plain text
            }
        }
        return McpRpc.toolResult(text, !response.ok(), structured);
    }

    private static Map<String, Object> failure(String message, int status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", message);
        if (status != 0) {
            data.put("status", status);
        }
        return McpRpc.toolResult(Json.write(data), true, data);
    }

    private static List<Map<String, Object>> templates() {
        return List.of(
                Map.of(
                        "uriTemplate", "loopforge://runs/{runId}/artifacts/{artifactId}",
                        "name", "Run artifact",
                        "description", "Immutable artifact captured from a run",
                        "mimeType", "application/json"),
                Map.of(
                        "uriTemplate", "loopforge://runs/{runId}/events",
                        "name", "Run events",
                        "description", "The first events of a run (lifecycle, gates, executor telemetry)",
                        "mimeType", "application/json"));
    }

    private Map<String, Object> read(Object id, JsonNode params, String authorization) {
        String uri = params == null ? "" : params.path("uri").asString("");
        String target;
        try {
            URI parsed = URI.create(uri);
            String[] parts = parsed.getPath() == null
                    ? new String[0]
                    : parsed.getPath().replaceAll("^/+", "").split("/");
            if (!"loopforge".equals(parsed.getScheme()) || !"runs".equals(parsed.getHost())) {
                throw new IllegalArgumentException("unsupported resource URI");
            }
            if (parts.length == 3 && "artifacts".equals(parts[1])) {
                target = target(
                        PlatformOperations.find("runs_artifact_get").orElseThrow(),
                        Json.mapper().valueToTree(Map.of("path", Map.of("runId", parts[0], "artifactId", parts[2]))));
            } else if (parts.length == 2 && "events".equals(parts[1])) {
                target = target(
                        PlatformOperations.find("runs_events").orElseThrow(),
                        Json.mapper().valueToTree(Map.of("path", Map.of("runId", parts[0]))));
            } else {
                throw new IllegalArgumentException("unsupported resource URI");
            }
        } catch (IllegalArgumentException e) {
            return McpRpc.error(id, McpRpc.INVALID_PARAMS, "unsupported resource URI");
        }
        try {
            InternalApi.Response response = api.call("GET", target, null, authorization);
            if (!response.ok()) {
                return McpRpc.error(id, McpRpc.INVALID_PARAMS, "LoopForge API returned " + response.status());
            }
            String mime = response.contentType().split(";")[0].strip();
            return McpRpc.result(
                    id,
                    Map.of(
                            "contents",
                            List.of(Map.of(
                                    "uri",
                                    uri,
                                    "mimeType",
                                    mime.isEmpty() ? "text/plain" : mime,
                                    "text",
                                    response.body()))));
        } catch (IOException e) {
            return McpRpc.error(id, McpRpc.INTERNAL_ERROR, "LoopForge API is unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpRpc.error(id, McpRpc.INTERNAL_ERROR, "interrupted");
        }
    }

    private static String clip(String s) {
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }
}
