package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.json.Json;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders Claude-style stream-json lines (upstream {@code ClaudeStream}) so every agent CLI's native
 * stream can be read by {@link AnthropicStreamParser}: deltas become stream events, finished text and
 * tool calls assistant blocks, tool output user tool_result blocks, and the turn ends with one
 * result line. Every line carries the CLI's session id once known.
 */
final class ClaudeStream {

    String sessionId;
    String lastText;
    private String block;
    private final Set<String> started = new HashSet<>();
    private Map<String, Long> usage;
    private boolean done;

    private String line(Map<String, Object> value) {
        if (sessionId != null && !sessionId.isBlank()) {
            value.put("session_id", sessionId);
        }
        return Json.write(value);
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    List<String> init(String session, String model) {
        if (session != null && !session.isBlank()) {
            sessionId = session.strip();
        }
        Map<String, Object> event = map("type", "system", "subtype", "init");
        if (model != null && !model.isBlank()) {
            event.put("model", model);
        }
        return List.of(line(event));
    }

    List<String> delta(String key, String kind, String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (!key.equals(block)) {
            out.addAll(closeBlock());
            out.add(line(map(
                    "type",
                    "stream_event",
                    "event",
                    map("type", "content_block_start", "content_block", map("type", kind)))));
            block = key;
        }
        Map<String, Object> delta = "thinking".equals(kind)
                ? map("type", "thinking_delta", "thinking", text)
                : map("type", "text_delta", "text", text);
        out.add(line(map("type", "stream_event", "event", map("type", "content_block_delta", "delta", delta))));
        return out;
    }

    List<String> closeBlock() {
        if (block == null) {
            return List.of();
        }
        block = null;
        return List.of(line(map("type", "stream_event", "event", map("type", "content_block_stop"))));
    }

    private List<String> assistant(Map<String, Object> content) {
        List<String> out = new ArrayList<>(closeBlock());
        out.add(line(map("type", "assistant", "message", map("role", "assistant", "content", List.of(content)))));
        return out;
    }

    List<String> text(String text) {
        if (text == null || text.isBlank()) {
            return closeBlock();
        }
        lastText = text;
        return assistant(map("type", "text", "text", text));
    }

    List<String> thinking(String text) {
        if (text == null || text.isBlank()) {
            return closeBlock();
        }
        return assistant(map("type", "thinking", "thinking", text));
    }

    List<String> toolUse(String id, String name, Object input) {
        if (id == null || id.isEmpty() || name == null || name.isEmpty() || !started.add(id)) {
            return List.of();
        }
        return assistant(map("type", "tool_use", "id", id, "name", name, "input", input == null ? Map.of() : input));
    }

    List<String> toolResult(String id, boolean failed, String content) {
        if (id == null || id.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(closeBlock());
        out.add(line(map(
                "type",
                "user",
                "message",
                map(
                        "role",
                        "user",
                        "content",
                        List.of(map(
                                "type",
                                "tool_result",
                                "tool_use_id",
                                id,
                                "is_error",
                                failed,
                                "content",
                                content == null ? "" : content))))));
        return out;
    }

    void addUsage(long input, long output, long cacheRead, long cacheWrite, long reasoning) {
        if (usage == null) {
            usage = new LinkedHashMap<>();
        }
        usage.merge("input_tokens", input, Long::sum);
        usage.merge("output_tokens", output, Long::sum);
        usage.merge("cache_read_input_tokens", cacheRead, Long::sum);
        usage.merge("cache_creation_input_tokens", cacheWrite, Long::sum);
        usage.merge("reasoning_tokens", reasoning, Long::sum);
    }

    void setUsage(long input, long output, long cacheRead, long cacheWrite, long reasoning) {
        usage = null;
        addUsage(input, output, cacheRead, cacheWrite, reasoning);
    }

    List<String> result(String text, boolean failed) {
        if (done) {
            return List.of();
        }
        done = true;
        if ((text == null || text.isBlank()) && !failed) {
            text = lastText;
        }
        Map<String, Object> event = map(
                "type",
                "result",
                "subtype",
                failed ? "error" : "success",
                "is_error",
                failed,
                "result",
                text == null ? "" : text);
        if (usage != null) {
            event.put("usage", usage);
        }
        List<String> out = new ArrayList<>(closeBlock());
        out.add(line(event));
        return out;
    }

    boolean finished() {
        return done;
    }
}
