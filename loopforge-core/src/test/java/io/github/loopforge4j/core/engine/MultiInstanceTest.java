package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RepositoryProvider;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import io.github.loopforge4j.core.store.InMemoryInstanceRegistry;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import io.github.loopforge4j.core.store.InstanceRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several engine instances on one store: leases, takeover, workspace host binding. */
class MultiInstanceTest {

    static final Duration TTL = Duration.ofMillis(900);

    @TempDir
    Path root;

    final InMemoryRunStore runs = new InMemoryRunStore();
    final InMemoryCatalogStore catalog = new InMemoryCatalogStore();
    final InstanceRegistry registry = new InMemoryInstanceRegistry();
    final List<RunEngine> engines = new ArrayList<>();

    @BeforeEach
    void catalog() {
        catalog.saveWorkflow(WorkflowDefinition.builder("slow")
                .node(WorkflowNode.builder("work", NodeType.SCRIPT)
                        .script(
                                "for i in 1 2 3 4 5 6 7 8; do echo \"$i\" >> \"$LOOPFORGE_WORKSPACE/../beats.txt\"; sleep 0.25; done")
                        .build())
                .build());
        catalog.saveWorkflow(WorkflowDefinition.builder("quick")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                        .script("echo done > out.txt")
                        .build())
                .build());
    }

    @AfterEach
    void close() {
        engines.forEach(RunEngine::close);
    }

    @Test
    void aRunExecutesOnlyOnTheInstanceHoldingItsLease() throws Exception {
        RunEngine a = engine("a", "host-1", false);
        RunEngine b = engine("b", "host-1", true);
        Run run = a.start("slow", "", ExecutionContext.EMPTY);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> runs.find(run.id()).orElseThrow().leaseUntil() != null);
        // several lease periods pass; b keeps looking for orphans but never takes the run
        Thread.sleep(TTL.multipliedBy(2).toMillis());
        assertThat(b.scheduling(run.id())).isFalse();
        assertThat(b.ownedElsewhere(run.id())).isTrue();
        Run done = awaitRun(run.id(), RunStatus.SUCCEEDED);
        // the retiring scheduler releases the lease right after it leaves the scheduler table
        await().atMost(Duration.ofSeconds(5))
                .until(() -> runs.find(run.id()).orElseThrow().ownerInstanceId() == null);
        assertThat(Files.readAllLines(root.resolve("ws/beats.txt")))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        assertThat(done.workspaceHost()).isEqualTo("host-1");
    }

    @Test
    void gracefulShutdownHandsTheRunOverImmediately() throws Exception {
        RunEngine a = engine("a", "host-1", false);
        Run run = a.start("slow", "", ExecutionContext.EMPTY);
        await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(root.resolve("ws/beats.txt")));
        a.close();
        engines.remove(a);
        Run parked = runs.find(run.id()).orElseThrow();
        assertThat(parked.ownerInstanceId()).isNull();
        RunEngine b = engine("b", "host-1", true);
        awaitRun(run.id(), RunStatus.SUCCEEDED);
        assertThat(eventTypes(b, run.id())).contains("run.parked", "run.recovered");
    }

    @Test
    void anExpiredLeaseOfASilentInstanceIsTakenOver() throws Exception {
        Run run = orphan("quick", "ghost", Instant.now().plus(TTL), "host-1");
        // the ghost heartbeated once and then went silent
        registry.heartbeat(new InstanceRegistry.Instance("ghost", "host-2", 1, Instant.now(), Instant.now()));
        RunEngine b = engine("b", "host-1", true);
        assertThat(b.claimOrphanedRuns()).isEmpty(); // lease still valid and ghost looks alive
        Run done = awaitRun(run.id(), RunStatus.SUCCEEDED);
        assertThat(eventTypes(b, run.id())).contains("run.recovered");
        assertThat(runs.events(run.id(), 0, 100).stream().map(e -> e.message()).toList())
                .anyMatch(m -> m.contains("接管了执行实例 ghost"));
        assertThat(done.generation()).isGreaterThan(1);
    }

    @Test
    void aDeadProcessOnTheSameHostIsDetectedWithoutWaitingForTheLease() throws Exception {
        Process exited = new ProcessBuilder("true").start();
        exited.waitFor();
        Instant started = exited.info().startInstant().orElse(Instant.now());
        Run run = orphan("quick", "crashed", Instant.now().plus(Duration.ofMinutes(10)), "host-1");
        registry.heartbeat(new InstanceRegistry.Instance("crashed", "host-1", exited.pid(), started, Instant.now()));
        RunEngine b = engine("b", "host-1", false);
        assertThat(b.claimOrphanedRuns()).containsExactly(run.id());
        awaitRun(run.id(), RunStatus.SUCCEEDED);
    }

    @Test
    void anInstanceThatLostItsLeaseStopsExecuting() throws Exception {
        RunEngine a = engine("a", "host-1", false);
        Run run = a.start("slow", "", ExecutionContext.EMPTY);
        await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(root.resolve("ws/beats.txt")));
        // another instance took the run over (e.g. after a long GC pause of a)
        runs.update(run.id(), c -> {
            c.run().ownerInstanceId("usurper");
            c.run().generation(c.run().generation() + 1);
            c.run().leaseUntil(c.now().plus(Duration.ofMinutes(5)));
            return true;
        });
        registry.heartbeat(new InstanceRegistry.Instance(
                "usurper",
                "host-1",
                ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElse(null),
                Instant.now().plus(Duration.ofMinutes(5))));
        await().atMost(Duration.ofSeconds(5)).until(() -> !a.scheduling(run.id()));
        long beats = Files.readAllLines(root.resolve("ws/beats.txt")).size();
        Thread.sleep(800);
        assertThat(Files.readAllLines(root.resolve("ws/beats.txt"))).hasSize((int) beats);
        assertThat(beats).isLessThan(8);
    }

    @Test
    void aRunStaysBoundToItsLiveWorkspaceHost() throws Exception {
        Run run = orphan("quick", "far", Instant.now().minusSeconds(1), "host-2");
        registry.heartbeat(new InstanceRegistry.Instance(
                "far-2", "host-2", 1, Instant.now(), Instant.now().plus(Duration.ofMinutes(5))));
        RunEngine b = engine("b", "host-1", false);
        assertThat(b.claimOrphanedRuns()).isEmpty();
        b.ensureScheduler(run.id());
        assertThat(b.scheduling(run.id())).isFalse();
    }

    @Test
    void aGoneHostWithoutCheckoutIsRebuiltHere() throws Exception {
        Run run = orphan("quick", "far", Instant.now().minusSeconds(1), "host-2");
        RunEngine b = engine("b", "host-1", false);
        assertThat(b.claimOrphanedRuns()).containsExactly(run.id());
        Run done = awaitRun(run.id(), RunStatus.SUCCEEDED);
        assertThat(done.workspaceHost()).isEqualTo("host-1");
        assertThat(eventTypes(b, run.id())).contains("run.workspace.rebound");
    }

    @Test
    void aGoneHostWithARepositoryCheckoutFailsTheRun() throws Exception {
        Run run = orphan("quick", "far", Instant.now().minusSeconds(1), "host-2");
        runs.update(run.id(), c -> {
            c.run()
                    .repositories(List.of(new RepositoryContext(
                            "app",
                            "app",
                            RepositoryProvider.HTTPS,
                            "https://git/app.git",
                            null,
                            "main",
                            "feature/x",
                            "app",
                            false,
                            false,
                            false,
                            null)));
            c.run().repositoriesPrepared(true);
            return true;
        });
        RunEngine b = engine("b", "host-1", false);
        assertThat(b.claimOrphanedRuns()).isEmpty();
        Run failed = runs.find(run.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(eventTypes(b, run.id())).contains("run.workspace_lost");
    }

    @Test
    void cancellingOnAnotherInstanceIsSettledByTheOwner() throws Exception {
        RunEngine a = engine("a", "host-1", false);
        RunEngine b = engine("b", "host-1", true);
        Run run = a.start("slow", "", ExecutionContext.EMPTY);
        await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(root.resolve("ws/beats.txt")));
        b.cancel(run.id());
        Run cancelled = awaitRun(run.id(), RunStatus.CANCELLED);
        assertThat(cancelled.ownerInstanceId()).isNull();
        assertThat(b.scheduling(run.id())).isFalse();
    }

    @Test
    void concurrentOrphanClaimsRunTheRunExactlyOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Files.deleteIfExists(root.resolve("ws/beats.txt"));
            Run run = orphan("slow", "gone-" + round, Instant.now().minusSeconds(1), "host-1");
            RunEngine a = engine("a" + round, "host-1", false);
            RunEngine b = engine("b" + round, "host-1", false);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            Thread ta = Thread.ofVirtual().start(() -> {
                awaitLatch(go);
                a.claimOrphanedRuns();
            });
            Thread tb = Thread.ofVirtual().start(() -> {
                awaitLatch(go);
                b.claimOrphanedRuns();
            });
            go.countDown();
            ta.join();
            tb.join();
            assertThat(a.scheduling(run.id()) ^ b.scheduling(run.id()))
                    .as("exactly one owner")
                    .isTrue();
            awaitRun(run.id(), RunStatus.SUCCEEDED);
            assertThat(Files.readAllLines(root.resolve("ws/beats.txt")))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
            assertThat(eventTypes(a, run.id()).stream().filter("run.recovered"::equals))
                    .hasSize(1);
        }
    }

    private static void awaitLatch(java.util.concurrent.CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ helpers

    private RunEngine engine(String name, String host, boolean claim) {
        EngineSettings settings = new EngineSettings(
                        "local-" + name, "mock", Duration.ofMinutes(2), Duration.ofMillis(25), false)
                .withLease(TTL, Duration.ofMillis(150))
                .withInstance("local-" + name, host);
        RunEngine engine = RunEngine.builder()
                .runStore(runs)
                .catalogStore(catalog)
                .executors(ExecutorRegistry.withBuiltIns(CredentialProvider.NONE))
                .sandbox(new LocalSandboxProvider(new LocalSandboxOptions(
                        root.resolve("ws"), LocalSandboxOptions.DEFAULT_PASSTHROUGH, true, Duration.ofMinutes(2))))
                .settings(settings)
                .instances(registry)
                .claimOrphans(claim)
                .build();
        engines.add(engine);
        return engine;
    }

    /** A running run left behind by {@code owner}. */
    private Run orphan(String workflow, String owner, Instant leaseUntil, String host) {
        Run run = Run.create(
                "run-" + owner + "-" + System.nanoTime(),
                catalog.workflow(workflow).orElseThrow(),
                "",
                ExecutionContext.EMPTY,
                Instant.now());
        run.status(RunStatus.RUNNING);
        run.generation(1);
        run.ownerInstanceId(owner);
        run.leaseUntil(leaseUntil);
        run.workspaceHost(host);
        run.workspaceRoot(root.resolve("ws").resolve(run.id()).toString());
        runs.insert(run, List.of());
        return run;
    }

    private Run awaitRun(String runId, RunStatus status) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> runs.find(runId).orElseThrow().status() == status
                        && engines.stream().noneMatch(e -> e.scheduling(runId)));
        return runs.find(runId).orElseThrow();
    }

    private static List<String> eventTypes(RunEngine engine, String runId) {
        return engine.events(runId, 0, 0).stream().map(e -> e.type()).toList();
    }
}
