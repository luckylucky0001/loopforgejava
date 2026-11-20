package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Append-only audit and telemetry record of a run. Lifecycle events ({@code node.started}, {@code
 * gate.approved}, ...) use category {@code lifecycle}; executor telemetry keeps the portable
 * categories of the upstream observability spec ({@code tool}, {@code output}, {@code usage}, ...).
 *
 * @param sequence store-assigned, strictly increasing per store; {@code 0} until persisted
 */
public record RunEvent(
        long sequence,
        String runId,
        String nodeId,
        String category,
        String type,
        String level,
        String message,
        Map<String, Object> attributes,
        Instant timestamp) {

    public static final String LIFECYCLE = "lifecycle";

    public RunEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        category = category == null ? LIFECYCLE : category;
    }

    public static RunEvent lifecycle(String runId, String nodeId, String type, String message, Instant at) {
        return new RunEvent(0, runId, nodeId, LIFECYCLE, type, "info", message, Map.of(), at);
    }

    public RunEvent withSequence(long value) {
        return new RunEvent(value, runId, nodeId, category, type, level, message, attributes, timestamp);
    }
}
