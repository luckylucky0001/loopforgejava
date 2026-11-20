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
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static final Set<String> STATUSES =
            Set.of("backlog", "todo", "in_progress", "in_review", "done", "closed", "blocked", "cancelled");
    public static final Set<String> PRIORITIES = Set.of("low", "medium", "high", "urgent");

    public Requirement {
        status = status == null || status.isBlank() ? "backlog" : status;
        priority = priority == null || priority.isBlank() ? "medium" : priority;
        repositoryIds = repositoryIds == null ? List.of() : List.copyOf(repositoryIds);
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
        deliveries = deliveries == null ? List.of() : List.copyOf(deliveries);
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
                createdBy,
                createdAt,
                updatedAt);
    }

    Requirement withStatus(String newStatus) {
        return with(newStatus, runIds, latestRunId, deliveries);
    }
}
