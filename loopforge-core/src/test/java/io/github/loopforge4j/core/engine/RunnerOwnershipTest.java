package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.executor.MockAgentExecutor;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import io.github.loopforge4j.core.store.InMemoryInstanceRegistry;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run assigned to an execution machine is executed only by the engine that is that machine; the
 * server engine queues it, changes it only in the store and never claims it, even with an expired
 * lease.
 */
class RunnerOwnershipTest {

    @TempDir
    Path root;

    final InMemoryRunStore runs = new InMemoryRunStore();
    final InMemoryCatalogStore catalog = new InMemoryCatalogStore();
    final InMemoryInstanceRegistry instances = new InMemoryInstanceRegistry();
    RunEngine server;
    RunEngine runner;

    RunEngine engine(String instance, String runnerId, Path workspaces) {
        return RunEngine.builder()
                .runStore(runs)
                .catalogStore(catalog)
                .executors(ExecutorRegistry.withBuiltIns(CredentialProvider.NONE)
                        .register(new MockAgentExecutor(Duration.ofMillis(5))))
                .sandbox(new LocalSandboxProvider(new LocalSandboxOptions(
                        workspaces, LocalSandboxOptions.DEFAULT_PASSTHROUGH, true, Duration.ofMinutes(1))))
                .instances(instances)
                .claimOrphans(true)
                .runnerId(runnerId)
                .settings(new EngineSettings(
                        instance,
                        "mock",
                        Duration.ofMinutes(1),
                        Duration.ofMillis(25),
                        false,
                        Duration.ofSeconds(2),
                        Duration.ofMillis(200),
                        instance + "-host",
                        false))
                .build();
    }

    @AfterEach
    void close() {
        if (runner != null) {
            runner.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void onlyTheAssignedRunnerExecutesItsRuns() throws Exception {
        catalog.saveWorkflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("build", NodeType.SCRIPT)
                        .script("echo built > out.txt")
                        .build())
                .node(WorkflowNode.builder("gate", NodeType.APPROVAL).build())
                .node(WorkflowNode.builder("ship", NodeType.SCRIPT)
                        .script("echo $LOOPFORGE_SANDBOX_ID > ship.txt")
                        .build())
                .chain("build", "gate")
                .chain("gate", "ship")
                .build());
        server = engine("server", null, root.resolve("server"));
        Run run = server.start("wf", "", ExecutionContext.triggeredBy("alice").onRunner("r1"));
        assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
        // the server neither promotes nor claims it
        assertThat(server.recoverInterruptedRuns()).isEmpty();
        Thread.sleep(300);
        assertThat(runs.find(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);

        runner = engine("runner-instance", "r1", root.resolve("runner"));
        assertThat(runner.recoverInterruptedRuns()).containsExactly(run.id());
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runs.find(run.id()).orElseThrow().status() == RunStatus.WAITING);
        assertThat(root.resolve("runner"))
                .isDirectoryContaining(p -> p.getFileName().toString().equals(run.id()));

        // a person approves on the server: the server only records it, the runner continues
        server.approve(run.id(), "gate", null);
        assertThat(runs.find(run.id()).orElseThrow().ownerInstanceId()).isNotEqualTo("server");
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            runner.recoverInterruptedRuns();
            return runs.find(run.id()).orElseThrow().status() == RunStatus.SUCCEEDED;
        });
        Run done = runs.find(run.id()).orElseThrow();
        assertThat(done.ownerInstanceId()).isNotEqualTo("server");
        assertThat(java.nio.file.Files.readString(
                        root.resolve("runner").resolve(run.id()).resolve("ship.txt")))
                .startsWith("local-");
        assertThat(root.resolve("server").resolve(run.id())).doesNotExist();

        // a runner never takes the server's runs
        Run plain = server.start("wf", "", ExecutionContext.triggeredBy("bob"));
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runs.find(plain.id()).orElseThrow().status() == RunStatus.WAITING);
        assertThat(runner.recoverInterruptedRuns()).isEmpty();
        server.cancel(plain.id());
    }

    @Test
    void anApprovalOnTheServerWaitsForTheRunnerEvenAfterItsLeaseExpired() throws Exception {
        catalog.saveWorkflow(WorkflowDefinition.builder("gated")
                .node(WorkflowNode.builder("gate", NodeType.APPROVAL).build())
                .node(WorkflowNode.builder("after", NodeType.SCRIPT)
                        .script("true")
                        .build())
                .chain("gate", "after")
                .build());
        server = engine("server", null, root.resolve("server"));
        runner = engine("runner-instance", "r1", root.resolve("runner"));
        Run run =
                server.start("gated", "", ExecutionContext.triggeredBy("alice").onRunner("r1"));
        runner.recoverInterruptedRuns();
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runs.find(run.id()).orElseThrow().status() == RunStatus.WAITING);
        runner.close();
        runner = null;
        Thread.sleep(2500); // the machine's lease runs out
        server.approve(run.id(), "gate", null);
        Thread.sleep(500);
        Run approved = runs.find(run.id()).orElseThrow();
        assertThat(approved.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(approved.ownerInstanceId()).isNotEqualTo("server");
        assertThat(root.resolve("server").resolve(run.id())).doesNotExist();
        // when the machine comes back it continues the run
        runner = engine("runner-instance-2", "r1", root.resolve("runner"));
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            runner.recoverInterruptedRuns();
            return runs.find(run.id()).orElseThrow().status() == RunStatus.SUCCEEDED;
        });
    }

    @Test
    void theServerNeverClaimsARunnerRunEvenWithAnExpiredLease() throws Exception {
        catalog.saveWorkflow(WorkflowDefinition.builder("slow")
                .node(WorkflowNode.builder("wait", NodeType.SCRIPT)
                        .script("sleep 30")
                        .build())
                .build());
        server = engine("server", null, root.resolve("server"));
        runner = engine("runner-instance", "r1", root.resolve("runner"));
        Run run = server.start("slow", "", ExecutionContext.triggeredBy("alice").onRunner("r1"));
        runner.recoverInterruptedRuns();
        await().atMost(Duration.ofSeconds(20))
                .until(() -> runs.find(run.id()).orElseThrow().status() == RunStatus.RUNNING
                        && "runner-instance"
                                .equals(runs.find(run.id()).orElseThrow().ownerInstanceId()));
        // the machine disappears (its lease runs out) — the server still leaves the run alone
        runner.close();
        runner = null;
        Thread.sleep(2500);
        assertThat(server.claimOrphanedRuns()).isEmpty();
        assertThat(runs.find(run.id()).orElseThrow().ownerInstanceId()).isNotEqualTo("server");
        // cancelling from the server settles it in the store
        server.cancel(run.id());
        await().atMost(Duration.ofSeconds(10))
                .until(() -> runs.find(run.id()).orElseThrow().status().terminal());
    }
}
