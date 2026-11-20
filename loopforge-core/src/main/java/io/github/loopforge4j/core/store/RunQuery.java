package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.RunStatus;
import java.util.Set;

/** Filter for run listings; {@code null}/empty fields do not filter. */
public record RunQuery(Set<RunStatus> statuses, String workflowId, String parentRunId, int limit) {

    public static final int DEFAULT_LIMIT = 50;

    public RunQuery {
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 500);
    }

    public static RunQuery all() {
        return new RunQuery(Set.of(), null, null, DEFAULT_LIMIT);
    }

    public static RunQuery withStatus(Set<RunStatus> statuses) {
        return new RunQuery(statuses, null, null, 500);
    }
}
