package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import java.util.Set;

/**
 * Filter for run listings; {@code null}/empty fields do not filter.
 *
 * @param projectIds when not {@code null}, only runs of these projects, plus runs started by {@code
 *     actor}, are listed (visibility of a non-admin account)
 * @param actor see {@code projectIds}
 */
public record RunQuery(
        Set<RunStatus> statuses,
        String workflowId,
        String parentRunId,
        int limit,
        Set<String> projectIds,
        String actor) {

    public static final int DEFAULT_LIMIT = 50;

    public RunQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
        projectIds = projectIds == null ? null : Set.copyOf(projectIds);
    }

    public RunQuery(Set<RunStatus> statuses, String workflowId, String parentRunId, int limit) {
        this(statuses, workflowId, parentRunId, limit, null, null);
    }

    public static RunQuery all() {
        return new RunQuery(Set.of(), null, null, DEFAULT_LIMIT);
    }

    public static RunQuery withStatus(Set<RunStatus> statuses) {
        return new RunQuery(statuses, null, null, 500);
    }

    /** Restricts the listing to what an account may see. */
    public RunQuery visibleTo(Set<String> projects, String actorName) {
        return new RunQuery(statuses, workflowId, parentRunId, limit, projects, actorName);
    }

    /** In-memory evaluation, shared by stores that filter in Java. */
    public boolean matches(Run run) {
        if (!statuses.isEmpty() && !statuses.contains(run.status())) {
            return false;
        }
        if (workflowId != null && !workflowId.equals(run.workflowId())) {
            return false;
        }
        if (parentRunId != null && !parentRunId.equals(run.context().parentRunId())) {
            return false;
        }
        if (projectIds != null) {
            String project = run.context().projectId();
            boolean own = actor != null && actor.equals(run.context().triggeredBy());
            return own || (project != null && projectIds.contains(project));
        }
        return true;
    }
}
