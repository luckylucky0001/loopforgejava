package io.github.loopforge4j.server.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** JSON-RPC 2.0 framing shared by the stateless Streamable HTTP MCP endpoints. */
public final class McpRpc {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private static final Set<String> VERSIONS = Set.of("2024-11-05", "2025-03-26", "2025-06-18");
    public static final String LATEST = "2025-06-18";

    private McpRpc() {}

    public static Map<String, Object> result(Object id, Object result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("result", result);
        return out;
    }

    public static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }

    /** The request id as a plain value (number or string), {@code null} when absent. */
    public static Object id(JsonNode request) {
        JsonNode id = request.get("id");
        if (id == null || id.isNull()) {
            return null;
        }
        return id.isNumber() ? id.numberValue() : id.asString();
    }

    /** The client's protocol version when supported, else the newest this server speaks. */
    public static String protocolVersion(JsonNode params) {
        JsonNode version = params == null ? null : params.get("protocolVersion");
        String v = version == null || !version.isString() ? "" : version.asString();
        return VERSIONS.contains(v) ? v : LATEST;
    }

    /** A text tool result; {@code structured} is added as structured content when not null. */
    public static Map<String, Object> toolResult(String text, boolean error, Object structured) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", text)));
        out.put("isError", error);
        if (structured != null) {
            out.put("structuredContent", structured);
        }
        return out;
    }
}
