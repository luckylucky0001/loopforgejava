package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.BranchMode;
import java.time.Instant;
import java.util.List;

/**
 * A scheduled task of a project (upstream scheduled tasks and schedule jobs).
 *
 * @param kind {@code sop} starts {@code workflowId}; {@code assistant} runs {@code prompt} as an
 *     assistant turn in the project (upstream AI scheduled task); {@code dispatch} starts the SOP of
 *     every requirement in {@code dispatchStatuses}, at most {@code maxDispatch} per firing (upstream
 *     trigger configuration)
 * @param cron five-field ({@code m h dom mon dow}) or six-field (with seconds) cron expression
 * @param zone IANA time zone the expression is evaluated in, UTC by default
 * @param skipIfRunning do not start a run while the previous one is still active
 * @param runAs whose identity and credentials the task works with: the account that last saved it,
 *     set by the server
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
        Instant updatedAt,
        String kind,
        String prompt,
        List<String> dispatchStatuses,
        int maxDispatch,
        String runAs) {

    public static final String SOP = "sop";
    public static final String ASSISTANT = "assistant";
    public static final String DISPATCH = "dispatch";

    /** One repository of the scheduled run. */
    public record Repository(String repositoryId, String baseBranch, String workBranch, BranchMode mode) {}

    public Schedule {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        zone = zone == null || zone.isBlank() ? "UTC" : zone.strip();
        kind = kind == null || kind.isBlank() ? SOP : kind.strip();
        dispatchStatuses = dispatchStatuses == null || dispatchStatuses.isEmpty()
                ? (DISPATCH.equals(kind) ? List.of("todo") : List.of())
                : List.copyOf(dispatchStatuses);
        maxDispatch = maxDispatch <= 0 ? 5 : maxDispatch;
    }

    /** The account the task works for: the last saver, else its creator. */
    public String responsible() {
        return runAs != null && !runAs.isBlank() ? runAs : createdBy;
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
                updatedAt,
                kind,
                prompt,
                dispatchStatuses,
                maxDispatch,
                runAs);
    }
}
