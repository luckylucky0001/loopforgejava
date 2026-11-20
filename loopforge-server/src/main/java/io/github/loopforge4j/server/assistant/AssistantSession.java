package io.github.loopforge4j.server.assistant;

import java.time.Instant;

/**
 * A conversation with the platform assistant (upstream Main Bot session): personal ({@code
 * projectId == null}, owner only) or in a project (every project member). It keeps one sandbox
 * workspace and one native CLI session across turns.
 *
 * @param status {@code idle}, {@code generating} or {@code cancelling}
 * @param cliSessionId the CLI session the next turn continues
 * @param source {@code chat} (started by a person) or {@code scheduled} (an AI scheduled task)
 */
public record AssistantSession(
        String id,
        String projectId,
        String ownerId,
        String ownerName,
        String title,
        String sandboxProfileId,
        String executorId,
        String cliSessionId,
        String status,
        String lastError,
        long totalTokens,
        double costUsd,
        Instant turnStartedAt,
        Instant createdAt,
        Instant updatedAt,
        String source) {

    public static final String CHAT = "chat";
    public static final String SCHEDULED = "scheduled";

    public AssistantSession {
        // conversations stored before sources existed: scheduled ones carried a title prefix
        source = source != null ? source : title != null && title.startsWith("定时") ? SCHEDULED : CHAT;
    }

    AssistantSession with(
            String newStatus, String newCliSession, String error, long tokens, double cost, Instant started) {
        return new AssistantSession(
                id,
                projectId,
                ownerId,
                ownerName,
                title,
                sandboxProfileId,
                executorId,
                newCliSession,
                newStatus,
                error,
                tokens,
                cost,
                started,
                createdAt,
                updatedAt,
                source);
    }
}
