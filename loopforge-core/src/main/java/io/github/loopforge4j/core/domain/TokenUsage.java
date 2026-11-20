package io.github.loopforge4j.core.domain;

/**
 * Token and cost accounting reported by an executor. {@code costStatus} distinguishes a reported zero
 * cost from an unknown one ({@code reported}, {@code estimated} or {@code unknown}).
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        long reasoningTokens,
        long totalTokens,
        double costUsd,
        String costStatus) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0, 0, 0, 0, null);

    public TokenUsage {
        if (totalTokens == 0) {
            totalTokens = inputTokens + outputTokens;
        }
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        String status = costStatus == null ? other.costStatus : costStatus;
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheReadTokens + other.cacheReadTokens,
                cacheWriteTokens + other.cacheWriteTokens,
                reasoningTokens + other.reasoningTokens,
                totalTokens + other.totalTokens,
                costUsd + other.costUsd,
                status);
    }
}
