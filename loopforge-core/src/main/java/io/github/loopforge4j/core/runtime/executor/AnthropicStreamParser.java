package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Parser of the Claude Code / CodeBuddy {@code --output-format stream-json} protocol (upstream
 * adapter {@code anthropic-stream-v1}). Raw frames are discarded after normalization.
 */
public final class AnthropicStreamParser implements StreamParser {

    private final String provider;
    private final EventSink sink;
    private final Map<String, String> toolNames = new HashMap<>();
    private String sessionId;
    private String model;
    private String result;
    private String lastOutput;
    private boolean terminal;
    private boolean failed;
    private boolean maxTurns;
    private int toolCalls;
    private TokenUsage usage = TokenUsage.ZERO;

    public AnthropicStreamParser(String provider, EventSink sink) {
        this.provider = provider;
        this.sink = sink;
    }

    @Override
    public void accept(String line) {
        JsonNode frame = Json.tryParseObject(line);
        if (frame == null) {
            return;
        }
        String session = JsonFields.text(frame, "session_id");
        if (!session.isBlank()) {
            sessionId = session;
        }
        switch (JsonFields.text(frame, "type")) {
            case "system" -> onSystem(frame);
            case "assistant" -> onAssistant(frame);
            case "user" -> onUser(frame);
            case "result" -> onResult(frame);
            default -> {
                // partial stream events and housekeeping frames are not part of the portable view
            }
        }
    }

    private void onSystem(JsonNode frame) {
        if (!"init".equals(JsonFields.text(frame, "subtype"))) {
            return;
        }
        String frameModel = JsonFields.text(frame, "model");
        if (!frameModel.isBlank()) {
            model = frameModel;
        }
        emit(new Telemetry(
                "lifecycle",
                "agent.initialized",
                "start",
                "info",
                "agent session initialized",
                sessionId,
                null,
                null,
                Map.of("provider", provider, "model", model == null ? "" : model)));
    }

    private void onAssistant(JsonNode frame) {
        JsonNode content = frame.path("message").path("content");
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content) {
            switch (JsonFields.text(block, "type")) {
                case "text" -> {
                    String value = JsonFields.text(block, "text");
                    if (!value.isBlank()) {
                        if (!text.isEmpty()) {
                            text.append('\n');
                        }
                        text.append(value);
                        emit(new Telemetry(
                                "output",
                                "agent.output",
                                "progress",
                                "info",
                                JsonFields.clip(value, 4000),
                                sessionId,
                                null,
                                null,
                                Map.of()));
                    }
                }
                case "tool_use" -> {
                    String id = JsonFields.text(block, "id");
                    String name = JsonFields.text(block, "name");
                    toolNames.put(id, name);
                    toolCalls++;
                    emit(new Telemetry(
                            "tool",
                            "tool.call",
                            "start",
                            "info",
                            name,
                            sessionId,
                            id,
                            null,
                            Map.of(
                                    "tool",
                                    name,
                                    "input",
                                    JsonFields.clip(block.path("input").toString(), 2000))));
                }
                default -> {
                    // thinking blocks are summarized by the reasoning category only when streamed
                }
            }
        }
        if (!text.isEmpty()) {
            lastOutput = text.toString();
        }
    }

    private void onUser(JsonNode frame) {
        for (JsonNode block : frame.path("message").path("content")) {
            if (!"tool_result".equals(JsonFields.text(block, "type"))) {
                continue;
            }
            boolean error = JsonFields.bool(block, "is_error");
            String id = JsonFields.text(block, "tool_use_id");
            String name = toolNames.getOrDefault(id, "tool");
            emit(new Telemetry(
                    "tool",
                    "tool.call",
                    "finish",
                    error ? "error" : "info",
                    name + (error ? " failed" : " completed"),
                    sessionId,
                    id,
                    null,
                    Map.of("tool", name, "success", !error)));
        }
    }

    private void onResult(JsonNode frame) {
        terminal = true;
        result = JsonFields.text(frame, "result");
        failed = JsonFields.bool(frame, "is_error");
        maxTurns = JsonFields.text(frame, "subtype").contains("max_turns");
        usage = JsonFields.usage(frame.get("usage"), frame);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("durationMs", JsonFields.number(frame, "duration_ms"));
        attributes.put("turns", JsonFields.number(frame, "num_turns"));
        attributes.put("isError", failed);
        emit(new Telemetry(
                "usage", "usage.final", "finish", "info", "final model usage", sessionId, null, usage, attributes));
        emit(new Telemetry(
                "lifecycle",
                "agent.completed",
                "finish",
                failed ? "error" : "info",
                JsonFields.clip(result, 4000),
                sessionId,
                null,
                null,
                Map.of()));
    }

    private void emit(Telemetry telemetry) {
        sink.emit(telemetry);
    }

    @Override
    public AgentResult result(int exitCode) {
        String visible = result != null && !result.isBlank() ? result : lastOutput;
        return new AgentResult(visible, sessionId, model, usage, toolCalls, terminal, failed, maxTurns, exitCode);
    }
}
