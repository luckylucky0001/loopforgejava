package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Parser of {@code codex exec --json} JSONL (upstream adapter {@code codex-jsonl-v1}). Codex has no
 * terminal result frame; a completed turn counts as terminal and the last agent message is the
 * output.
 */
public final class CodexJsonParser implements StreamParser {

    private final EventSink sink;
    private String threadId;
    private String lastMessage;
    private String error;
    private boolean terminal;
    private int toolCalls;
    private TokenUsage usage = TokenUsage.ZERO;

    public CodexJsonParser(EventSink sink) {
        this.sink = sink;
    }

    @Override
    public void accept(String line) {
        JsonNode frame = Json.tryParseObject(line);
        if (frame == null) {
            return;
        }
        String thread = JsonFields.text(frame, "thread_id");
        if (!thread.isBlank()) {
            threadId = thread;
        }
        switch (JsonFields.text(frame, "type")) {
            case "thread.started" ->
                sink.emit(new Telemetry(
                        "lifecycle",
                        "agent.initialized",
                        "start",
                        "info",
                        "codex thread initialized",
                        threadId,
                        null,
                        null,
                        Map.of()));
            case "turn.completed" -> {
                terminal = true;
                usage = usage.plus(JsonFields.usage(frame.get("usage"), null));
                sink.emit(new Telemetry(
                        "usage",
                        "usage.final",
                        "finish",
                        "info",
                        "final model usage",
                        threadId,
                        null,
                        usage,
                        Map.of()));
            }
            case "turn.failed", "error" -> {
                String message = JsonFields.text(frame.path("error"), "message");
                if (message.isBlank()) {
                    message = JsonFields.text(frame, "message");
                }
                error = message.isBlank() ? "codex turn failed" : message;
                sink.emit(new Telemetry(
                        "error", "model.error", "finish", "error", error, threadId, null, null, Map.of()));
            }
            case "item.started", "item.completed" -> onItem(frame, JsonFields.text(frame, "type"));
            default -> {
                // item.updated and unknown frames are intentionally dropped
            }
        }
    }

    private void onItem(JsonNode frame, String type) {
        JsonNode item = frame.path("item");
        String phase = "item.started".equals(type) ? "start" : "finish";
        String id = JsonFields.text(item, "id");
        switch (JsonFields.text(item, "type")) {
            case "command_execution", "mcp_tool_call", "tool_call" -> {
                String name = JsonFields.text(item, "command");
                if (name.isBlank()) {
                    name = JsonFields.text(item, "name");
                }
                boolean success =
                        JsonFields.number(item, "exit_code") == 0 && !"failed".equals(JsonFields.text(item, "status"));
                if ("start".equals(phase)) {
                    toolCalls++;
                }
                sink.emit(new Telemetry(
                        "tool",
                        "tool.call",
                        phase,
                        success ? "info" : "error",
                        JsonFields.clip(name, 500),
                        threadId,
                        id,
                        null,
                        Map.of("success", success)));
            }
            case "agent_message" -> {
                String text = JsonFields.text(item, "text");
                if (!text.isBlank()) {
                    lastMessage = text;
                }
                sink.emit(new Telemetry(
                        "output",
                        "agent.output",
                        phase,
                        "info",
                        JsonFields.clip(text, 4000),
                        threadId,
                        null,
                        null,
                        Map.of()));
            }
            case "reasoning" ->
                sink.emit(new Telemetry(
                        "reasoning",
                        "agent.reasoning",
                        phase,
                        "info",
                        JsonFields.clip(JsonFields.text(item, "text"), 2000),
                        threadId,
                        null,
                        null,
                        Map.of()));
            default -> {
                // file changes and plans are visible through the workspace itself
            }
        }
    }

    @Override
    public AgentResult result(int exitCode) {
        boolean failed = error != null && !terminal;
        String output = lastMessage != null ? lastMessage : error;
        return new AgentResult(output, threadId, null, usage, toolCalls, terminal, failed, false, exitCode);
    }
}
