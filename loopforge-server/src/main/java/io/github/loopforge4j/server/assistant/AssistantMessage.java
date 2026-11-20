package io.github.loopforge4j.server.assistant;

import java.time.Instant;
import java.util.List;

/**
 * One message of a conversation.
 *
 * @param role {@code user}, {@code assistant} or {@code error}
 * @param author who spoke (the account for user messages)
 * @param activity what the assistant did meanwhile (tool calls, commands), newest last
 */
public record AssistantMessage(
        long seq,
        String sessionId,
        String role,
        String author,
        String content,
        String model,
        long tokens,
        double costUsd,
        List<String> activity,
        Instant createdAt) {

    public AssistantMessage {
        activity = activity == null ? List.of() : List.copyOf(activity);
    }
}
