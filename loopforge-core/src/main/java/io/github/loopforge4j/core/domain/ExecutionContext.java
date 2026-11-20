package io.github.loopforge4j.core.domain;

/**
 * Who and what started a run.
 *
 * @param parentRunId set on child runs created by an {@code sop} node
 * @param parentNodeId the {@code sop} node of the parent
 * @param workspaceRunId run that owns the shared workspace (the root of a child chain)
 * @param runnerId execution machine that runs it (upstream personal / pool runner); {@code null}
 *     for the server's own engine instances
 */
public record ExecutionContext(
        String projectId,
        String triggeredBy,
        String parentRunId,
        String parentNodeId,
        String workspaceRunId,
        String runnerId) {

    public static final ExecutionContext EMPTY = new ExecutionContext(null, null, null, null, null);

    public ExecutionContext {
        runnerId = runnerId == null || runnerId.isBlank() ? null : runnerId.strip();
    }

    public ExecutionContext(
            String projectId, String triggeredBy, String parentRunId, String parentNodeId, String workspaceRunId) {
        this(projectId, triggeredBy, parentRunId, parentNodeId, workspaceRunId, null);
    }

    /** The same context, executed by {@code runner}. */
    public ExecutionContext onRunner(String runner) {
        return new ExecutionContext(projectId, triggeredBy, parentRunId, parentNodeId, workspaceRunId, runner);
    }

    public static ExecutionContext triggeredBy(String actor) {
        return new ExecutionContext(null, actor, null, null, null);
    }

    public boolean childRun() {
        return parentRunId != null && !parentRunId.isBlank();
    }

    /** Context for a child run started by {@code parentNodeId} of {@code parent}. */
    public static ExecutionContext childOf(Run parent, String parentNodeId) {
        ExecutionContext p = parent.context();
        // a child SOP runs where its parent runs: it shares the parent's workspace
        return new ExecutionContext(
                p.projectId(), p.triggeredBy(), parent.id(), parentNodeId, parent.workspaceOwnerId(), p.runnerId());
    }
}
