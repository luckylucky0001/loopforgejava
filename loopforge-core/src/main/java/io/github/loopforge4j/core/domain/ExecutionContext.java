package io.github.loopforge4j.core.domain;

/**
 * Who and what started a run.
 *
 * @param parentRunId set on child runs created by an {@code sop} node
 * @param parentNodeId the {@code sop} node of the parent
 * @param workspaceRunId run that owns the shared workspace (the root of a child chain)
 */
public record ExecutionContext(
        String projectId, String triggeredBy, String parentRunId, String parentNodeId, String workspaceRunId) {

    public static final ExecutionContext EMPTY = new ExecutionContext(null, null, null, null, null);

    public static ExecutionContext triggeredBy(String actor) {
        return new ExecutionContext(null, actor, null, null, null);
    }

    public boolean childRun() {
        return parentRunId != null && !parentRunId.isBlank();
    }

    /** Context for a child run started by {@code parentNodeId} of {@code parent}. */
    public static ExecutionContext childOf(Run parent, String parentNodeId) {
        ExecutionContext p = parent.context();
        return new ExecutionContext(
                p.projectId(), p.triggeredBy(), parent.id(), parentNodeId, parent.workspaceOwnerId());
    }
}
