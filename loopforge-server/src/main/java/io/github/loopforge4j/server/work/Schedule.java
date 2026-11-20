package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.BranchMode;
import java.time.Instant;
import java.util.List;

/**
 * A scheduled SOP run of a project (upstream scheduled tasks).
 *
 * @param cron five-field ({@code m h dom mon dow}) or six-field (with seconds) cron expression
 * @param zone IANA time zone the expression is evaluated in, UTC by default
 * @param skipIfRunning do not start a run while the previous one is still active
 */
public record Schedule(
        String id,
        String projectId,
        String name,
        boolean enabled,
        String cron,
        String zone,
        String workflowId,
        String input,
        List<Repository> repositories,
        boolean skipIfRunning,
        Instant nextRunAt,
        Instant lastRunAt,
        String lastRunId,
        String lastError,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    /** One repository of the scheduled run. */
    public record Repository(String repositoryId, String baseBranch, String workBranch, BranchMode mode) {}

    public Schedule {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        zone = zone == null || zone.isBlank() ? "UTC" : zone.strip();
    }

    Schedule fired(Instant at, Instant next, String runId, String error) {
        return new Schedule(
                id,
                projectId,
                name,
                enabled,
                cron,
                zone,
                workflowId,
                input,
                repositories,
                skipIfRunning,
                next,
                at,
                runId == null ? lastRunId : runId,
                error,
                createdBy,
                createdAt,
                updatedAt);
    }
}
