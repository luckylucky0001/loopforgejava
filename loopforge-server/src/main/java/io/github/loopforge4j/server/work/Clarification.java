package io.github.loopforge4j.server.work;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * AI clarification of a requirement (upstream issue {@code clarification}): rounds in which an
 * analyst agent reads the requirement and its repositories and either asks the few questions that
 * block starting work or concludes. The conclusion is written into the requirement's description
 * (and, for requirements synced from a tracker, as a comment on the external item). Clarification is
 * optional: it never blocks starting an SOP.
 *
 * @param status {@code new} (no finished round yet), {@code analyzing} (a round is running), {@code
 *     cancelling}, {@code in_progress} (questions are open) or {@code ready} (concluded)
 * @param summary the current understanding, the conclusion once ready
 * @param history the rounds: user answers and the analyst's results
 * @param pending the answer the running round is about
 * @param cliSessionId native CLI session the next round continues
 * @param error why the last round failed, cleared by the next successful round
 * @param writeBack {@code pending}, {@code succeeded}, {@code failed} or {@code skipped} (not synced
 *     from a tracker, or write-back off)
 * @param writeBackFingerprint the conclusion last written back, so the same text is not posted twice
 */
public record Clarification(
        String requirementId,
        String status,
        String summary,
        List<Question> questions,
        List<Message> history,
        String pending,
        String sandboxProfileId,
        String executorId,
        String cliSessionId,
        String error,
        List<String> activity,
        long totalTokens,
        double costUsd,
        String writeBack,
        String writeBackError,
        String writeBackFingerprint,
        String startedBy,
        Instant roundStartedAt,
        Instant createdAt,
        Instant updatedAt) {

    public static final Set<String> IDLE = Set.of("new", "in_progress", "ready");

    public Clarification {
        questions = questions == null ? List.of() : List.copyOf(questions);
        history = history == null ? List.of() : List.copyOf(history);
        activity = activity == null ? List.of() : List.copyOf(activity);
        status = status == null || status.isBlank() ? "new" : status;
    }

    /** One message of the clarification dialogue: {@code user} (with author) or {@code assistant}. */
    public record Message(String role, String author, String content, Instant at) {}

    /**
     * A question of the analyst: a choice ({@code options}, at least two) or free text.
     *
     * @param multiSelect several options may be chosen
     * @param freeText answered in the person's words
     */
    public record Question(String id, String question, List<String> options, boolean multiSelect, boolean freeText) {
        public Question {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    public boolean running() {
        return "analyzing".equals(status) || "cancelling".equals(status);
    }

    public int analystRounds() {
        return (int) history.stream().filter(m -> "assistant".equals(m.role())).count();
    }

    Builder toBuilder() {
        return new Builder(this);
    }

    /** Copy-and-change helper for the many fields of a clarification. */
    static final class Builder {
        String requirementId;
        String status;
        String summary;
        List<Question> questions;
        List<Message> history;
        String pending;
        String sandboxProfileId;
        String executorId;
        String cliSessionId;
        String error;
        List<String> activity;
        long totalTokens;
        double costUsd;
        String writeBack;
        String writeBackError;
        String writeBackFingerprint;
        String startedBy;
        Instant roundStartedAt;
        Instant createdAt;
        Instant updatedAt;

        Builder(Clarification c) {
            requirementId = c.requirementId;
            status = c.status;
            summary = c.summary;
            questions = c.questions;
            history = c.history;
            pending = c.pending;
            sandboxProfileId = c.sandboxProfileId;
            executorId = c.executorId;
            cliSessionId = c.cliSessionId;
            error = c.error;
            activity = c.activity;
            totalTokens = c.totalTokens;
            costUsd = c.costUsd;
            writeBack = c.writeBack;
            writeBackError = c.writeBackError;
            writeBackFingerprint = c.writeBackFingerprint;
            startedBy = c.startedBy;
            roundStartedAt = c.roundStartedAt;
            createdAt = c.createdAt;
            updatedAt = c.updatedAt;
        }

        Clarification build() {
            return new Clarification(
                    requirementId,
                    status,
                    summary,
                    questions,
                    history,
                    pending,
                    sandboxProfileId,
                    executorId,
                    cliSessionId,
                    error,
                    activity,
                    totalTokens,
                    costUsd,
                    writeBack,
                    writeBackError,
                    writeBackFingerprint,
                    startedBy,
                    roundStartedAt,
                    createdAt,
                    updatedAt);
        }
    }
}
