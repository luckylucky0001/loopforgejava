package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.PullRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A project requirement (upstream platform issue of kind {@code requirement}). Starting it runs an
 * SOP on its repositories with work branch {@code feature/requirement-<number>-<time>}; the
 * requirement follows the run: in progress, in review once delivered, done when its PRs merged,
 * blocked when the run failed.
 *
 * @param status backlog, todo, in_progress, in_review, done, closed, blocked, cancelled
 * @param priority low, medium, high, urgent
 */
public record Requirement(
        String id,
        String projectId,
        int number,
        String title,
        String description,
        String status,
        String priority,
        String assignee,
        String workflowId,
        List<String> repositoryIds,
        String baseBranch,
        List<String> runIds,
        String latestRunId,
        List<PullRequest> deliveries,
        String source,
        ExternalRef external,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        String playbookId,
        List<SopBinding> sopBindings) {

    public static final Set<String> STATUSES =
            Set.of("backlog", "todo", "in_progress", "in_review", "done", "closed", "blocked", "cancelled");
    public static final Set<String> PRIORITIES = Set.of("low", "medium", "high", "urgent");

    public Requirement {
        status = status == null || status.isBlank() ? "backlog" : status;
        priority = priority == null || priority.isBlank() ? "medium" : priority;
        repositoryIds = repositoryIds == null ? List.of() : List.copyOf(repositoryIds);
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
        // null: the chain is the single SOP in workflowId (requirements before multi-lane)
        sopBindings = sopBindings == null ? null : List.copyOf(sopBindings);
        source = source == null || source.isBlank() ? (external == null ? "local" : external.source()) : source;
    }

    /** The SOP chain: the explicit bindings, or one step from {@code workflowId} (upstream). */
    public List<SopBinding> effectiveBindings() {
        if (sopBindings != null) {
            return sopBindings;
        }
        return workflowId == null || workflowId.isBlank()
                ? List.of()
                : List.of(new SopBinding("sop-default", workflowId, 1, assignee, List.of(), List.of()));
    }

    /** A copy with another SOP chain (the first step becomes the default SOP). */
    Requirement withChain(String newPlaybookId, List<SopBinding> bindings) {
        String first = bindings == null || bindings.isEmpty()
                ? workflowId
                : bindings.getFirst().workflowId();
        return new Requirement(
                id,
                projectId,
                number,
                title,
                description,
                status,
                priority,
                assignee,
                first,
                repositoryIds,
                baseBranch,
                runIds,
                latestRunId,
                deliveries,
                source,
                external,
                createdBy,
                createdAt,
                updatedAt,
                newPlaybookId,
                bindings);
    }

    Requirement with(String newStatus, List<String> newRunIds, String latest, List<PullRequest> newDeliveries) {
        return new Requirement(
                id,
                projectId,
                number,
                title,
                description,
                newStatus,
                priority,
                assignee,
                workflowId,
                repositoryIds,
                baseBranch,
                newRunIds,
                latest,
                newDeliveries,
                source,
                external,
                createdBy,
                createdAt,
                updatedAt,
                playbookId,
                sopBindings);
    }

    public Requirement withExternal(ExternalRef ref) {
        return new Requirement(
                id,
                projectId,
                number,
                title,
                description,
                status,
                priority,
                assignee,
                workflowId,
                repositoryIds,
                baseBranch,
                runIds,
                latestRunId,
                deliveries,
                ref == null ? source : ref.source(),
                ref,
                createdBy,
                createdAt,
                updatedAt,
                playbookId,
                sopBindings);
    }

    /** Tracker content refreshed; local lifecycle, assignment and SOP stay as they are (upstream). */
    public Requirement synced(String newTitle, String newDescription, ExternalRef ref) {
        return new Requirement(
                id,
                projectId,
                number,
                newTitle,
                newDescription,
                status,
                priority,
                assignee,
                workflowId,
                repositoryIds,
                baseBranch,
                runIds,
                latestRunId,
                deliveries,
                source,
                ref,
                createdBy,
                createdAt,
                updatedAt,
                playbookId,
                sopBindings);
    }

    /** The description with a clarification conclusion, and the status it moves to. */
    Requirement clarified(String newDescription, String newStatus) {
        return new Requirement(
                id,
                projectId,
                number,
                title,
                newDescription,
                newStatus,
                priority,
                assignee,
                workflowId,
                repositoryIds,
                baseBranch,
                runIds,
                latestRunId,
                deliveries,
                source,
                external,
                createdBy,
                createdAt,
                updatedAt,
                playbookId,
                sopBindings);
    }

    Requirement withStatus(String newStatus) {
        return with(newStatus, runIds, latestRunId, deliveries);
    }
}
