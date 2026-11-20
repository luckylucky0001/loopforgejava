package io.github.loopforge4j.core.engine;

import static io.github.loopforge4j.core.domain.NodeType.SCRIPT;
import static io.github.loopforge4j.core.domain.NodeType.SOP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import io.github.loopforge4j.core.store.RunMutator;
import io.github.loopforge4j.core.store.RunQuery;
import io.github.loopforge4j.core.store.RunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Regression tests for races found in the concurrency review of the scheduler and controls. */
class ConcurrencyRegressionTest {

    @TempDir
    Path root;

    /** Resuming a paused parent must follow its existing child SOP instead of starting a second one. */
    @Test
    void resumingPausedParentKeepsItsChildRun() {
        try (EngineFixture f = new EngineFixture(root)) {
            f.workflow(WorkflowDefinition.builder("child")
                            .node(WorkflowNode.builder("work", SCRIPT)
                                    .script(
                                            "if [ -f .child-resumed ]; then echo child-done; else touch .child-resumed; sleep 30; fi")
                                    .build())
                            .build())
                    .workflow(WorkflowDefinition.builder("parent")
                            .node(WorkflowNode.builder("sub", SOP)
                                    .sopWorkflow("child")
                                    .build())
                            .build());
            Run parent = f.start("parent");
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                String child = f.engine.require(parent.id()).requireNode("sub").childRunId();
                return child != null
                        && f.engine.require(child).requireNode("work").status() == NodeStatus.RUNNING;
            });
            String childId = f.engine.require(parent.id()).requireNode("sub").childRunId();
            f.awaitWorkspaceFile(childId, ".child-resumed");

            f.engine.pause(parent.id());
            f.awaitStatus(parent.id(), RunStatus.WAITING);
            f.awaitStatus(childId, RunStatus.WAITING);
            f.engine.resume(parent.id(), null);
            Run done = f.awaitStatus(parent.id(), RunStatus.SUCCEEDED);

            assertThat(done.requireNode("sub").childRunId()).isEqualTo(childId);
            assertThat(f.engine.list(new RunQuery(java.util.Set.of(), null, parent.id(), 10)))
                    .extracting(Run::id)
                    .containsExactly(childId);
            assertThat(f.engine.require(childId).status()).isEqualTo(RunStatus.SUCCEEDED);
        }
    }

    /** An automatic rework must not clear a cancel that an operator requested in the meantime. */
    @Test
    void automaticReworkDoesNotReviveACancellingRun() {
        InMemoryRunStore runs = new InMemoryRunStore();
        Run run = failedReviewRun("run-cancel");
        run.cancelRequested(true);
        runs.insert(run, List.of());
        try (EngineFixture f = new EngineFixture(root, runs, false)) {
            f.engine.reject(run.id(), "check", null, "auto", null, false);
            Run settled = f.awaitStatus(run.id(), RunStatus.CANCELLED);
            assertThat(settled.requireNode("build").reworkCount()).isZero();
            assertThatThrownBy(() -> f.engine.reject(run.id(), "check", null, "op", null))
                    .isInstanceOf(RunStateException.class);
        }
    }

    /** An automatic rework during a pause resets the target but keeps the run paused. */
    @Test
    void automaticReworkKeepsAPendingPause() {
        InMemoryRunStore runs = new InMemoryRunStore();
        Run run = failedReviewRun("run-pause");
        run.pauseRequested(true);
        runs.insert(run, List.of());
        try (EngineFixture f = new EngineFixture(root, runs, false)) {
            f.engine.reject(run.id(), "check", null, "auto", null, false);
            Run paused = f.awaitStatus(run.id(), RunStatus.WAITING);
            assertThat(paused.pauseRequested()).isTrue();
            assertThat(paused.requireNode("build").reworkCount()).isEqualTo(1);
            assertThat(paused.requireNode("build").status()).isEqualTo(NodeStatus.PENDING);

            f.engine.resume(run.id(), null);
            Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            assertThat(done.requireNode("check").output()).contains("fine");
        }
    }

    /** Operator decisions are refused while a cancel is pending instead of silently clearing it. */
    @Test
    void operatorControlsAreRefusedWhileCancelling() {
        InMemoryRunStore runs = new InMemoryRunStore();
        Run run = failedReviewRun("run-op");
        run.status(RunStatus.FAILED);
        run.cancelRequested(true);
        runs.insert(run, List.of());
        try (EngineFixture f = new EngineFixture(root, runs, false)) {
            assertThatThrownBy(() -> f.engine.retry(run.id(), null, null, null)).hasMessageContaining("cancelled");
            assertThatThrownBy(() -> f.engine.pass(run.id(), "check", null)).hasMessageContaining("cancelled");
        }
    }

    /** A forced cancel settle applies only while the cancel is pending and no scheduler took over. */
    @Test
    void forcedCancelSettleIsFencedByGeneration() {
        InMemoryRunStore runs = new InMemoryRunStore();
        Run run = failedReviewRun("run-force");
        run.status(RunStatus.WAITING);
        run.generation(5);
        runs.insert(run, List.of());
        try (EngineFixture f = new EngineFixture(root, runs, false)) {
            f.engine.settleCancelled(run.id(), 5, true);
            assertThat(f.engine.require(run.id()).status())
                    .as("no pending cancel")
                    .isEqualTo(RunStatus.WAITING);
            runs.update(run.id(), c -> {
                c.run().cancelRequested(true);
                return true;
            });
            f.engine.settleCancelled(run.id(), 4, true);
            assertThat(f.engine.require(run.id()).status())
                    .as("stale generation")
                    .isEqualTo(RunStatus.WAITING);
            f.engine.settleCancelled(run.id(), 5, true);
            assertThat(f.engine.require(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
        }
    }

    /** Cancelling a paused (waiting) run settles atomically; nothing can relaunch it afterwards. */
    @Test
    void cancellingAPausedRunSettlesAtomically() {
        try (EngineFixture f = new EngineFixture(root)) {
            f.workflow(WorkflowDefinition.builder("slow")
                    .node(WorkflowNode.builder("work", SCRIPT)
                            .script("sleep 30")
                            .build())
                    .build());
            Run run = f.start("slow");
            f.awaitNode(run.id(), "work", NodeStatus.RUNNING);
            f.engine.pause(run.id());
            f.awaitStatus(run.id(), RunStatus.WAITING);
            assertThat(f.engine.cancel(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
            assertThatThrownBy(() -> f.engine.resume(run.id(), null)).isInstanceOf(RunStateException.class);
            assertThat(f.engine.scheduling(run.id())).isFalse();
        }
    }

    /** Passing one paused branch must also release the other paused branches. */
    @Test
    void passingOnePausedBranchReleasesTheOthers() {
        try (EngineFixture f = new EngineFixture(root)) {
            f.workflow(WorkflowDefinition.builder("fan")
                    .node(WorkflowNode.builder("left", SCRIPT)
                            .script("if [ -f .left ]; then echo l; else touch .left; sleep 30; fi")
                            .build())
                    .node(WorkflowNode.builder("right", SCRIPT)
                            .script("if [ -f .right ]; then echo r; else touch .right; sleep 30; fi")
                            .build())
                    .node(WorkflowNode.builder("join", SCRIPT)
                            .script("echo joined")
                            .build())
                    .edge("left", "join")
                    .edge("right", "join")
                    .build());
            Run run = f.start("fan");
            f.awaitNode(run.id(), "left", NodeStatus.RUNNING);
            f.awaitNode(run.id(), "right", NodeStatus.RUNNING);
            f.awaitWorkspaceFile(run.id(), ".left");
            f.awaitWorkspaceFile(run.id(), ".right");
            f.engine.pause(run.id());
            Run paused = f.awaitStatus(run.id(), RunStatus.WAITING);
            assertThat(paused.requireNode("left").paused()).isTrue();
            assertThat(paused.requireNode("right").paused()).isTrue();

            f.engine.pass(run.id(), "left", null);
            Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            assertThat(done.requireNode("right").output()).contains("r");
            assertThat(done.requireNode("join").output()).contains("joined");
        }
    }

    /** A scheduler that dies on a transient store error is restarted instead of stranding the run. */
    @Test
    void crashedSchedulerIsRestarted() {
        FlakyRunStore runs = new FlakyRunStore();
        try (EngineFixture f = new EngineFixture(root, runs, false)) {
            f.workflow(WorkflowDefinition.builder("wf")
                    .node(WorkflowNode.builder("a", SCRIPT)
                            .script("sleep 0.3; echo a")
                            .build())
                    .node(WorkflowNode.builder("b", SCRIPT).script("echo b").build())
                    .chain("a", "b")
                    .build());
            Run run = f.start("wf");
            runs.failSchedulerReads.set(2);
            Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            assertThat(done.requireNode("b").output()).contains("b");
            assertThat(runs.failSchedulerReads.get()).isLessThanOrEqualTo(0);
        }
    }

    /** Cancelling a parent waits for its child to drain before the shared workspace is deleted. */
    @Test
    void parentCancelReleasesSharedWorkspaceAfterChildSettles() throws Exception {
        try (EngineFixture f = new EngineFixture(root)) {
            f.workflow(WorkflowDefinition.builder("child")
                            .node(WorkflowNode.builder("work", SCRIPT)
                                    .script("sleep 30")
                                    .build())
                            .build())
                    .workflow(WorkflowDefinition.builder("parent")
                            .node(WorkflowNode.builder("sub", SOP)
                                    .sopWorkflow("child")
                                    .build())
                            .build());
            Run parent = f.start("parent");
            await().atMost(Duration.ofSeconds(10)).until(() -> {
                String child = f.engine.require(parent.id()).requireNode("sub").childRunId();
                return child != null
                        && f.engine.require(child).requireNode("work").status() == NodeStatus.RUNNING;
            });
            String childId = f.engine.require(parent.id()).requireNode("sub").childRunId();
            // the child works in (and records) the parent's workspace
            Path workspace = Path.of(f.engine.require(childId).workspaceRoot());
            assertThat(workspace.getFileName().toString()).isEqualTo(parent.id());

            f.engine.cancel(parent.id());
            f.awaitStatus(parent.id(), RunStatus.CANCELLED);
            f.awaitStatus(childId, RunStatus.CANCELLED);
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> f.engine.require(parent.id()).workspaceReleased());
            assertThat(Files.exists(workspace)).isFalse();
        }
    }

    /** A node whose writes the store keeps rejecting backs off and is failed, instead of hot-looping. */
    @Test
    void nodeWhoseWritesAreRejectedBacksOffAndFails() {
        RejectingRunStore runs = new RejectingRunStore("boom");
        try (EngineFixture f = new EngineFixture(root, runs, false)) {
            f.workflow(WorkflowDefinition.builder("wf")
                    .node(WorkflowNode.builder("boom", SCRIPT)
                            .script("echo never")
                            .build())
                    .build());
            Run run = f.start("wf");
            Run failed = f.awaitStatus(run.id(), RunStatus.FAILED);
            assertThat(failed.requireNode("boom").error()).contains("反复崩溃").contains("StoreException");
            assertThat(runs.rejected.get()).isBetween(5, 7);
        }
    }

    /** Rejects every write that records an event for one node, like a database column that is too small. */
    static final class RejectingRunStore implements RunStore {
        final AtomicInteger rejected = new AtomicInteger();
        private final String nodeId;
        private final InMemoryRunStore delegate = new InMemoryRunStore();

        RejectingRunStore(String nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public Optional<Run> update(String runId, RunMutator mutator) {
            return delegate.update(runId, c -> {
                boolean write = mutator.apply(c);
                if (c.pendingEvents().stream().anyMatch(e -> nodeId.equals(e.nodeId()))) {
                    rejected.incrementAndGet();
                    throw new io.github.loopforge4j.core.store.StoreException("run store write failed", null);
                }
                return write;
            });
        }

        @Override
        public List<RunEvent> appendEvents(String runId, List<RunEvent> events) {
            return delegate.appendEvents(runId, events);
        }

        @Override
        public void insert(Run run, List<RunEvent> initialEvents) {
            delegate.insert(run, initialEvents);
        }

        @Override
        public Optional<Run> find(String runId) {
            return delegate.find(runId);
        }

        @Override
        public List<Run> list(RunQuery query) {
            return delegate.list(query);
        }

        @Override
        public List<RunEvent> events(String runId, long afterSequence, int limit) {
            return delegate.events(runId, afterSequence, limit);
        }

        @Override
        public List<Artifact> artifacts(String runId) {
            return delegate.artifacts(runId);
        }

        @Override
        public Optional<Artifact> artifact(String runId, String artifactId) {
            return delegate.artifact(runId, artifactId);
        }
    }

    private static Run failedReviewRun(String id) {
        WorkflowDefinition wf = WorkflowDefinition.builder("review")
                .node(WorkflowNode.builder("build", SCRIPT).script("echo built").build())
                .node(WorkflowNode.builder("check", SCRIPT)
                        .script("if [ -f .ok ]; then echo fine; else touch .ok; exit 1; fi")
                        .rejectTo("build")
                        .maxReworks(2)
                        .build())
                .chain("build", "check")
                .build();
        Run run = Run.create(id, wf, "", ExecutionContext.EMPTY, Instant.now());
        run.status(RunStatus.RUNNING);
        run.requireNode("build").status(NodeStatus.SUCCEEDED);
        run.requireNode("build").attempt(1);
        run.requireNode("check").status(NodeStatus.FAILED);
        run.requireNode("check").attempt(1);
        run.requireNode("check").error("exit 1");
        return run;
    }

    /** Fails reads issued by scheduler threads while armed, to simulate a transient database error. */
    static final class FlakyRunStore implements RunStore {
        final AtomicInteger failSchedulerReads = new AtomicInteger();
        private final InMemoryRunStore delegate = new InMemoryRunStore();

        @Override
        public Optional<Run> find(String runId) {
            if (Thread.currentThread().getName().startsWith("run-") && failSchedulerReads.getAndDecrement() > 0) {
                throw new IllegalStateException("simulated database outage");
            }
            return delegate.find(runId);
        }

        @Override
        public void insert(Run run, List<RunEvent> initialEvents) {
            delegate.insert(run, initialEvents);
        }

        @Override
        public List<Run> list(RunQuery query) {
            return delegate.list(query);
        }

        @Override
        public Optional<Run> update(String runId, RunMutator mutator) {
            return delegate.update(runId, mutator);
        }

        @Override
        public List<RunEvent> appendEvents(String runId, List<RunEvent> events) {
            return delegate.appendEvents(runId, events);
        }

        @Override
        public List<RunEvent> events(String runId, long afterSequence, int limit) {
            return delegate.events(runId, afterSequence, limit);
        }

        @Override
        public List<Artifact> artifacts(String runId) {
            return delegate.artifacts(runId);
        }

        @Override
        public Optional<Artifact> artifact(String runId, String artifactId) {
            return delegate.artifact(runId, artifactId);
        }
    }
}
