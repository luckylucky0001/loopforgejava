package io.github.loopforge4j.core.runtime;

import io.github.loopforge4j.core.domain.TokenUsage;
import java.util.Map;

/**
 * Provider-neutral observation emitted while a node executes. Categories follow the upstream
 * observability spec: lifecycle, model, reasoning, output, tool, usage, process, verification,
 * artifact, sandbox, error.
 */
public record Telemetry(
        String category,
        String name,
        String phase,
        String level,
        String message,
        String sessionId,
        String toolCallId,
        TokenUsage usage,
        Map<String, Object> attributes) {

    public Telemetry {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        level = level == null ? "info" : level;
    }

    public static Telemetry of(String category, String name, String phase, String message) {
        return new Telemetry(category, name, phase, "info", message, null, null, null, Map.of());
    }

    public Telemetry withLevel(String newLevel) {
        return new Telemetry(category, name, phase, newLevel, message, sessionId, toolCallId, usage, attributes);
    }

    public Telemetry withAttributes(Map<String, Object> extra) {
        java.util.HashMap<String, Object> merged = new java.util.HashMap<>(attributes);
        merged.putAll(extra);
        return new Telemetry(category, name, phase, level, message, sessionId, toolCallId, usage, merged);
    }
}
