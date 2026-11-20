package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Translators from each agent CLI's headless JSON stream to {@link ClaudeStream} lines (port of
 * upstream {@code agent_cli_stream.go} and the ZCode translator).
 */
final class AgentCliTranslators {

    private AgentCliTranslators() {}

    /** Returns a stateful line translator for the CLI kind, or {@code null} for unknown kinds. */
    static Function<String, List<String>> forKind(String kind) {
        ClaudeStream stream = new ClaudeStream();
        Translator translator = switch (kind) {
            case AgentCliExecutor.OPENCODE -> new OpenCode();
            case AgentCliExecutor.CURSOR -> new Cursor();
            case AgentCliExecutor.ANTIGRAVITY -> new Antigravity();
            case AgentCliExecutor.KIMI -> new Kimi();
            case AgentCliExecutor.PI -> new Pi();
            case AgentCliExecutor.DEEPSEEK -> new DeepSeek();
            case AgentCliExecutor.ZCODE -> new ZCode();
            default -> null;
        };
        if (translator == null) {
            return null;
        }
        return line -> {
            String trimmed = line == null ? "" : line.strip();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
                return List.of();
            }
            JsonNode raw = Json.tryParseObject(trimmed);
            return raw == null ? List.of() : translator.translate(stream, raw);
        };
    }

    interface Translator {
        List<String> translate(ClaudeStream s, JsonNode raw);
    }

    // ------------------------------------------------------------------ helpers

    static String str(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.stringValue() : "";
    }

    static long num(JsonNode node, String field) {
        if (node == null) {
            return 0;
        }
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.longValue() : 0;
    }

    static JsonNode obj(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isObject() ? v : null;
    }

    static Object value(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode()
                ? null
                : Json.mapper().convertValue(node, Object.class);
    }

    static String jsonText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isString() ? node.stringValue() : node.toString();
    }

    /** Joins the text parts of an MCP / chat content value. */
    static String contentText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return "";
        }
        if (value.isString()) {
            return value.stringValue();
        }
        List<String> parts = new ArrayList<>();
        if (value.isArray()) {
            for (JsonNode item : value) {
                String text = str(item, "text");
                if (!text.isEmpty()) {
                    parts.add(text);
                }
            }
        }
        return parts.isEmpty() ? value.toString() : String.join("\n", parts);
    }

    static String errorText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isString()) {
            return value.stringValue();
        }
        if (value.isObject()) {
            for (String key : List.of("message", "error")) {
                String text = errorText(value.get(key));
                if (!text.isEmpty()) {
                    return text;
                }
            }
            JsonNode data = obj(value, "data");
            if (data != null) {
                String text = errorText(data);
                if (!text.isEmpty()) {
                    return text;
                }
            }
            return str(value, "name");
        }
        return "";
    }

    private static List<String> concat(List<String> a, List<String> b) {
        if (a.isEmpty()) {
            return b;
        }
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    // ------------------------------------------------------------------ OpenCode

    /** {@code opencode run --format json} reports finished parts. */
    static final class OpenCode implements Translator {
        private boolean started;
        private String failure = "";

        @Override
        public List<String> translate(ClaudeStream s, JsonNode raw) {
            List<String> out = new ArrayList<>();
            if (!started && !str(raw, "sessionID").isEmpty()) {
                started = true;
                out.addAll(s.init(str(raw, "sessionID"), ""));
            }
            JsonNode part = obj(raw, "part");
            switch (str(raw, "type")) {
                case "reasoning" -> out.addAll(s.thinking(str(part, "text")));
                case "text" -> out.addAll(s.text(str(part, "text")));
                case "tool_use" -> {
                    JsonNode state = obj(part, "state");
                    String id = str(part, "callID");
                    out.addAll(s.toolUse(id, str(part, "tool"), value(state == null ? null : state.get("input"))));
                    switch (str(state, "status")) {
                        case "completed" -> out.addAll(s.toolResult(id, false, jsonText(state.get("output"))));
                        case "error" -> out.addAll(s.toolResult(id, true, jsonText(state.get("error"))));
                        default -> {}
                    }
                }
                case "step_finish" -> {
                    JsonNode tokens = obj(part, "tokens");
                    JsonNode cache = obj(tokens, "cache");
                    s.addUsage(
                            num(tokens, "input"),
                            num(tokens, "output"),
                            num(cache, "read"),
                            num(cache, "write"),
                            num(tokens, "reasoning"));
                    String reason = str(part, "reason");
                    if (!reason.isEmpty() && !reason.equals("tool-calls")) {
                        out.addAll(s.result("", !failure.isEmpty() && s.lastText == null));
                    }
                }
                case "error" -> {
                    failure = errorText(raw.get("error"));
                    if (failure.isEmpty()) {
                        failure = "OpenCode 运行失败";
                    }
                    out.addAll(s.result(failure, true));
                }
                default -> {}
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ Cursor

    /** {@code cursor-agent -p --output-format stream-json --stream-partial-output}. */
    static final class Cursor implements Translator {
        private int segment;
        private final StringBuilder partial = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();

        private List<String> flushText(ClaudeStream s) {
            String text = partial.toString();
            partial.setLength(0);
            segment++;
            return s.text(text);
        }

        private List<String> flushThinking(ClaudeStream s) {
            String text = thinking.toString();
            thinking.setLength(0);
            if (text.isEmpty()) {
                return List.of();
            }
            segment++;
            return s.thinking(text);
        }

        @Override
        public List<String> translate(ClaudeStream s, JsonNode raw) {
            switch (str(raw, "type")) {
                case "system" -> {
                    if ("init".equals(str(raw, "subtype"))) {
                        return s.init(str(raw, "session_id"), str(raw, "model"));
                    }
                }
                case "thinking" -> {
                    if ("delta".equals(str(raw, "subtype"))) {
                        String text = str(raw, "text");
                        thinking.append(text);
                        return s.delta("thinking#" + segment, "thinking", text);
                    }
                    return flushThinking(s);
                }
                case "assistant" -> {
                    String text = contentText(
                            obj(raw, "message") == null
                                    ? null
                                    : raw.get("message").get("content"));
                    List<String> out = flushThinking(s);
                    if (raw.has("timestamp_ms")) {
                        partial.append(text);
                        return concat(out, s.delta("text#" + segment, "text", text));
                    }
                    partial.setLength(0);
                    segment++;
                    return concat(out, s.text(text));
                }
                case "tool_call" -> {
                    List<String> out = new ArrayList<>(flushThinking(s));
                    out.addAll(flushText(s));
                    String id = str(raw, "call_id");
                    Object[] call = toolCall(raw);
                    out.addAll(s.toolUse(id, (String) call[0], call[1]));
                    if ("completed".equals(str(raw, "subtype"))) {
                        JsonNode result = (JsonNode) call[2];
                        JsonNode success = result == null ? null : result.get("success");
                        JsonNode failure = result == null ? null : result.get("error");
                        if (success == null && (failure == null || failure.isNull()) && result != null) {
                            failure = result.get("rejected");
                        }
                        if (failure != null && !failure.isNull()) {
                            out.addAll(s.toolResult(id, true, jsonText(failure)));
                        } else {
                            JsonNode content = success != null && success.isObject() ? success.get("content") : null;
                            out.addAll(s.toolResult(id, false, contentText(content == null ? success : content)));
                        }
                    }
                    return out;
                }
                case "result" -> {
                    List<String> out = new ArrayList<>(flushThinking(s));
                    out.addAll(flushText(s));
                    JsonNode usage = obj(raw, "usage");
                    s.setUsage(
                            num(usage, "inputTokens"),
                            num(usage, "outputTokens"),
                            num(usage, "cacheReadTokens"),
                            num(usage, "cacheWriteTokens"),
                            0);
                    boolean failed = raw.path("is_error").asBoolean(false);
                    String text = str(raw, "result");
                    if (failed && text.isEmpty()) {
                        text = errorText(raw.get("error"));
                    }
                    out.addAll(s.result(text, failed));
                    return out;
                }
                default -> {}
            }
            return List.of();
        }

        /** [name, input, result] of a Cursor tool call. */
        private static Object[] toolCall(JsonNode raw) {
            JsonNode call = obj(raw, "tool_call");
            if (call == null) {
                return new Object[] {"", null, null};
            }
            for (Map.Entry<String, JsonNode> entry : call.properties()) {
                String key = entry.getKey();
                if (!key.endsWith("ToolCall")) {
                    continue;
                }
                JsonNode body = entry.getValue();
                JsonNode args = obj(body, "args");
                JsonNode result = obj(body, "result");
                if (key.equals("mcpToolCall")) {
                    String server = str(args, "providerIdentifier");
                    if (server.isEmpty()) {
                        server = str(args, "serverIdentifier");
                    }
                    return new Object[] {
                        "mcp__" + server + "__" + str(args, "toolName"),
                        value(args == null ? null : args.get("args")),
                        result
                    };
                }
                if (key.equals("shellToolCall")) {
                    return new Object[] {
                        "Bash", Map.of("command", jsonText(args == null ? null : args.get("command"))), result
                    };
                }
                String name = key.substring(0, key.length() - "ToolCall".length());
                if (!name.isEmpty()) {
                    name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                }
                Object input = value(args);
                if (input instanceof Map<?, ?> m) {
                    Map<Object, Object> copy = new java.util.LinkedHashMap<>(m);
                    copy.remove("toolCallId");
                    input = copy;
                }
                return new Object[] {name, input, result};
            }
            return new Object[] {"", null, null};
        }
    }

    // ------------------------------------------------------------------ Antigravity

    /** {@code agy -p --output-format stream-json}. */
    static final class Antigravity implements Translator {
        private final StringBuilder text = new StringBuilder();
        private String failure = "";

        @Override
        public List<String> translate(ClaudeStream s, JsonNode raw) {
            switch (str(raw, "event")) {
                case "init" -> {
                    return s.init(str(raw, "conversation_id"), str(obj(raw, "init"), "model"));
                }
                case "step_update" -> {
                    return step(s, obj(raw, "step_update"));
                }
                case "result" -> {
                    JsonNode result = obj(raw, "result");
                    boolean failed = !"SUCCESS".equals(str(result, "status"));
                    String response = str(result, "response");
                    if (failed) {
                        response = errorText(result == null ? null : result.get("error"));
                        if (response.isEmpty()) {
                            response = failure.isEmpty() ? "Antigravity 运行失败" : failure;
                        }
                    }
                    JsonNode usage = obj(result, "usage");
                    if (usage != null) {
                        s.setUsage(
                                num(usage, "input_tokens"),
                                num(usage, "output_tokens"),
                                num(usage, "cache_read_tokens"),
                                0,
                                num(usage, "thinking_tokens"));
                    }
                    return s.result(response, failed);
                }
                default -> {}
            }
            return List.of();
        }

        private List<String> step(ClaudeStream s, JsonNode step) {
            long index = num(step, "step_index");
            boolean done = "DONE".equals(str(step, "state"));
            switch (str(step, "step_type")) {
                case "agent_response" -> {
                    String delta = str(step, "text_delta");
                    text.append(delta);
                    List<String> out = new ArrayList<>(s.delta("text#" + index, "text", delta));
                    if (done) {
                        JsonNode usage = obj(step, "usage");
                        if (usage != null) {
                            s.addUsage(
                                    num(usage, "input_tokens"),
                                    num(usage, "output_tokens"),
                                    num(usage, "cache_read_tokens"),
                                    0,
                                    num(usage, "thinking_tokens"));
                        }
                        out.addAll(s.text(text.toString()));
                        text.setLength(0);
                    }
                    return out;
                }
                case "tool" -> {
                    JsonNode info = obj(step, "tool_info");
                    String id = str(step, "conversation_id") + "#" + index;
                    String name = str(info, "name");
                    JsonNode input = info == null ? null : info.get("parameters");
                    if (input != null && input.isObject() && name.equals("call_mcp_tool")) {
                        name = "mcp__" + str(input, "ServerName") + "__" + str(input, "ToolName");
                        input = input.get("Arguments");
                    }
                    List<String> out = new ArrayList<>(s.toolUse(id, name, value(input)));
                    if (done) {
                        String failed = errorText(info == null ? null : info.get("error"));
                        out.addAll(
                                failed.isEmpty()
                                        ? s.toolResult(id, false, jsonText(info == null ? null : info.get("output")))
                                        : s.toolResult(id, true, failed));
                    }
                    return out;
                }
                case "error_message" -> {
                    String message = errorText(step == null ? null : step.get("error"));
                    if (!message.isEmpty()) {
                        failure = message;
                    }
                }
                default -> {}
            }
            return List.of();
        }
    }

    // ------------------------------------------------------------------ Kimi

    /** {@code kimi -p --output-format stream-json}; the session id arrives with the resume hint. */
    static final class Kimi implements Translator {
        private boolean started;

        @Override
        public List<String> translate(ClaudeStream s, JsonNode raw) {
            List<String> out = new ArrayList<>();
            if (!started) {
                started = true;
                out.addAll(s.init("", ""));
            }
            switch (str(raw, "role")) {
                case "assistant" -> {
                    JsonNode content = raw.get("content");
                    if (content != null && content.isString()) {
                        out.addAll(s.text(content.stringValue()));
                    } else if (content != null && content.isArray()) {
                        for (JsonNode part : content) {
                            switch (str(part, "type")) {
                                case "text" -> out.addAll(s.text(str(part, "text")));
                                case "think", "thinking" -> {
                                    String t = str(part, "think");
                                    out.addAll(s.thinking(t.isEmpty() ? str(part, "thinking") : t));
                                }
                                default -> {}
                            }
                        }
                    }
                    JsonNode calls = raw.get("tool_calls");
                    if (calls != null && calls.isArray()) {
                        for (JsonNode call : calls) {
                            JsonNode function = obj(call, "function");
                            Object input = Map.of();
                            String arguments = str(function, "arguments");
                            if (!arguments.isEmpty()) {
                                JsonNode parsed = Json.tryParseObject(arguments);
                                if (parsed != null) {
                                    input = value(parsed);
                                }
                            }
                            out.addAll(s.toolUse(str(call, "id"), str(function, "name"), input));
                        }
                    }
                }
                case "tool" ->
                    out.addAll(s.toolResult(str(raw, "tool_call_id"), false, contentText(raw.get("content"))));
                case "meta" -> {
                    if ("session.resume_hint".equals(str(raw, "type"))) {
                        s.sessionId = str(raw, "session_id");
                        out.addAll(s.init(s.sessionId, ""));
                        out.addAll(s.result("", false));
                    }
                }
                default -> {}
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ Pi

    /** {@code pi --mode json}: deltas per content index plus finished messages and tool runs. */
    static final class Pi implements Translator {
        private int turn;
        private String failure = "";

        @Override
        public List<String> translate(ClaudeStream s, JsonNode raw) {
            switch (str(raw, "type")) {
                case "session" -> {
                    return s.init(str(raw, "id"), "");
                }
                case "turn_start" -> turn++;
                case "message_update" -> {
                    JsonNode event = obj(raw, "assistantMessageEvent");
                    String key = turn + "#" + num(event, "contentIndex");
                    switch (str(event, "type")) {
                        case "text_delta" -> {
                            return s.delta(key, "text", str(event, "delta"));
                        }
                        case "thinking_delta" -> {
                            return s.delta(key, "thinking", str(event, "delta"));
                        }
                        default -> {}
                    }
                }
                case "message_end" -> {
                    JsonNode message = obj(raw, "message");
                    if (!"assistant".equals(str(message, "role"))) {
                        return List.of();
                    }
                    JsonNode usage = obj(message, "usage");
                    s.addUsage(
                            num(usage, "input"),
                            num(usage, "output"),
                            num(usage, "cacheRead"),
                            num(usage, "cacheWrite"),
                            num(usage, "reasoning"));
                    if ("error".equals(str(message, "stopReason"))) {
                        failure = str(message, "errorMessage");
                    }
                    List<String> out = new ArrayList<>();
                    JsonNode content = message.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode part : content) {
                            switch (str(part, "type")) {
                                case "text" -> out.addAll(s.text(str(part, "text")));
                                case "thinking" -> out.addAll(s.thinking(str(part, "thinking")));
                                case "toolCall" ->
                                    out.addAll(s.toolUse(
                                            str(part, "id"), str(part, "name"), value(part.get("arguments"))));
                                default -> {}
                            }
                        }
                    }
                    out.addAll(s.closeBlock());
                    return out;
                }
                case "tool_execution_start" -> {
                    if (str(raw, "parentToolCallId").isEmpty()) {
                        return List.of();
                    }
                    return s.toolUse(str(raw, "toolCallId"), str(raw, "toolName"), value(raw.get("args")));
                }
                case "tool_execution_end" -> {
                    JsonNode result = obj(raw, "result");
                    return s.toolResult(
                            str(raw, "toolCallId"),
                            raw.path("isError").asBoolean(false),
                            contentText(result == null ? null : result.get("content")));
                }
                case "agent_end" -> {
                    if (!failure.isEmpty() && s.lastText == null) {
                        return s.result(failure, true);
                    }
                    return s.result("", false);
                }
                default -> {}
            }
            return List.of();
        }
    }

    // ------------------------------------------------------------------ DeepSeek Harness

    /**
     * {@code dsh --profile headless --json}: event strings are capped at 8 KiB and flagged {@code
     * truncated}; only {@code final} carries the whole answer, so a truncated text is held back.
     */
    static final class DeepSeek implements Translator {
        private String failure = "";
        private String truncatedText = "";

        @Override
        public List<String> translate(ClaudeStream s, JsonNode raw) {
            String kind = str(raw, "type");
            List<String> out = new ArrayList<>();
            if (!truncatedText.isEmpty()
                    && List.of("thinking", "text", "tool_call", "tool_result").contains(kind)) {
                out.addAll(s.text(truncatedText));
                truncatedText = "";
            }
            switch (kind) {
                case "session" -> out.addAll(s.init(str(raw, "sessionId"), ""));
                case "thinking" -> out.addAll(s.thinking(str(raw, "text")));
                case "text" -> {
                    if (raw.path("truncated").asBoolean(false)) {
                        truncatedText = str(raw, "text");
                    } else {
                        out.addAll(s.text(str(raw, "text")));
                    }
                }
                case "tool_call" ->
                    out.addAll(s.toolUse(str(raw, "callId"), str(raw, "tool"), value(raw.get("input"))));
                case "tool_result" ->
                    out.addAll(s.toolResult(
                            str(raw, "callId"), !"completed".equals(str(raw, "status")), jsonText(raw.get("result"))));
                case "status" -> {
                    if ("step_end".equals(str(raw, "phase"))) {
                        JsonNode usage = obj(raw, "usage");
                        s.addUsage(
                                num(usage, "inputTokens"),
                                num(usage, "outputTokens"),
                                num(usage, "cacheReadTokens"),
                                num(usage, "cacheWriteTokens"),
                                num(usage, "reasoningTokens"));
                    }
                }
                case "error" -> failure = errorText(raw);
                case "final" -> {
                    if (!str(raw, "sessionId").isEmpty()) {
                        s.sessionId = str(raw, "sessionId");
                    }
                    JsonNode reason = obj(raw, "reason");
                    boolean failed = !"completed".equals(str(reason, "kind"));
                    String text = str(raw, "text");
                    if (!truncatedText.isEmpty()) {
                        String held = text.startsWith(truncatedText) ? text : truncatedText;
                        truncatedText = "";
                        out.addAll(s.text(held));
                    }
                    if (failed && text.isEmpty()) {
                        text = failure.isEmpty() ? "DeepSeek Harness 运行结束：" + str(reason, "kind") : failure;
                    }
                    out.addAll(s.result(text, failed));
                }
                default -> {}
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ ZCode

    /** {@code zcode --output-format stream-json}: session events with payloads. */
    static final class ZCode implements Translator {
        private boolean started;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();

        @Override
        public List<String> translate(ClaudeStream s, JsonNode raw) {
            List<String> out = new ArrayList<>();
            if (!started && !str(raw, "sessionId").isEmpty()) {
                started = true;
                out.addAll(s.init(str(raw, "sessionId"), ""));
            }
            JsonNode payload = obj(raw, "payload");
            switch (str(raw, "type")) {
                case "model.streaming" -> {
                    String message = str(payload, "assistantMessageId");
                    switch (str(payload, "kind")) {
                        case "text_delta" -> {
                            String delta = str(payload, "delta");
                            text.append(delta);
                            out.addAll(s.delta(message + "#text", "text", delta));
                        }
                        case "text_end" -> {
                            out.addAll(s.text(text.toString()));
                            text.setLength(0);
                        }
                        case "reasoning_delta" -> {
                            String delta = str(payload, "delta");
                            reasoning.append(delta);
                            out.addAll(s.delta(message + "#thinking", "thinking", delta));
                        }
                        case "reasoning_end" -> {
                            out.addAll(s.thinking(reasoning.toString()));
                            reasoning.setLength(0);
                        }
                        case "tool_call" ->
                            out.addAll(s.toolUse(
                                    str(payload, "toolCallId"),
                                    str(payload, "toolName"),
                                    value(payload == null ? null : payload.get("input"))));
                        default -> {}
                    }
                }
                case "tool.updated" -> {
                    if ("result".equals(str(payload, "kind"))) {
                        JsonNode result = obj(payload, "result");
                        boolean success =
                                result != null && result.path("success").asBoolean(false);
                        out.addAll(s.toolResult(
                                str(payload, "toolCallId"),
                                !success,
                                contentText(result == null ? null : result.get("content"))));
                    }
                }
                case "turn.failed" -> {
                    JsonNode failure = obj(payload, "error");
                    String message = str(failure, "message");
                    if (message.isEmpty()) {
                        message = "ZCode 本轮执行失败";
                    }
                    if (num(obj(failure, "attribution"), "statusCode") == 401) {
                        message += " (HTTP 401)";
                    }
                    out.addAll(s.result(message, true));
                }
                case "result" -> {
                    JsonNode usage = obj(raw, "usage");
                    s.setUsage(
                            num(usage, "inputTokens"),
                            num(usage, "outputTokens"),
                            num(usage, "cacheReadTokens"),
                            num(usage, "cacheWriteTokens"),
                            num(usage, "reasoningTokens"));
                    out.addAll(s.result(str(raw, "response"), false));
                }
                default -> {}
            }
            return out;
        }
    }
}
