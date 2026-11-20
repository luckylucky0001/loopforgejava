package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One execution of a workflow: the frozen workflow snapshot plus per-node state.
 *
 * <p>Concurrency: a run is persisted with a {@link #revision()}; stores reject stale writes. Within an
 * engine instance, {@link #generation()} fences late results of a superseded scheduler (upstream
 * "execution generation").
 */
@JsonAutoDetect(
        fieldVisibility = Visibility.ANY,
        getterVisibility = Visibility.NONE,
        isGetterVisibility = Visibility.NONE,
        setterVisibility = Visibility.NONE)
public final class Run {

    private String id;
    private String workflowId;
    private WorkflowDefinition workflowSnapshot;
    private RunStatus status;
    private String input;
    private ExecutionContext context = ExecutionContext.EMPTY;
    private Map<String, NodeRun> nodes = new LinkedHashMap<>();
    private boolean cancelRequested;
    private boolean pauseRequested;
    private String humanContext;
    private TokenUsage usage = TokenUsage.ZERO;
    private int toolCalls;
    private String workspaceRoot;
    private java.util.List<RepositoryContext> repositories = new java.util.ArrayList<>();
    private boolean repositoriesPrepared;
    private java.util.List<PullRequest> deliveries = new java.util.ArrayList<>();
    private boolean workspaceReleased;
    private String ownerInstanceId;
    private int generation;
    private Instant createdAt;
    private Instant updatedAt;
    private long revision;

    Run() {
        // for Jackson
    }

    /** New queued run with one pending node state per workflow node. */
    public static Run create(
            String id, WorkflowDefinition snapshot, String input, ExecutionContext context, Instant now) {
        Run run = new Run();
        run.id = Objects.requireNonNull(id, "id");
        run.workflowSnapshot = Objects.requireNonNull(snapshot, "snapshot");
        run.workflowId = snapshot.id();
        run.input = input == null ? "" : input;
        run.context = context == null ? ExecutionContext.EMPTY : context;
        run.status = RunStatus.QUEUED;
        run.createdAt = now;
        run.updatedAt = now;
        for (WorkflowNode node : snapshot.nodes()) {
            run.nodes.put(node.id(), NodeRun.pending(node.id()));
        }
        return run;
    }

    public Optional<NodeRun> node(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public NodeRun requireNode(String nodeId) {
        NodeRun node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("node '" + nodeId + "' is not part of run " + id);
        }
        return node;
    }

    public Collection<NodeRun> nodeRuns() {
        return nodes.values();
    }

    /** True while any node holds an executor. */
    public boolean hasActiveNode() {
        return nodes.values().stream().anyMatch(n -> n.status().active());
    }

    public void addUsage(TokenUsage delta, int tools) {
        usage = usage == null ? delta : usage.plus(delta);
        toolCalls += tools;
    }

    /** Appends an operator note so later prompts can prefer it. */
    public void appendHumanContext(String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        humanContext =
                humanContext == null || humanContext.isBlank() ? note.trim() : humanContext + "\n\n" + note.trim();
    }

    public String id() {
        return id;
    }

    public String workflowId() {
        return workflowId;
    }

    public WorkflowDefinition workflow() {
        return workflowSnapshot;
    }

    public RunStatus status() {
        return status;
    }

    public void status(RunStatus value) {
        this.status = value;
    }

    public String input() {
        return input;
    }

    public ExecutionContext context() {
        return context;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public void cancelRequested(boolean value) {
        this.cancelRequested = value;
    }

    public boolean pauseRequested() {
        return pauseRequested;
    }

    public void pauseRequested(boolean value) {
        this.pauseRequested = value;
    }

    public String humanContext() {
        return humanContext;
    }

    public TokenUsage usage() {
        return usage;
    }

    public int toolCalls() {
        return toolCalls;
    }

    public String workspaceRoot() {
        return workspaceRoot;
    }

    public void workspaceRoot(String value) {
        this.workspaceRoot = value;
    }

    public java.util.List<RepositoryContext> repositories() {
        return repositories == null ? java.util.List.of() : java.util.List.copyOf(repositories);
    }

    public void repositories(java.util.List<RepositoryContext> value) {
        this.repositories = new java.util.ArrayList<>(value);
    }

    public boolean repositoriesPrepared() {
        return repositoriesPrepared;
    }

    public void repositoriesPrepared(boolean value) {
        this.repositoriesPrepared = value;
    }

    /** Pull requests that deliver this run's work branches (refreshed from the hosting API). */
    public java.util.List<PullRequest> deliveries() {
        return deliveries == null ? java.util.List.of() : java.util.List.copyOf(deliveries);
    }

    public void deliveries(java.util.List<PullRequest> value) {
        this.deliveries = new java.util.ArrayList<>(value);
    }

    public boolean workspaceReleased() {
        return workspaceReleased;
    }

    public void workspaceReleased(boolean value) {
        this.workspaceReleased = value;
    }

    /** Run whose workspace this run uses: child SOP runs work in their root run's checkout. */
    public String workspaceOwnerId() {
        return context.workspaceRunId() == null || context.workspaceRunId().isBlank() ? id : context.workspaceRunId();
    }

    public String ownerInstanceId() {
        return ownerInstanceId;
    }

    public void ownerInstanceId(String value) {
        this.ownerInstanceId = value;
    }

    public int generation() {
        return generation;
    }

    public void generation(int value) {
        this.generation = value;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void updatedAt(Instant value) {
        this.updatedAt = value;
    }

    public long revision() {
        return revision;
    }

    public void revision(long value) {
        this.revision = value;
    }

    @Override
    public String toString() {
        return "Run[" + id + " " + status + " rev=" + revision + " nodes=" + nodes.values() + "]";
    }
}
