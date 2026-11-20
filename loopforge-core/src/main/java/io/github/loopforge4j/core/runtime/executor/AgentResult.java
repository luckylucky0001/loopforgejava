package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.TokenUsage;

/**
 * Normalized outcome of one agent call.
 *
 * @param output visible text: the terminal result, else the last assistant message
 * @param terminal the CLI emitted a terminal result frame
 * @param failed the CLI reported an error result
 * @param maxTurns the CLI stopped because it ran out of turns
 */
public record AgentResult(
        String output,
        String sessionId,
        String model,
        TokenUsage usage,
        int toolCalls,
        boolean terminal,
        boolean failed,
        boolean maxTurns,
        int exitCode) {

    public AgentResult {
        output = output == null ? "" : output;
        usage = usage == null ? TokenUsage.ZERO : usage;
    }
}
