package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.delivery.Deliveries;
import io.github.loopforge4j.core.delivery.DeliveryException;
import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.BranchMode;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.RepositorySelection;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.git.GitCredentialProvider;
import io.github.loopforge4j.core.runtime.git.GitEnvironment;
import io.github.loopforge4j.core.runtime.git.GitException;
import io.github.loopforge4j.core.runtime.git.GitWorkspace;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import io.github.loopforge4j.core.runtime.sandbox.SandboxProvider;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.core.store.RunChange;
import io.github.loopforge4j.core.store.RunMutator;
import io.github.loopforge4j.core.store.RunQuery;
import io.github.loopforge4j.core.store.RunStore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the SOP engine. Thread-safe.
 *
 * <p>Every state change goes through {@link RunStore#update}, so decisions are made on fresh state and
 * committed atomically with their events and artifacts. One {@link RunScheduler} per run executes
 * ready nodes on virtual threads; operator controls change the stored state first and then wake or
 * start the scheduler.
 */
public final class RunEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunEngine.class);
    static final int MAX_HUMAN_CONTEXT = 8000;
    static final String PIPELINE_FAILED = "流水线执行失败";
    static final int MAX_SCHEDULER_RESTARTS = 10;

    private final RunStore runs;
    private final CatalogStore catalog;
    private final ExecutorRegistry executors;
    private final List<SandboxProvider> sandboxes;
    private final EngineSettings settings;
    private final Clock clock;
    private final List<RunListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    /** Guards {@link #schedulers}; a ReentrantLock so blocked virtual threads unmount (no carrier pinning). */
    private final ReentrantLock schedulerLock = new ReentrantLock();

    private final Map<String, Integer> schedulerCrashes = new ConcurrentHashMap<>();
    private final Map<String, RunScheduler> schedulers = new HashMap<>();
    private final NodeRunner nodeRunner;
    private final GitCredentialProvider gitCredentials;
    private final LeaseKeeper leases;
    private final AdmissionPolicy admission;
    private final io.github.loopforge4j.core.pricing.TokenPricing pricing;
    private final TelemetryBuffer telemetry;
    private final java.util.function.UnaryOperator<String> mcpSecrets;
    private final io.github.loopforge4j.core.mcp.PlatformMcp platformMcp;
    /** The execution machine this engine is, {@code null} for server instances. */
    private final String runnerId;

    private final java.nio.file.Path gateMcpJava;
    private final DeliveryProvider delivery;
    /** Serializes repository checkout per shared workspace (parallel first nodes, parent + child). */
    private final ReentrantLock[] checkoutLocks = new ReentrantLock[32];

    private volatile boolean closed;

    private RunEngine(Builder builder) {
        this.runs = Objects.requireNonNull(builder.runs, "runStore");
        this.catalog = Objects.requireNonNull(builder.catalog, "catalogStore");
        this.executors = Objects.requireNonNull(builder.executors, "executors");
        this.sandboxes = List.copyOf(builder.sandboxes);
        this.settings = builder.settings == null ? EngineSettings.defaults() : builder.settings;
        this.clock = builder.clock == null ? Clock.systemUTC() : builder.clock;
        this.nodeRunner = new NodeRunner(this);
        this.gitCredentials = builder.gitCredentials == null ? GitCredentialProvider.NONE : builder.gitCredentials;
        this.mcpSecrets = builder.mcpSecrets == null ? name -> null : builder.mcpSecrets;
        this.platformMcp =
                builder.platformMcp == null ? io.github.loopforge4j.core.mcp.PlatformMcp.NONE : builder.platformMcp;
        this.runnerId = builder.runnerId == null || builder.runnerId.isBlank() ? null : builder.runnerId.strip();
        this.gateMcpJava = builder.gateMcpJava == null
                ? io.github.loopforge4j.core.mcp.GateMcpPackage.currentJava()
                : builder.gateMcpJava;
        this.delivery = builder.delivery == null ? DeliveryProvider.NONE : builder.delivery;
        for (int i = 0; i < checkoutLocks.length; i++) {
            checkoutLocks[i] = new ReentrantLock();
        }
        if (sandboxes.isEmpty()) {
            throw new IllegalArgumentException("at least one sandbox provider is required");
        }
        this.telemetry = new TelemetryBuffer(
                runs,
                builder.telemetryFlushInterval == null ? Duration.ofMillis(200) : builder.telemetryFlushInterval,
                (runId, stored) -> listeners.forEach(l -> safely(() -> l.eventsAppended(runId, stored))));
        this.admission = builder.admission == null ? AdmissionPolicy.UNLIMITED : builder.admission;
        this.pricing =
                builder.pricing == null ? io.github.loopforge4j.core.pricing.TokenPricing.defaults() : builder.pricing;
        this.leases = new LeaseKeeper(
                this,
                builder.instances == null
                        ? new io.github.loopforge4j.core.store.InMemoryInstanceRegistry()
                        : builder.instances);
        this.leases.start(builder.claimOrphans);
    }

    /**
     * Event categories retention may drop: high-volume executor telemetry. Lifecycle, verification,
     * orchestration, artifact, sandbox and usage events form the audit trail and are kept.
     */
    public static final Set<String> TELEMETRY_CATEGORIES =
            Set.of("output", "tool", "process", "hook", "runtime", "script", "reasoning");

    /** Outcome of {@link #applyRetention}. */
    public record RetentionResult(int prunedEvents, int deletedRuns) {}

    /**
     * Applies the retention policy: drops executor telemetry older than {@code telemetryAge} and
     * deletes terminal runs (with events, artifacts and local workspace) not touched for {@code
     * runAge}. A zero or negative age disables that part.
     */
    public RetentionResult applyRetention(Duration telemetryAge, Duration runAge) {
        Instant now = clock.instant();
        int pruned = 0;
        if (telemetryAge != null && telemetryAge.isPositive()) {
            telemetry.flushAll();
            pruned = runs.pruneEvents(now.minus(telemetryAge), TELEMETRY_CATEGORIES);
        }
        int deleted = 0;
        if (runAge != null && runAge.isPositive()) {
            for (String id : runs.terminalRunsBefore(now.minus(runAge), 500)) {
                Run run = runs.find(id).orElse(null);
                if (run == null || !run.status().terminal() || scheduling(id)) {
                    continue;
                }
                if (id.equals(run.workspaceOwnerId()) && !run.workspaceReleased() && !foreignWorkspace(run)) {
                    sandboxProvider(run.workflow().sandboxProfileId()).ifPresent(provider -> {
                        try {
                            provider.release(id);
                        } catch (IOException e) {
                            log.warn("run {}: cannot delete workspace during retention: {}", id, e.toString());
                        }
                    });
                }
                if (runs.delete(id)) {
                    deleted++;
                }
            }
        }
        if (pruned > 0 || deleted > 0) {
            log.info("retention: pruned {} telemetry event(s), deleted {} run(s)", pruned, deleted);
        }
        return new RetentionResult(pruned, deleted);
    }

    /** Whether a new top-level run of {@code projectId} may start now. */
    private boolean admits(String projectId) {
        if (admission.unlimited()) {
            return true;
        }
        List<Run> active = runs.list(RunQuery.withStatus(Set.of(RunStatus.RUNNING))).stream()
                .filter(r -> !r.context().childRun())
                .toList();
        if (admission.maxActiveRuns() > 0 && active.size() >= admission.maxActiveRuns()) {
            return false;
        }
        if (admission.maxActiveRunsPerProject() > 0 && projectId != null) {
            long inProject = active.stream()
                    .filter(r -> projectId.equals(r.context().projectId()))
                    .count();
            return inProject < admission.maxActiveRunsPerProject();
        }
        return true;
    }

    /**
     * Starts queued runs, oldest first, while the admission policy allows (upstream {@code
     * PromoteQueuedRuns}). Called when runs settle and periodically.
     *
     * @return ids of the runs that started
     */
    public List<String> promoteQueuedRuns() {
        if (closed) {
            return List.of();
        }
        List<Run> queued = new ArrayList<>(runs.list(RunQuery.withStatus(Set.of(RunStatus.QUEUED))));
        queued.sort(java.util.Comparator.comparing(Run::createdAt).thenComparing(Run::id));
        List<String> started = new ArrayList<>();
        for (Run run : queued) {
            if (!executes(run) || run.cancelRequested() || !admits(run.context().projectId())) {
                continue;
            }
            boolean[] promoted = {false};
            mutateQuietly(run.id(), c -> {
                promoted[0] = false;
                if (c.run().status() != RunStatus.QUEUED) {
                    return false;
                }
                c.run().status(RunStatus.RUNNING);
                c.event(null, "run.started", "获得执行名额，流水线开始执行");
                promoted[0] = true;
                return true;
            });
            if (promoted[0]) {
                ensureScheduler(run.id());
                started.add(run.id());
            }
        }
        return started;
    }

    /** Number of runs this instance currently executes. */
    public int activeRunCount() {
        schedulerLock.lock();
        try {
            return schedulers.size();
        } finally {
            schedulerLock.unlock();
        }
    }

    /** Snapshot of the schedulers of this instance. */
    Map<String, RunScheduler> localSchedulers() {
        schedulerLock.lock();
        try {
            return Map.copyOf(schedulers);
        } finally {
            schedulerLock.unlock();
        }
    }

    /** Whether the run's lease is held by another live engine instance. */
    public boolean ownedElsewhere(String runId) {
        return runs.find(runId)
                .map(r -> leases.heldElsewhere(r, clock.instant()))
                .orElse(false);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether this engine executes the run: its runner, or the server for unassigned runs. */
    boolean executes(Run run) {
        return java.util.Objects.equals(run.context().runnerId(), runnerId);
    }

    /** The execution machine this engine is, {@code null} for server instances. */
    public String runnerId() {
        return runnerId;
    }

    public void addListener(RunListener listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ start

    /** Starts the published catalog workflow {@code workflowId}. */
    public Run start(String workflowId, String input, ExecutionContext context) {
        return start(workflowId, input, context, List.of());
    }

    /**
     * Starts the published catalog workflow with repositories bound to the run. Every repository is
     * checked out into its own sub-directory before the first node runs.
     */
    public Run start(
            String workflowId, String input, ExecutionContext context, List<RepositorySelection> repositories) {
        WorkflowDefinition workflow = catalog.workflow(workflowId)
                .filter(w -> sameProject(w.projectId(), context))
                .orElseThrow(() -> new RunNotFoundException("workflow '" + workflowId + "' not found"));
        if (workflow.status() != io.github.loopforge4j.core.domain.WorkflowStatus.PUBLISHED) {
            throw new RunStateException("workflow '" + workflowId + "' is "
                    + workflow.status().wire() + "; publish it before starting runs");
        }
        String runId = newRunId();
        return start(runId, workflow, input, context, resolveRepositories(runId, repositories, context), false);
    }

    /** A project resource is usable only by runs of that project; global ones by every run. */
    static boolean sameProject(String resourceProject, ExecutionContext context) {
        if (resourceProject == null || resourceProject.isBlank()) {
            return true;
        }
        return context != null && resourceProject.equals(context.projectId());
    }

    /** Freezes {@code workflow} into a new run and schedules it. */
    public Run start(WorkflowDefinition workflow, String input, ExecutionContext context) {
        return start(newRunId(), workflow, input, context, List.of(), false);
    }

    /** Child SOP run: shares the parent's workspace and its (already checked out) repositories. */
    Run startChild(
            WorkflowDefinition workflow,
            String input,
            ExecutionContext context,
            List<RepositoryContext> repositories,
            boolean prepared) {
        return start(newRunId(), workflow, input, context, repositories, prepared);
    }

    private Run start(
            String runId,
            WorkflowDefinition workflow,
            String input,
            ExecutionContext context,
            List<RepositoryContext> repositories,
            boolean prepared) {
        ensureOpen();
        if (!sameProject(workflow.projectId(), context)) {
            throw new RunNotFoundException("workflow '" + workflow.id() + "' not found");
        }
        WorkflowValidator.validate(workflow);
        boolean remote = context != null && !java.util.Objects.equals(context.runnerId(), runnerId);
        if (!remote && sandboxProvider(workflow.sandboxProfileId()).isEmpty()) {
            throw new WorkflowValidationException(
                    List.of("sandbox profile '" + workflow.sandboxProfileId() + "' is not available"));
        }
        Run run = Run.create(runId, workflow, input, context, clock.instant());
        boolean child = context != null && context.childRun();
        // a run for an execution machine waits until that machine claims it
        boolean admitted = !remote && (child || admits(run.context().projectId()));
        run.status(admitted ? RunStatus.RUNNING : RunStatus.QUEUED);
        run.repositories(repositories);
        run.repositoriesPrepared(prepared || repositories.isEmpty());
        RunEvent first = admitted
                ? RunEvent.lifecycle(run.id(), null, "run.started", child ? "子 SOP 开始执行" : "流水线开始执行", clock.instant())
                : remote
                        ? RunEvent.lifecycle(
                                run.id(), null, "run.queued", "等待执行机器 " + context.runnerId() + " 认领", clock.instant())
                        : RunEvent.lifecycle(run.id(), null, "run.queued", "执行名额已满，排队等待", clock.instant());
        runs.insert(run, List.of(first));
        Run stored = runs.find(run.id()).orElseThrow();
        notifyChanged(stored);
        if (admitted) {
            ensureScheduler(run.id());
        }
        return runs.find(run.id()).orElse(stored);
    }

    /**
     * Resolves repository selections into frozen contexts (port of upstream {@code
     * normalizeRepositories}, {@code AssignRepositoryDirs} and {@code assignWorkBranches}).
     */
    List<RepositoryContext> resolveRepositories(
            String runId, List<RepositorySelection> selections, ExecutionContext context) {
        if (selections == null || selections.isEmpty()) {
            return List.of();
        }
        List<RepositoryContext> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RepositorySelection selection : selections) {
            if (selection.repositoryId() == null || !seen.add(selection.repositoryId())) {
                continue;
            }
            RepositoryDefinition definition = catalog.repository(selection.repositoryId())
                    // another project's repository is invisible: its credentials and pushes are not ours
                    .filter(r -> sameProject(r.projectId(), context))
                    .orElseThrow(
                            () -> new RunNotFoundException("repository '" + selection.repositoryId() + "' not found"));
            RepositoryContext rc = RepositoryContext.of(definition, selection.baseBranch());
            String explicit =
                    selection.workBranch() == null || selection.workBranch().isBlank()
                            ? null
                            : selection.workBranch().strip();
            switch (selection.mode()) {
                case READ_ONLY -> rc = rc.withMode(true, false, false);
                case CONTINUE, NEW -> {
                    if (explicit == null) {
                        throw new RunStateException("repository " + definition.id() + ": branch mode "
                                + selection.mode().wire() + " needs an explicit work branch");
                    }
                    rc = rc.withWorkBranch(explicit)
                            .withMode(
                                    false, selection.mode() == BranchMode.CONTINUE, selection.mode() == BranchMode.NEW);
                }
                case AUTO -> rc = rc.withWorkBranch(explicit);
            }
            if (!GitWorkspace.validBranch(rc.baseBranch())) {
                throw new RunStateException(
                        "repository " + definition.id() + ": invalid base branch '" + rc.baseBranch() + "'");
            }
            if (rc.workBranch() != null
                    && (!GitWorkspace.validBranch(rc.workBranch())
                            || GitWorkspace.sharedBranch(rc.workBranch(), rc.baseBranch()))) {
                throw new RunStateException("repository " + definition.id() + ": work branch '" + rc.workBranch()
                        + "' is invalid or a shared branch (main, master or the base branch)");
            }
            resolved.add(rc);
        }
        // every repository without a branch shares the first repository's work branch, else a generated one
        String shared = resolved.stream()
                .filter(rc -> !rc.readOnly() && !rc.requireExistingWorkBranch() && !rc.createNewWorkBranch())
                .map(RepositoryContext::workBranch)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(defaultWorkBranch(runId));
        Set<String> dirs = new HashSet<>();
        List<RepositoryContext> out = new ArrayList<>();
        for (RepositoryContext rc : resolved) {
            if (!rc.readOnly() && rc.workBranch() == null) {
                rc = rc.withWorkBranch(shared);
            }
            String base = directoryName(rc);
            String dir = base;
            for (int n = 2; !dirs.add(dir); n++) {
                dir = base + "-" + n;
            }
            out.add(rc.withWorkspaceDir(dir));
        }
        return out;
    }

    /** {@code feature/fix-<yyyyMMdd-HHmm>-<run suffix>}: readable, and unique per run (upstream uses minutes only). */
    private String defaultWorkBranch(String runId) {
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(java.time.ZoneOffset.UTC)
                .format(clock.instant());
        String suffix = runId.replace("run-", "").replaceAll("[^a-z0-9]", "");
        return "feature/fix-" + stamp + "-" + suffix.substring(0, Math.min(6, suffix.length()));
    }

    /** Upstream {@code NormalizeRepositoryDirName}: a safe single-level directory per repository. */
    static String directoryName(RepositoryContext rc) {
        String name = rc.name() == null ? "" : rc.name().strip();
        String cleaned = name.replaceAll("[^A-Za-z0-9_-]", "-").replaceAll("^-+|-+$", "");
        if (cleaned.isEmpty()) {
            cleaned = rc.repositoryId().replaceAll("[^A-Za-z0-9_-]", "-");
        }
        return cleaned.isEmpty() ? "repo" : cleaned;
    }

    // --------------------------------------------------------------- queries

    public Optional<Run> find(String runId) {
        return runs.find(runId);
    }

    public Run require(String runId) {
        return runs.find(runId).orElseThrow(() -> new RunNotFoundException("run '" + runId + "' not found"));
    }

    public List<Run> list(RunQuery query) {
        return runs.list(query);
    }

    public List<RunEvent> events(String runId, long afterSequence, int limit) {
        require(runId);
        telemetry.flush(runId);
        return runs.events(runId, afterSequence, limit);
    }

    public List<Artifact> artifacts(String runId) {
        require(runId);
        return runs.artifacts(runId);
    }

    public Optional<Artifact> artifact(String runId, String artifactId) {
        return runs.artifact(runId, artifactId);
    }

    public ExecutorRegistry executors() {
        return executors;
    }

    /** True while a scheduler of this engine executes the run. */
    public boolean scheduling(String runId) {
        schedulerLock.lock();
        try {
            return schedulers.containsKey(runId);
        } finally {
            schedulerLock.unlock();
        }
    }

    // -------------------------------------------------------------- controls

    /** Approves a node waiting at a human gate (upstream {@code Approve}). */
    public Run approve(String runId, String nodeId, String humanContext) {
        Run run = mutate(runId, c -> {
            Run r = c.run();
            requireStatus(r, RunStatus.RUNNING, RunStatus.WAITING);
            requireNotCancelling(r);
            NodeRun nr = r.requireNode(nodeId);
            if (nr.status() != NodeStatus.WAITING_APPROVAL) {
                throw new RunStateException("node " + nodeId + " is not waiting for approval");
            }
            if (nr.paused()) {
                throw new RunStateException("node " + nodeId + " is paused; resume the run instead");
            }
            nr.finish(NodeStatus.SUCCEEDED, c.now());
            nr.activity(null);
            String note = recordHumanContext(c, nodeId, "人工批准继续", humanContext);
            String decision = "# Approval\n\nApproved by a human gate.\n"
                    + (humanContext == null || humanContext.isBlank() ? "" : "\n" + note + "\n");
            c.artifact(ArtifactSupport.create(
                    r.id(), nodeId, nr.attempt(), "decision", "decision.md", "text/markdown", decision, c.now()));
            c.event(nodeId, "gate.approved", "人工门禁已通过");
            r.status(RunStatus.RUNNING);
            return true;
        });
        ensureScheduler(runId);
        return run;
    }

    /**
     * Sends work back to {@code targetId} (default: the node's {@code rejectTo}). The target and all
     * its descendants are reset; the target's rework counter increases and it receives the reason as
     * feedback (upstream {@code Reject}).
     */
    public Run reject(String runId, String nodeId, String targetId, String reason, String humanContext) {
        return reject(runId, nodeId, targetId, reason, humanContext, true);
    }

    Run reject(String runId, String nodeId, String targetId, String reason, String humanContext, boolean operator) {
        Set<String> interrupted = new HashSet<>();
        List<String> staleChildren = new ArrayList<>();
        Run run = mutate(runId, c -> {
            interrupted.clear();
            staleChildren.clear();
            Run r = c.run();
            if (!operator && (r.cancelRequested() || r.status().terminal())) {
                // an automatic rework must never revive a run that is being cancelled or already settled
                return false;
            }
            WorkflowDefinition wf = r.workflow();
            WorkflowNode node =
                    wf.node(nodeId).orElseThrow(() -> new RunStateException("node " + nodeId + " not found"));
            String target = targetId == null || targetId.isBlank() ? node.rejectTo() : targetId;
            if (target == null) {
                throw new RunStateException("reject target is required");
            }
            NodeRun targetRun =
                    r.node(target).orElseThrow(() -> new RunStateException("reject target " + target + " not found"));
            if (operator) {
                requireStatus(r, RunStatus.RUNNING, RunStatus.WAITING, RunStatus.FAILED);
                requireNotCancelling(r);
                NodeStatus status = r.requireNode(nodeId).status();
                if (status != NodeStatus.WAITING_APPROVAL && status != NodeStatus.FAILED) {
                    throw new RunStateException("node " + nodeId + " is not waiting for a decision");
                }
            }
            if (node.maxReworks() > 0 && targetRun.reworkCount() >= node.maxReworks()) {
                throw new RunStateException("rework limit reached");
            }
            Set<String> reset = new LinkedHashSet<>(WorkflowGraph.descendants(wf, target));
            reset.add(target);
            String note = recordHumanContext(c, nodeId, reason, humanContext);
            for (String id : reset) {
                NodeRun nr = r.requireNode(id);
                if (nr.status() == NodeStatus.RUNNING) {
                    interrupted.add(id);
                    nr.activity(null);
                }
                if (nr.childRunId() != null) {
                    staleChildren.add(nr.childRunId());
                }
                nr.resetToPending();
                if (id.equals(target)) {
                    nr.reworkCount(nr.reworkCount() + 1);
                    nr.feedback(note);
                } else {
                    nr.feedback(null);
                }
            }
            c.event(nodeId, "gate.rejected", "打回到 " + target + "：" + (reason == null ? "" : reason));
            if (operator) {
                // an operator decision continues the run, including branches parked by a pause
                releasePause(c, null);
                r.status(RunStatus.RUNNING);
            } else if (!r.pauseRequested()) {
                r.status(RunStatus.RUNNING);
            }
            return true;
        });
        cancelChildren(staleChildren);
        interruptNodes(runId, interrupted);
        ensureScheduler(runId);
        return run;
    }

    /**
     * Reopens a stopped run from {@code nodeId} (default: where it stopped). Succeeded upstream nodes
     * and their artifacts are kept; a node that exhausted its attempts gets exactly one more (upstream
     * {@code Retry}).
     */
    public Run retry(String runId, String nodeId, String reason, String humanContext) {
        List<String> staleChildren = new ArrayList<>();
        Run run = mutate(runId, c -> {
            staleChildren.clear();
            Run r = c.run();
            boolean explicit = nodeId != null && !nodeId.isBlank();
            if (!explicit && r.status() == RunStatus.SUCCEEDED) {
                throw new RunStateException("a succeeded run can only be replayed from an explicit node");
            }
            String id = explicit
                    ? nodeId
                    : WorkflowGraph.resumeNode(r)
                            .map(WorkflowNode::id)
                            .orElseThrow(() -> new RunStateException("retry node not found"));
            WorkflowNode node =
                    r.workflow().node(id).orElseThrow(() -> new RunStateException("retry node is not in this SOP"));
            if (r.status() == RunStatus.RUNNING || r.hasActiveNode()) {
                throw new RunStateException("run is still active");
            }
            requireStatus(r, RunStatus.WAITING, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.SUCCEEDED);
            requireNotCancelling(r);
            if (explicit && !upstreamSucceeded(r, id)) {
                throw new RunStateException("cannot replay node " + id + ": an upstream node has not succeeded");
            }
            if (r.workspaceReleased() && !explicit) {
                throw new RunStateException("run workspace has been released; choose a node to replay");
            }
            NodeRun target = r.requireNode(id);
            if (target.attempt() >= node.attemptBudget()) {
                target.maxAttemptsOverride(target.attempt() + 1);
            }
            String why = reason == null || reason.isBlank()
                    ? (r.status() == RunStatus.SUCCEEDED ? "从指定节点重跑" : "从中断处继续")
                    : reason;
            Set<String> reset = new LinkedHashSet<>(WorkflowGraph.descendants(r.workflow(), id));
            reset.add(id);
            // Parallel branches cancelled together with this one are not failures of their own.
            for (WorkflowNode candidate : r.workflow().nodes()) {
                if (!reset.contains(candidate.id())
                        && r.requireNode(candidate.id()).status() == NodeStatus.CANCELLED) {
                    reset.add(candidate.id());
                    reset.addAll(WorkflowGraph.descendants(r.workflow(), candidate.id()));
                }
            }
            String note = recordHumanContext(c, id, why, node.scriptNode() ? null : humanContext);
            for (String resetId : reset) {
                NodeRun nr = r.requireNode(resetId);
                if (nr.childRunId() != null) {
                    staleChildren.add(nr.childRunId());
                }
                nr.resetToPending();
                nr.activity(null);
                nr.feedback(resetId.equals(id) ? note : null);
            }
            c.event(id, "node.retry_requested", (explicit ? "从指定节点重跑：" : "从中断处继续：") + note);
            releasePause(c, null);
            r.status(RunStatus.RUNNING);
            return true;
        });
        cancelChildren(staleChildren);
        ensureScheduler(runId);
        return run;
    }

    /**
     * Marks a failed (or paused) script node as succeeded without re-running it, so downstream can
     * continue (upstream {@code Pass}).
     */
    public Run pass(String runId, String nodeId, String humanContext) {
        Run run = mutate(runId, c -> {
            Run r = c.run();
            String id = nodeId == null || nodeId.isBlank()
                    ? WorkflowGraph.resumeNode(r).map(WorkflowNode::id).orElse(null)
                    : nodeId;
            if (id == null) {
                throw new RunStateException("pass node not found");
            }
            requireNotCancelling(r);
            NodeRun nr = r.requireNode(id);
            WorkflowNode node = r.workflow().requireNode(id);
            boolean pausedScript = r.status() == RunStatus.WAITING && nr.paused();
            if (!pausedScript && (r.status() != RunStatus.FAILED || nr.status() != NodeStatus.FAILED)) {
                throw new RunStateException("only a failed node on a failed run can be passed");
            }
            if (!node.scriptNode()) {
                throw new RunStateException("only a failed script node can be passed");
            }
            nr.finish(NodeStatus.SUCCEEDED, c.now());
            nr.paused(false);
            nr.activity("人工通过，未重新执行");
            String note = recordHumanContext(c, id, "人工通过该节点，继续下游", humanContext);
            c.artifact(ArtifactSupport.create(
                    r.id(),
                    id,
                    nr.attempt(),
                    "decision",
                    "decision.md",
                    "text/markdown",
                    "# Pass\n\nManually passed by a human "
                            + (pausedScript ? "while the run was paused" : "after the node failed") + ".\n\n" + note
                            + "\n",
                    c.now()));
            c.event(id, "node.passed", "人工通过该节点，继续下游");
            releasePause(c, null);
            r.status(RunStatus.RUNNING);
            return true;
        });
        ensureScheduler(runId);
        return run;
    }

    /** A human decides not to retry a node that ran out of turns; the run fails. */
    public Run declineRetry(String runId, String nodeId) {
        return mutate(runId, c -> {
            Run r = c.run();
            requireNotCancelling(r);
            NodeRun nr = r.requireNode(nodeId);
            if (nr.status() != NodeStatus.WAITING_APPROVAL || nr.paused()) {
                throw new RunStateException("node " + nodeId + " is not waiting for approval");
            }
            if (nr.error() == null || nr.error().isBlank()) {
                nr.error("人工选择不重试");
            }
            nr.finish(NodeStatus.FAILED, c.now());
            nr.activity("人工选择不重试");
            c.event(nodeId, "agent.turns.declined", "人工选择不重试本节点");
            if (!r.hasActiveNode()) {
                r.status(RunStatus.FAILED);
                c.event(null, "run.failed", PIPELINE_FAILED);
            }
            return true;
        });
    }

    /** Interrupts the active nodes and parks them at a human decision point (upstream {@code Pause}). */
    public Run pause(String runId) {
        Run run = mutate(runId, c -> {
            Run r = c.run();
            requireStatus(r, RunStatus.RUNNING);
            requireNotCancelling(r);
            if (r.pauseRequested()) {
                return false;
            }
            r.pauseRequested(true);
            c.event(null, "run.pause_requested", "已请求暂停");
            return true;
        });
        for (String child : liveChildren(run)) {
            try {
                pause(child);
            } catch (RunStateException e) {
                log.debug("child {} not paused: {}", child, e.getMessage());
            }
        }
        if (!interruptScheduler(runId)) {
            settlePaused(runId, run.generation());
        }
        return require(runId);
    }

    /** Continues every node interrupted by a pause (upstream {@code ResumePaused}). */
    public Run resume(String runId, String humanContext) {
        Run before = require(runId);
        for (String child : pausedChildren(before)) {
            resume(child, null);
        }
        Run run = mutate(runId, c -> {
            Run r = c.run();
            if (r.status() != RunStatus.WAITING || !r.pauseRequested()) {
                throw new RunStateException("run is not paused");
            }
            requireNotCancelling(r);
            boolean anyPaused = r.nodeRuns().stream().anyMatch(NodeRun::paused);
            if (!anyPaused
                    && WorkflowGraph.firstWithStatus(r, NodeStatus.WAITING_APPROVAL)
                            .isPresent()) {
                throw new RunStateException("paused node not found");
            }
            String note = releasePause(c, humanContext);
            r.status(RunStatus.RUNNING);
            c.event(null, "run.resumed", note == null || note.isBlank() ? "人工确认直接继续运行" : "人工补充信息后继续运行");
            return true;
        });
        ensureScheduler(runId);
        return run;
    }

    /** Stops the run for good (upstream {@code Cancel}); child SOP runs are cancelled too. */
    public Run cancel(String runId) {
        boolean[] settledNow = {false};
        Run run = mutate(runId, c -> {
            settledNow[0] = false;
            Run r = c.run();
            if (r.status().terminal()) {
                throw new RunStateException("run is not active");
            }
            if (r.cancelRequested()) {
                return false;
            }
            r.cancelRequested(true);
            c.event(null, "run.cancel_requested", "已请求取消");
            if (r.status() != RunStatus.RUNNING) {
                // waiting or queued: nothing executes, so settle in the same transaction
                markCancelled(c);
                settledNow[0] = true;
            }
            return true;
        });
        cancelChildren(liveChildren(run));
        if (settledNow[0]) {
            onTerminal(runId);
        } else if (run.status() == RunStatus.RUNNING
                && !interruptScheduler(runId)
                && !leases.heldElsewhere(run, clock.instant())) {
            // no scheduler of this engine owns the run (it crashed or never started): settle it here,
            // unless a scheduler took over in the meantime (generation changed)
            settleCancelled(runId, run.generation(), true);
        }
        return require(runId);
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Re-attaches runs that were executing when the previous process stopped: in-flight nodes go back
     * to pending (continuing the same attempt and CLI session) and a scheduler is started.
     *
     * @return ids of the recovered runs
     */
    public List<String> recoverInterruptedRuns() {
        List<String> recovered = new ArrayList<>(claimOrphanedRuns());
        recovered.addAll(promoteQueuedRuns());
        return recovered;
    }

    /**
     * Takes over active runs that no live instance owns: an expired lease, a dead owner process on
     * this host, or no owner at all. Runs bound to a workspace on another live host stay there; a
     * run whose workspace host is gone fails when the workspace held a repository checkout (its
     * uncommitted changes went with the host), otherwise it continues here on a fresh workspace.
     *
     * @return ids of the runs this instance now executes
     */
    public List<String> claimOrphanedRuns() {
        List<String> recovered = new ArrayList<>();
        for (Run run : runs.list(RunQuery.withStatus(Set.of(RunStatus.RUNNING)))) {
            if (closed || scheduling(run.id()) || !executes(run)) {
                continue;
            }
            Instant now = clock.instant();
            if (leases.heldElsewhere(run, now)) {
                continue;
            }
            if (foreignWorkspace(run) && leases.hostAlive(run.workspaceHost(), now)) {
                continue;
            }
            if (foreignWorkspace(run) && workspaceLost(run)) {
                failLostWorkspace(run);
                continue;
            }
            // the claim re-checks owner and lease atomically; processes are reclaimed only after winning it
            if (ensureScheduler(run.id(), true)) {
                recovered.add(run.id());
            }
        }
        return recovered;
    }

    /** A node whose thread keeps crashing is failed instead of being relaunched forever. */
    void failCrashedNode(String runId, int generation, WorkflowNode node, int crashes, Throwable error) {
        mutateQuietly(runId, c -> {
            Run r = c.run();
            NodeRun nr = r.requireNode(node.id());
            if (r.generation() != generation
                    || nr.status() == NodeStatus.SUCCEEDED
                    || nr.status() == NodeStatus.FAILED) {
                return false;
            }
            String message = "节点执行反复崩溃（" + crashes + " 次）：" + error.getClass().getSimpleName()
                    + (error instanceof io.github.loopforge4j.core.store.StoreException
                            ? "：" + error.getMessage()
                            : "");
            nr.error(message);
            nr.finish(NodeStatus.FAILED, c.now());
            c.event(null, "node.crashed", message);
            return true;
        });
    }

    /** Kills process groups a crashed predecessor engine left running for this run's workspace. */
    private void reclaimOrphanedProcesses(Run run) {
        sandboxProvider(run.workflow().sandboxProfileId()).ifPresent(provider -> {
            try {
                int killed = provider.reclaim(run.workspaceOwnerId());
                if (killed > 0) {
                    log.warn("run {}: terminated {} process group(s) left by a crashed engine", run.id(), killed);
                    emit(
                            run.id(),
                            null,
                            Telemetry.of("sandbox", "sandbox.reclaimed", "finish", "终止了上一个执行实例遗留的 " + killed + " 个进程组")
                                    .withLevel("warn"));
                }
            } catch (IOException e) {
                log.warn("run {}: cannot reclaim orphaned processes: {}", run.id(), e.toString());
            }
        });
    }

    /** Parks every active run so the next process can resume it, then stops all threads. */
    @Override
    public void close() {
        closed = true;
        List<RunScheduler> active;
        schedulerLock.lock();
        try {
            active = List.copyOf(schedulers.values());
        } finally {
            schedulerLock.unlock();
        }
        active.forEach(RunScheduler::shutdown);
        threads.shutdown();
        try {
            if (!threads.awaitTermination(30, TimeUnit.SECONDS)) {
                threads.shutdownNow();
            }
        } catch (InterruptedException e) {
            threads.shutdownNow();
            Thread.currentThread().interrupt();
        } finally {
            telemetry.close();
            leases.stop();
        }
    }

    // ------------------------------------------------------ scheduler plumbing

    /** Wakes the run's scheduler or starts a new one when the run is running and has none. */
    void ensureScheduler(String runId) {
        ensureScheduler(runId, false);
    }

    /**
     * Claims the run (owner, lease and generation in one conditional update) and starts its
     * scheduler. With {@code recovery}, orphaned processes of the previous owner are killed after the
     * claim succeeded and before any node is relaunched.
     *
     * @return whether this call started a scheduler
     */
    private boolean ensureScheduler(String runId, boolean recovery) {
        if (closed) {
            return false;
        }
        schedulerLock.lock();
        try {
            RunScheduler existing = schedulers.get(runId);
            if (existing != null) {
                existing.wake();
                return false;
            }
            Optional<Run> claimed = runs.update(runId, c -> {
                Run r = c.run();
                if (r.status() != RunStatus.RUNNING || !executes(r)) {
                    // a run of another execution machine is only changed in the store; its owner's
                    // scheduler picks the change up within a tick
                    return false;
                }
                if (leases.heldElsewhere(r, c.now())) {
                    // another live instance executes it and picks up the change within a tick
                    return false;
                }
                if (foreignWorkspace(r)) {
                    if (leases.hostAlive(r.workspaceHost(), c.now()) || workspaceLost(r)) {
                        return false;
                    }
                    // the bound host is gone and held no checkout: continue on a fresh workspace here
                    r.workspaceHost(settings.workspaceHost());
                    r.workspaceRoot(null);
                    r.repositoriesPrepared(false);
                    c.event(null, "run.workspace.rebound", "工作区所在机器已下线，改在 " + settings.workspaceHost() + " 上继续");
                }
                // No scheduler of this engine runs the run, so a node still marked running was left by a
                // crashed or stopped scheduler: continue it instead of failing it as orphaned.
                parkInFlightNodes(r, recovery ? "实例重启，等待恢复" : "调度器重启，等待恢复");
                if (recovery) {
                    String previous = r.ownerInstanceId();
                    c.event(
                            null,
                            "run.recovered",
                            previous == null || previous.equals(settings.instanceId())
                                    ? "执行实例重启后恢复调度"
                                    : "接管了执行实例 " + previous + " 的过期租约");
                }
                r.generation(r.generation() + 1);
                r.ownerInstanceId(settings.instanceId());
                r.leaseUntil(c.now().plus(settings.leaseTtl()));
                return true;
            });
            if (claimed.isEmpty()
                    || claimed.get().status() != RunStatus.RUNNING
                    || !settings.instanceId().equals(claimed.get().ownerInstanceId())) {
                return false;
            }
            claimed.ifPresent(this::notifyChanged);
            if (recovery && !foreignWorkspace(claimed.get())) {
                reclaimOrphanedProcesses(claimed.get());
            }
            RunScheduler scheduler = new RunScheduler(this, runId, claimed.get().generation());
            schedulers.put(runId, scheduler);
            scheduler.start(threads);
            return true;
        } finally {
            schedulerLock.unlock();
        }
    }

    /**
     * Called by a scheduler that settled its run. Returns {@code false} when a wake-up arrived in the
     * meantime, in which case the scheduler must re-check the state instead of exiting.
     */
    boolean tryRetire(RunScheduler scheduler) {
        schedulerLock.lock();
        try {
            if (scheduler.drainWake()) {
                return false;
            }
            schedulers.remove(scheduler.runId(), scheduler);
            schedulerCrashes.remove(scheduler.runId());
        } finally {
            schedulerLock.unlock();
        }
        releaseLease(scheduler.runId(), scheduler.generation());
        if (!admission.unlimited()) {
            // a slot may have opened; promote off the scheduler thread
            Thread.ofVirtual().name("promote-queued").start(() -> {
                try {
                    promoteQueuedRuns();
                } catch (RuntimeException e) {
                    log.warn("promoting queued runs failed: {}", e.toString());
                }
            });
        }
        return true;
    }

    /** A settled run (waiting, paused, terminal) has no owner; any instance may continue it. */
    private void releaseLease(String runId, int generation) {
        try {
            runs.update(runId, c -> {
                Run r = c.run();
                if (r.status() == RunStatus.RUNNING
                        || r.generation() != generation
                        || !settings.instanceId().equals(r.ownerInstanceId())) {
                    return false;
                }
                r.releaseLease();
                return true;
            });
        } catch (RuntimeException e) {
            log.warn("run {}: cannot release lease: {}", runId, e.toString());
        }
    }

    private boolean foreignWorkspace(Run run) {
        return !settings.sharedWorkspaces()
                && run.workspaceHost() != null
                && !run.workspaceHost().equals(settings.workspaceHost())
                && run.workspaceRoot() != null
                && !run.workspaceReleased();
    }

    /** The foreign workspace held a repository checkout whose changes are gone with the host. */
    private static boolean workspaceLost(Run run) {
        return !run.repositories().isEmpty() && run.repositoriesPrepared();
    }

    private void failLostWorkspace(Run run) {
        String message = "工作区所在机器 " + run.workspaceHost() + " 已下线，之前节点的代码改动随之丢失，请从头重跑";
        boolean[] failed = {false};
        mutateQuietly(run.id(), c -> {
            Run r = c.run();
            failed[0] = false;
            if (r.status() != RunStatus.RUNNING && r.status() != RunStatus.QUEUED || leases.heldElsewhere(r, c.now())) {
                return false;
            }
            for (NodeRun nr : r.nodeRuns()) {
                if (nr.status() == NodeStatus.RUNNING) {
                    nr.error(message);
                    nr.finish(NodeStatus.FAILED, c.now());
                }
            }
            r.status(RunStatus.FAILED);
            r.releaseLease();
            c.event(null, "run.workspace_lost", message);
            c.event(null, "run.failed", message);
            failed[0] = true;
            return true;
        });
        if (failed[0]) {
            log.warn("run {} failed: {}", run.id(), message);
        }
    }

    void retire(RunScheduler scheduler) {
        schedulerLock.lock();
        try {
            schedulers.remove(scheduler.runId(), scheduler);
        } finally {
            schedulerLock.unlock();
        }
    }

    /**
     * A scheduler died on an unexpected error (typically a transient store failure). The run is still
     * running in the store, so restart a scheduler with exponential backoff instead of stranding it.
     */
    void schedulerCrashed(RunScheduler scheduler, Throwable error) {
        retire(scheduler);
        String runId = scheduler.runId();
        Thread.ofVirtual().name("reschedule-" + runId).start(() -> {
            while (!closed) {
                int crashes = schedulerCrashes.merge(runId, 1, Integer::sum);
                if (crashes > MAX_SCHEDULER_RESTARTS) {
                    log.error(
                            "run {}: scheduler failed {} times in a row; giving up until restart or operator action",
                            runId,
                            crashes - 1,
                            error);
                    schedulerCrashes.remove(runId);
                    return;
                }
                long delay = Math.min(60_000L, 250L << Math.min(crashes, 8));
                log.warn("run {}: scheduler crashed ({}); restarting in {} ms", runId, error.toString(), delay);
                try {
                    Thread.sleep(delay);
                    ensureScheduler(runId);
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    log.warn("run {}: restarting the scheduler failed: {}", runId, e.toString());
                }
            }
        });
    }

    private boolean interruptScheduler(String runId) {
        schedulerLock.lock();
        try {
            RunScheduler scheduler = schedulers.get(runId);
            if (scheduler == null) {
                return false;
            }
            scheduler.interruptNodes(null);
            return true;
        } finally {
            schedulerLock.unlock();
        }
    }

    private void interruptNodes(String runId, Set<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return;
        }
        schedulerLock.lock();
        try {
            RunScheduler scheduler = schedulers.get(runId);
            if (scheduler != null) {
                scheduler.interruptNodes(nodeIds);
            }
        } finally {
            schedulerLock.unlock();
        }
    }

    boolean closed() {
        return closed;
    }

    // ------------------------------------------------------ settle helpers

    /**
     * Settles a run whose scheduler has nothing in flight and nothing ready (upstream {@code execute}
     * tail + {@code finishAt}). Returns {@code true} when a terminal or waiting state was written.
     */
    boolean settleIdle(String runId, int generation) {
        boolean[] settled = {false};
        RunStatus[] outcome = {null};
        mutateQuietly(runId, c -> {
            settled[0] = false;
            Run r = c.run();
            if (r.status() != RunStatus.RUNNING) {
                settled[0] = true;
                return false;
            }
            if (r.generation() != generation || !WorkflowGraph.readyNodes(r).isEmpty()) {
                return false;
            }
            for (NodeRun nr : r.nodeRuns()) {
                if (nr.status() == NodeStatus.RUNNING) {
                    // No execution owns it any more (crashed node thread): never leave it running.
                    nr.error("节点执行已中断");
                    nr.finish(NodeStatus.FAILED, c.now());
                    c.event(nr.nodeId(), "node.failed", "节点执行已中断");
                }
            }
            if (WorkflowGraph.allSucceeded(r)) {
                r.status(RunStatus.SUCCEEDED);
                c.event(null, "run.succeeded", "流水线执行完成");
            } else if (WorkflowGraph.firstWithStatus(r, NodeStatus.WAITING_APPROVAL)
                    .isPresent()) {
                r.status(RunStatus.WAITING);
                c.event(null, "run.waiting", "等待人工处理");
            } else if (WorkflowGraph.firstWithStatus(r, NodeStatus.FAILED).isPresent()) {
                r.status(RunStatus.FAILED);
                c.event(null, "run.failed", PIPELINE_FAILED);
            } else {
                String name = WorkflowGraph.firstWithStatus(r, NodeStatus.CANCELLED)
                        .map(WorkflowNode::name)
                        .orElse(null);
                r.status(RunStatus.FAILED);
                c.event(null, "run.failed", name == null ? "流水线无法继续" : "节点「" + name + "」已取消，流水线无法继续；请从该节点重跑");
            }
            outcome[0] = r.status();
            settled[0] = true;
            return true;
        });
        if (outcome[0] == RunStatus.SUCCEEDED) {
            onTerminal(runId);
        }
        return settled[0];
    }

    /**
     * Settles a cancel. The scheduler path ({@code force=false}) runs after its nodes drained; the
     * forced path is used when no scheduler of this engine owns the run and only applies while the
     * cancel is still pending and no scheduler took over (same generation).
     */
    void settleCancelled(String runId, int generation, boolean force) {
        boolean[] done = {false};
        mutateQuietly(runId, c -> {
            done[0] = false;
            Run r = c.run();
            if (r.status().terminal() || r.generation() != generation || (force && !r.cancelRequested())) {
                return false;
            }
            markCancelled(c);
            done[0] = true;
            return true;
        });
        if (done[0]) {
            onTerminal(runId);
        }
    }

    private static void markCancelled(RunChange c) {
        Run r = c.run();
        for (NodeRun nr : r.nodeRuns()) {
            if (nr.status() == NodeStatus.RUNNING || nr.status() == NodeStatus.WAITING_APPROVAL) {
                nr.finish(NodeStatus.CANCELLED, c.now());
                nr.error(null);
                nr.paused(false);
                nr.activity("已由用户取消");
                c.event(nr.nodeId(), "node.cancelled", "节点已取消");
            }
        }
        r.cancelRequested(false);
        r.pauseRequested(false);
        r.status(RunStatus.CANCELLED);
        c.event(null, "run.cancelled", "流水线已取消");
    }

    void settlePaused(String runId, int generation) {
        mutateQuietly(runId, c -> {
            Run r = c.run();
            if (r.status() != RunStatus.RUNNING || r.generation() != generation) {
                return false;
            }
            for (NodeRun nr : r.nodeRuns()) {
                if (nr.status() != NodeStatus.RUNNING) {
                    continue;
                }
                boolean script = r.workflow().requireNode(nr.nodeId()).scriptNode();
                nr.finish(NodeStatus.WAITING_APPROVAL, c.now());
                nr.error(null);
                nr.paused(true);
                nr.activity(script ? "已由用户暂停，继续后脚本将从头执行" : "已由用户暂停，等待补充信息");
                c.event(nr.nodeId(), "node.paused", "执行已暂停，等待人工补充信息");
            }
            r.status(RunStatus.WAITING);
            r.cancelRequested(false);
            r.pauseRequested(true);
            c.event(null, "run.paused", "运行已由用户暂停");
            return true;
        });
    }

    /** Engine shutdown: in-flight nodes go back to pending so a restarted engine continues them. */
    void parkForResume(String runId, int generation) {
        mutateQuietly(runId, c -> {
            Run r = c.run();
            if (r.status() != RunStatus.RUNNING || r.generation() != generation) {
                return false;
            }
            parkInFlightNodes(r, "执行实例退出，等待恢复");
            // released at once: another instance (or this one after a restart) continues immediately
            r.releaseLease();
            c.event(null, "run.parked", "执行实例正在退出，已挂起等待接管");
            return true;
        });
    }

    private static void parkInFlightNodes(Run r, String activity) {
        for (NodeRun nr : r.nodeRuns()) {
            if (nr.status() != NodeStatus.RUNNING) {
                continue;
            }
            boolean script = r.workflow().requireNode(nr.nodeId()).scriptNode();
            nr.status(NodeStatus.PENDING);
            nr.error(null);
            nr.activity(activity);
            nr.interrupted(!script);
            nr.resumeSessionId(script ? null : nr.sessionId());
        }
    }

    /**
     * Un-parks every node stopped by a pause and clears the pause flag. Sub-SOP nodes keep their
     * child run so they follow it instead of starting a second one; agent nodes continue their CLI
     * session. Returns the operator note recorded for the first agent node.
     */
    static String releasePause(RunChange c, String humanContext) {
        Run r = c.run();
        String note = null;
        for (NodeRun nr : r.nodeRuns()) {
            if (!nr.paused()) {
                continue;
            }
            WorkflowNode node = r.workflow().requireNode(nr.nodeId());
            nr.finish(NodeStatus.PENDING, null);
            nr.paused(false);
            nr.error(null);
            nr.activity(null);
            nr.gateResult(null);
            if (node.scriptNode()) {
                nr.feedback(null);
                nr.resumeSessionId(null);
            } else {
                if (note == null) {
                    note = recordHumanContext(c, nr.nodeId(), "", humanContext);
                }
                nr.feedback(note == null || note.isBlank() ? null : note);
                nr.resumeSessionId(nr.sessionId());
            }
        }
        r.pauseRequested(false);
        return note;
    }

    /** Releases the workspace of the run's workspace owner once nothing uses it any more. */
    private void onTerminal(String runId) {
        runs.find(runId).ifPresent(run -> {
            if (run.status() == RunStatus.SUCCEEDED
                    && !run.context().childRun()
                    && !run.repositories().isEmpty()) {
                try {
                    refreshDeliveries(runId);
                } catch (RuntimeException e) {
                    log.warn("run {}: delivery lookup failed: {}", runId, e.toString());
                }
            }
            releaseWorkspaceIfIdle(run.workspaceOwnerId());
        });
    }

    private void releaseWorkspaceIfIdle(String ownerId) {
        if (!settings.releaseWorkspaceOnFinish()) {
            return;
        }
        Run owner = runs.find(ownerId).orElse(null);
        if (owner == null || owner.workspaceReleased()) {
            return;
        }
        if (owner.status() != RunStatus.SUCCEEDED && owner.status() != RunStatus.CANCELLED) {
            return;
        }
        if (hasLiveDescendant(ownerId)) {
            // a child SOP still works in the shared checkout; the last one to settle releases it
            return;
        }
        sandboxProvider(owner.workflow().sandboxProfileId()).ifPresent(provider -> {
            try {
                provider.release(ownerId);
                mutateQuietly(ownerId, c -> {
                    c.run().workspaceReleased(true);
                    return true;
                });
                emit(ownerId, null, Telemetry.of("sandbox", "sandbox.closed", "finish", "run workspace released"));
            } catch (IOException e) {
                log.warn("failed to release workspace of run {}: {}", ownerId, e.toString());
            }
        });
    }

    private boolean hasLiveDescendant(String runId) {
        for (Run child : runs.list(new RunQuery(Set.of(), null, runId, 500))) {
            if (!child.status().terminal() || hasLiveDescendant(child.id())) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------- collaborators

    RunStore runs() {
        return runs;
    }

    CatalogStore catalog() {
        return catalog;
    }

    EngineSettings settings() {
        return settings;
    }

    Clock clock() {
        return clock;
    }

    NodeRunner nodeRunner() {
        return nodeRunner;
    }

    Optional<SandboxProvider> sandboxProvider(String profileId) {
        return sandboxes.stream().filter(p -> p.supports(profileId)).findFirst();
    }

    /** Sandbox of the run's workspace; records the workspace root on first use. */
    Sandbox sandbox(Run run) throws IOException, NodeFailure {
        SandboxProvider provider = sandboxProvider(run.workflow().sandboxProfileId())
                .orElseThrow(() -> new NodeFailure(
                        FailureKind.CONFIGURATION,
                        "sandbox profile '" + run.workflow().sandboxProfileId() + "' is not available"));
        Sandbox sandbox = provider.acquire(run.workspaceOwnerId());
        if (run.workspaceRoot() == null || run.workspaceReleased() || run.workspaceHost() == null) {
            mutateQuietly(run.id(), c -> {
                c.run().workspaceRoot(sandbox.workspace().toString());
                c.run().workspaceReleased(false);
                c.run().workspaceHost(settings.workspaceHost());
                return true;
            });
            emit(
                    run.id(),
                    null,
                    new Telemetry(
                            "sandbox",
                            "sandbox.created",
                            "start",
                            "info",
                            sandbox.id(),
                            null,
                            null,
                            null,
                            Map.of(
                                    "driver",
                                    sandbox.driver(),
                                    "workspace",
                                    sandbox.workspace().toString())));
        }
        return sandbox;
    }

    /**
     * Checks the run's repositories out once per shared workspace (parent and child SOP runs, and
     * parallel first nodes, wait for the same checkout).
     */
    Run prepareRepositories(String runId, Sandbox sandbox, Cancellation cancellation)
            throws NodeFailure, IOException, CancelledException {
        Run run = require(runId);
        if (run.repositoriesPrepared() || run.repositories().isEmpty()) {
            return run;
        }
        ReentrantLock lock = checkoutLocks[Math.floorMod(run.workspaceOwnerId().hashCode(), checkoutLocks.length)];
        lock.lock();
        try {
            run = require(runId);
            if (run.repositoriesPrepared()) {
                return run;
            }
            GitWorkspace git = new GitWorkspace(sandbox, processEnvironment(run, sandbox), cancellation);
            List<RepositoryContext> prepared = new ArrayList<>();
            for (RepositoryContext rc : run.repositories()) {
                try {
                    RepositoryContext checkedOut = git.prepare(rc);
                    prepared.add(checkedOut);
                    emit(
                            runId,
                            null,
                            new Telemetry(
                                    "sandbox",
                                    "repository.checked_out",
                                    "finish",
                                    "info",
                                    rc.name() + " @ "
                                            + (rc.writableWorkBranch() == null
                                                    ? rc.baseBranch() + "（只读）"
                                                    : rc.writableWorkBranch()),
                                    null,
                                    null,
                                    null,
                                    Map.of(
                                            "repositoryId",
                                            rc.repositoryId(),
                                            "dir",
                                            rc.workspaceDir(),
                                            "baseBranch",
                                            rc.baseBranch(),
                                            "baseSha",
                                            String.valueOf(checkedOut.baseSha()))));
                } catch (GitException e) {
                    throw new NodeFailure(FailureKind.EXECUTOR, "repository " + rc.name() + ": " + e.getMessage());
                }
            }
            mutateQuietly(runId, c -> {
                c.run().repositories(prepared);
                c.run().repositoriesPrepared(true);
                return true;
            });
            return require(runId);
        } finally {
            lock.unlock();
        }
    }

    /** Environment for every process of the run: git credentials, commit identity, repository manifest. */
    Map<String, String> processEnvironment(Run run, Sandbox sandbox) throws IOException {
        return GitEnvironment.build(sandbox.workspace(), gitCredentials.credentialsFor(run), run.repositories());
    }

    io.github.loopforge4j.core.pricing.TokenPricing pricing() {
        return pricing;
    }

    DeliveryProvider delivery() {
        return delivery;
    }

    io.github.loopforge4j.core.mcp.PlatformMcp platformMcp() {
        return platformMcp;
    }

    java.util.function.UnaryOperator<String> mcpSecrets() {
        return mcpSecrets;
    }

    java.nio.file.Path gateMcpJava() {
        return gateMcpJava;
    }

    /**
     * Re-reads the pull requests that deliver the run's work branches (merge detection). Repositories
     * whose host cannot be queried are skipped.
     */
    public Run refreshDeliveries(String runId) {
        Run run = require(runId);
        List<PullRequest> found = new ArrayList<>();
        for (RepositoryContext rc : run.repositories()) {
            String work = rc.writableWorkBranch();
            if (work == null || !delivery.supports(rc)) {
                continue;
            }
            try {
                PullRequest pr = Deliveries.representative(delivery.pullRequests(rc, work, rc.baseBranch()));
                if (pr != null) {
                    found.add(pr);
                }
            } catch (DeliveryException e) {
                log.warn("run {}: cannot query pull requests of {}: {}", runId, rc.repositoryId(), e.getMessage());
                run.deliveries().stream()
                        .filter(d -> rc.repositoryId().equals(d.repositoryId()))
                        .forEach(found::add);
            }
        }
        boolean changed = !found.equals(run.deliveries());
        return changed
                ? mutate(runId, c -> {
                    c.run().deliveries(found);
                    for (PullRequest pr : found) {
                        c.event(null, "delivery." + pr.state(), pr.url());
                    }
                    return true;
                })
                : run;
    }

    /** Stores pull requests found by an MR gate, replacing earlier entries of the same repository. */
    void recordDeliveries(String runId, List<PullRequest> pulls) {
        if (pulls.isEmpty()) {
            return;
        }
        mutate(runId, c -> {
            Set<String> repositories = new HashSet<>();
            pulls.forEach(p -> repositories.add(p.repositoryId()));
            List<PullRequest> merged = new ArrayList<>();
            c.run().deliveries().stream()
                    .filter(d -> !repositories.contains(d.repositoryId()))
                    .forEach(merged::add);
            merged.addAll(pulls);
            if (merged.equals(c.run().deliveries())) {
                return false;
            }
            c.run().deliveries(merged);
            for (PullRequest pr : pulls) {
                c.event(null, "delivery." + pr.state(), pr.url());
            }
            return true;
        });
    }

    /** Telemetry sink that appends to the run's event log. */
    EventSink sink(String runId, String nodeId) {
        return telemetry -> emit(runId, nodeId, telemetry);
    }

    void emit(String runId, String nodeId, Telemetry t) {
        Map<String, Object> attributes = new LinkedHashMap<>(t.attributes());
        if (t.phase() != null) {
            attributes.put("phase", t.phase());
        }
        if (t.sessionId() != null) {
            attributes.put("sessionId", t.sessionId());
        }
        if (t.toolCallId() != null) {
            attributes.put("toolCallId", t.toolCallId());
        }
        if (t.usage() != null) {
            attributes.put("inputTokens", t.usage().inputTokens());
            attributes.put("outputTokens", t.usage().outputTokens());
            attributes.put("totalTokens", t.usage().totalTokens());
            attributes.put("costUsd", t.usage().costUsd());
        }
        attributes.values().removeIf(Objects::isNull);
        RunEvent event = new RunEvent(
                0, runId, nodeId, t.category(), t.name(), t.level(), t.message(), attributes, clock.instant());
        telemetry.add(runId, event);
    }

    /** Applies a mutation and notifies listeners; throws when the run does not exist. */
    Run mutate(String runId, RunMutator mutator) {
        telemetry.flush(runId);
        Run run = runs.update(runId, mutator)
                .orElseThrow(() -> new RunNotFoundException("run '" + runId + "' not found"));
        notifyChanged(run);
        return run;
    }

    /** Like {@link #mutate} but tolerates a run that disappeared. */
    Optional<Run> mutateQuietly(String runId, RunMutator mutator) {
        telemetry.flush(runId);
        Optional<Run> run = runs.update(runId, mutator);
        run.ifPresent(this::notifyChanged);
        return run;
    }

    /**
     * A change an execution machine committed through the server's store: listeners hear about it as
     * if this engine had made it (requirements, notifications, live streams).
     */
    public void externalChange(Run run, List<RunEvent> storedEvents) {
        if (storedEvents != null && !storedEvents.isEmpty()) {
            listeners.forEach(l -> safely(() -> l.eventsAppended(run.id(), storedEvents)));
        }
        if (run != null) {
            notifyChanged(run);
        }
    }

    private void notifyChanged(Run run) {
        listeners.forEach(l -> safely(() -> l.runChanged(run)));
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("run listener failed: {}", e.toString());
        }
    }

    /** Records operator context on the run and as an artifact; returns the feedback note. */
    static String recordHumanContext(RunChange c, String nodeId, String reason, String context) {
        String why = reason == null ? "" : reason.strip();
        String ctx = context == null ? "" : context.strip();
        if (ctx.length() > MAX_HUMAN_CONTEXT) {
            ctx = ctx.substring(0, MAX_HUMAN_CONTEXT);
        }
        String note = ctx.isEmpty() ? why : why.isEmpty() ? "人工补充上下文：\n" + ctx : why + "\n\n人工补充上下文：\n" + ctx;
        if (ctx.isEmpty()) {
            return note;
        }
        Run r = c.run();
        r.appendHumanContext(r.humanContext() == null || r.humanContext().isBlank() ? ctx : "---\n" + ctx);
        int attempt = r.node(nodeId).map(NodeRun::attempt).orElse(0);
        c.artifact(ArtifactSupport.create(
                r.id(),
                nodeId,
                attempt,
                "human-context",
                "human-context.md",
                "text/markdown",
                "# 人工补充上下文\n\n" + ctx + "\n",
                c.now()));
        return note;
    }

    private static boolean upstreamSucceeded(Run r, String nodeId) {
        return WorkflowGraph.ancestors(r.workflow(), nodeId).stream()
                .allMatch(id -> r.requireNode(id).status() == NodeStatus.SUCCEEDED);
    }

    private static void requireNotCancelling(Run r) {
        if (r.cancelRequested()) {
            throw new RunStateException("run is being cancelled");
        }
    }

    private static void requireStatus(Run r, RunStatus... allowed) {
        for (RunStatus status : allowed) {
            if (r.status() == status) {
                return;
            }
        }
        throw new RunStateException("run is " + r.status().wire() + "; operation requires "
                + java.util.Arrays.stream(allowed).map(RunStatus::wire).toList());
    }

    private List<String> liveChildren(Run run) {
        return run.nodeRuns().stream()
                .map(NodeRun::childRunId)
                .filter(Objects::nonNull)
                .filter(id -> runs.find(id).map(child -> child.status().live()).orElse(false))
                .toList();
    }

    private List<String> pausedChildren(Run run) {
        return run.nodeRuns().stream()
                .map(NodeRun::childRunId)
                .filter(Objects::nonNull)
                .filter(id -> runs.find(id)
                        .map(c -> c.status() == RunStatus.WAITING && c.pauseRequested())
                        .orElse(false))
                .toList();
    }

    private void cancelChildren(List<String> childIds) {
        for (String childId : childIds) {
            runs.find(childId).filter(child -> !child.status().terminal()).ifPresent(child -> {
                try {
                    cancel(childId);
                } catch (RunStateException e) {
                    log.debug("child {} not cancelled: {}", childId, e.getMessage());
                }
            });
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new RunStateException("engine is shutting down");
        }
    }

    private String newRunId() {
        return "run-" + UUID.randomUUID();
    }

    Duration tick() {
        return settings.schedulerTick();
    }

    /** Builder; run store, catalog store, executors and at least one sandbox provider are required. */
    public static final class Builder {
        private RunStore runs;
        private CatalogStore catalog;
        private ExecutorRegistry executors;
        private final List<SandboxProvider> sandboxes = new ArrayList<>();
        private EngineSettings settings;
        private Clock clock;
        private GitCredentialProvider gitCredentials;
        private io.github.loopforge4j.core.store.InstanceRegistry instances;
        private boolean claimOrphans;
        private Duration telemetryFlushInterval;
        private AdmissionPolicy admission;
        private io.github.loopforge4j.core.pricing.TokenPricing pricing;
        private java.util.function.UnaryOperator<String> mcpSecrets;
        private io.github.loopforge4j.core.mcp.PlatformMcp platformMcp;
        private String runnerId;
        private java.nio.file.Path gateMcpJava;
        private DeliveryProvider delivery;

        private Builder() {}

        /**
         * Makes this engine the execution machine {@code value}: it executes exactly the runs assigned
         * to that runner and never touches others (server instances execute only unassigned runs).
         */
        public Builder runnerId(String value) {
            this.runnerId = value;
            return this;
        }

        /** Platform-served MCP servers for roles with {@code platformMcp} / {@code knowledgeBase}. */
        public Builder platformMcp(io.github.loopforge4j.core.mcp.PlatformMcp value) {
            this.platformMcp = value;
            return this;
        }

        /** Resolves {@code ${secret:NAME}} placeholders of MCP server definitions. */
        public Builder mcpSecrets(java.util.function.UnaryOperator<String> value) {
            this.mcpSecrets = value;
            return this;
        }

        /** {@code java} launcher for the gate MCP server as seen inside the sandbox. */
        public Builder gateMcpJava(java.nio.file.Path value) {
            this.gateMcpJava = value;
            return this;
        }

        /** Shared registry of engine instances (multi-instance deployments use a database one). */
        public Builder instances(io.github.loopforge4j.core.store.InstanceRegistry value) {
            this.instances = value;
            return this;
        }

        /** How long telemetry is batched before it is written; zero writes every event at once. */
        public Builder telemetryFlushInterval(Duration value) {
            this.telemetryFlushInterval = value;
            return this;
        }

        /** Price table for usage the CLIs do not price themselves. */
        public Builder pricing(io.github.loopforge4j.core.pricing.TokenPricing value) {
            this.pricing = value;
            return this;
        }

        /** Limits on concurrently running runs; excess runs are queued. */
        public Builder admission(AdmissionPolicy value) {
            this.admission = value;
            return this;
        }

        /** Periodically take over runs whose owner stopped renewing its lease. */
        public Builder claimOrphans(boolean value) {
            this.claimOrphans = value;
            return this;
        }

        public Builder gitCredentials(GitCredentialProvider value) {
            this.gitCredentials = value;
            return this;
        }

        public Builder delivery(DeliveryProvider value) {
            this.delivery = value;
            return this;
        }

        public Builder runStore(RunStore value) {
            this.runs = value;
            return this;
        }

        public Builder catalogStore(CatalogStore value) {
            this.catalog = value;
            return this;
        }

        public Builder executors(ExecutorRegistry value) {
            this.executors = value;
            return this;
        }

        public Builder sandbox(SandboxProvider value) {
            this.sandboxes.add(value);
            return this;
        }

        public Builder settings(EngineSettings value) {
            this.settings = value;
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        public RunEngine build() {
            return new RunEngine(this);
        }
    }
}
