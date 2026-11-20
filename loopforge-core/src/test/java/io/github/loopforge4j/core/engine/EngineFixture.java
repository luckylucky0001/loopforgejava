package io.github.loopforge4j.core.engine;

import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.executor.MockAgentExecutor;
import io.github.loopforge4j.core.runtime.git.GitCredentialProvider;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import io.github.loopforge4j.core.store.RunStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Engine wired with in-memory stores, a real local sandbox and the deterministic mock executor. */
final class EngineFixture implements AutoCloseable {

    final RunStore runs;
    final InMemoryCatalogStore catalog = new InMemoryCatalogStore();
    final Path root;
    final RunEngine engine;

    EngineFixture(Path root) {
        this(root, new InMemoryRunStore(), true);
    }

    EngineFixture(Path root, RunStore runs, boolean releaseWorkspace) {
        this(root, runs, releaseWorkspace, GitCredentialProvider.NONE, DeliveryProvider.NONE);
    }

    EngineFixture(
            Path root, RunStore runs, boolean releaseWorkspace, GitCredentialProvider git, DeliveryProvider delivery) {
        this(root, runs, releaseWorkspace, git, delivery, io.github.loopforge4j.core.mcp.PlatformMcp.NONE);
    }

    EngineFixture(Path root, io.github.loopforge4j.core.mcp.PlatformMcp platform) {
        this(root, new InMemoryRunStore(), true, GitCredentialProvider.NONE, DeliveryProvider.NONE, platform);
    }

    EngineFixture(
            Path root,
            RunStore runs,
            boolean releaseWorkspace,
            GitCredentialProvider git,
            DeliveryProvider delivery,
            io.github.loopforge4j.core.mcp.PlatformMcp platform) {
        this(
                root,
                runs,
                releaseWorkspace,
                git,
                delivery,
                platform,
                io.github.loopforge4j.core.store.WorkspaceSnapshotStore.NONE,
                io.github.loopforge4j.core.runtime.WorkspaceSnapshot.Limits.DEFAULT);
    }

    /** Released workspaces are snapshotted into {@code snapshots}. */
    EngineFixture(
            Path root,
            io.github.loopforge4j.core.store.WorkspaceSnapshotStore snapshots,
            io.github.loopforge4j.core.runtime.WorkspaceSnapshot.Limits limits) {
        this(
                root,
                new InMemoryRunStore(),
                true,
                GitCredentialProvider.NONE,
                DeliveryProvider.NONE,
                io.github.loopforge4j.core.mcp.PlatformMcp.NONE,
                snapshots,
                limits);
    }

    EngineFixture(
            Path root,
            RunStore runs,
            boolean releaseWorkspace,
            GitCredentialProvider git,
            DeliveryProvider delivery,
            io.github.loopforge4j.core.mcp.PlatformMcp platform,
            io.github.loopforge4j.core.store.WorkspaceSnapshotStore snapshots,
            io.github.loopforge4j.core.runtime.WorkspaceSnapshot.Limits limits) {
        this.root = root;
        this.runs = runs;
        ExecutorRegistry registry = ExecutorRegistry.withBuiltIns(CredentialProvider.NONE)
                .register(new MockAgentExecutor(Duration.ofMillis(5)));
        LocalSandboxOptions options =
                new LocalSandboxOptions(root, LocalSandboxOptions.DEFAULT_PASSTHROUGH, true, Duration.ofMinutes(2));
        this.engine = RunEngine.builder()
                .runStore(runs)
                .catalogStore(catalog)
                .executors(registry)
                .sandbox(new LocalSandboxProvider(options))
                .gitCredentials(git)
                .delivery(delivery)
                .platformMcp(platform)
                .snapshots(snapshots)
                .snapshotLimits(limits)
                .settings(new EngineSettings(
                        "test", "mock", Duration.ofMinutes(2), Duration.ofMillis(25), releaseWorkspace))
                .build();
    }

    EngineFixture agent(AgentDefinition agent) {
        catalog.saveAgent(agent);
        return this;
    }

    EngineFixture workflow(WorkflowDefinition workflow) {
        catalog.saveWorkflow(workflow);
        return this;
    }

    Run start(String workflowId) {
        return engine.start(workflowId, "实现一个功能", ExecutionContext.triggeredBy("tester"));
    }

    Run awaitStatus(String runId, RunStatus status) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> engine.require(runId).status() == status && !engine.scheduling(runId));
        return engine.require(runId);
    }

    Run awaitNode(String runId, String nodeId, io.github.loopforge4j.core.domain.NodeStatus status) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> engine.require(runId).requireNode(nodeId).status() == status);
        return engine.require(runId);
    }

    /**
     * Waits until a script of the run created {@code name} in its workspace. Waiting for the node to be
     * marked running is not enough: the process may not have started yet when a test interrupts it.
     */
    void awaitWorkspaceFile(String runId, String name) {
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> {
                    String root = engine.require(runId).workspaceRoot();
                    return root != null
                            && java.nio.file.Files.exists(Path.of(root).resolve(name));
                });
    }

    List<String> eventTypes(String runId) {
        return engine.events(runId, 0, 0).stream().map(e -> e.type()).toList();
    }

    static NodeRun node(Run run, String id) {
        return run.requireNode(id);
    }

    @Override
    public void close() {
        engine.close();
    }
}
