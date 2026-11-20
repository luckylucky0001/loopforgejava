package io.github.loopforge4j.server.knowledge;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.server.mcp.McpRpc;
import io.github.loopforge4j.server.mcp.RunTokens;
import io.github.loopforge4j.server.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * The read-only knowledge base MCP agents get (upstream knowledge proxy): stateless Streamable
 * HTTP, authenticated by a knowledge run token, every call narrowed to the run's project sources.
 * The upstream token never leaves the server.
 */
@RestController
public class KnowledgeProxyController {

    public static final String PATH = "/api/v1/knowledge/mcp";

    private final KnowledgeService knowledge;
    private final RunTokens tokens;

    public KnowledgeProxyController(KnowledgeService knowledge, RunTokens tokens) {
        this.knowledge = knowledge;
        this.tokens = tokens;
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> post(@RequestBody String raw, HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7) : null;
        Optional<RunTokens.Claims> claims = tokens.verify(token, RunTokens.Kind.KNOWLEDGE);
        if (claims.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer realm=\"LoopForge Knowledge\"")
                    .body(Map.of("error", "invalid knowledge token"));
        }
        if (!knowledge.ready()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", KnowledgeService.NOT_CONFIGURED));
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
        Object response = switch (method) {
            case "initialize" ->
                McpRpc.result(
                        id,
                        Map.of(
                                "protocolVersion", McpRpc.protocolVersion(params),
                                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                                "serverInfo", Map.of("name", PlatformMcp.KNOWLEDGE_ID, "version", "1.0.0"),
                                "instructions", "团队知识库（只读）。先 kb_search 检索，再用 kb_fetch 读原文；引用时附上 citation。"));
            case "ping" -> McpRpc.result(id, Map.of());
            case "tools/list" -> list(id);
            case "tools/call" -> call(id, params, claims.get());
            default -> McpRpc.error(id, McpRpc.METHOD_NOT_FOUND, "method not found");
        };
        return ResponseEntity.ok()
                .header("MCP-Protocol-Version", McpRpc.protocolVersion(params))
                .body(response);
    }

    private Map<String, Object> list(Object id) {
        try {
            return McpRpc.result(id, Map.of("tools", knowledge.readOnlyTools()));
        } catch (IOException e) {
            return McpRpc.error(id, McpRpc.INTERNAL_ERROR, "知识库不可达：" + e.getMessage());
        }
    }

    private Map<String, Object> call(Object id, JsonNode params, RunTokens.Claims claims) {
        String name = params == null ? "" : params.path("name").asString("").strip();
        int qualified = name.lastIndexOf("__");
        String tool = qualified >= 0 ? name.substring(qualified + 2) : name;
        if (!KnowledgeService.READ_ONLY_TOOLS.contains(tool)) {
            return McpRpc.error(
                    id, McpRpc.INVALID_PARAMS, "知识库工具 " + name + " 不可用：Agent 只能只读检索，新知识请写入 knowledge.md 等待审核");
        }
        JsonNode argNode = params.get("arguments");
        Map<String, Object> arguments = argNode == null || !argNode.isObject()
                ? Map.of()
                : Json.mapper().convertValue(argNode, new TypeReference<Map<String, Object>>() {});
        long started = System.nanoTime();
        try {
            KnowledgeService.Scoped result = knowledge.callScoped(claims.projectId(), tool, arguments);
            knowledge.record(
                    tool,
                    claims.projectId(),
                    claims.runId(),
                    claims.agentId(),
                    "agent",
                    arguments,
                    result.hits(),
                    null,
                    started);
            return McpRpc.result(id, McpRpc.toolResult(result.text(), false, null));
        } catch (IOException | ApiException e) {
            knowledge.record(
                    tool,
                    claims.projectId(),
                    claims.runId(),
                    claims.agentId(),
                    "agent",
                    arguments,
                    0,
                    e.getMessage(),
                    started);
            return McpRpc.result(id, McpRpc.toolResult(e.getMessage(), true, null));
        }
    }
}
