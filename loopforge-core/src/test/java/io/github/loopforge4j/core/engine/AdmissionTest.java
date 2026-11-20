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
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdmissionTest {

    @TempDir
    Path root;

    @Test
    void runsBeyondTheLimitsQueueAndStartAsSlotsFree() {
        InMemoryRunStore runs = new InMemoryRunStore();
        InMemoryCatalogStore catalog = new InMemoryCatalogStore();
        catalog.saveWorkflow(WorkflowDefinition.builder("gate")
                .node(WorkflowNode.builder("approve", NodeType.APPROVAL).build())
                .node(WorkflowNode.builder("after", NodeType.SCRIPT)
                        .script("sleep 0.3")
                        .build())
                .chain("approve", "after")
                .build());
        catalog.saveWorkflow(WorkflowDefinition.builder("quick")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                        .script("sleep 0.5")
                        .build())
                .build());
        try (RunEngine engine = RunEngine.builder()
                .runStore(runs)
                .catalogStore(catalog)
                .executors(ExecutorRegistry.withBuiltIns(CredentialProvider.NONE))
                .sandbox(new LocalSandboxProvider(LocalSandboxOptions.defaults(root)))
                .settings(new EngineSettings("adm", "mock", Duration.ofMinutes(1), Duration.ofMillis(20), true))
                .admission(new AdmissionPolicy(2, 1))
                .build()) {
            Run p1 = engine.start("quick", "", new ExecutionContext("p", "t", null, null, null));
            Run p2 = engine.start("quick", "", new ExecutionContext("p", "t", null, null, null));
            Run q1 = engine.start("quick", "", new ExecutionContext("q", "t", null, null, null));
            Run r1 = engine.start("quick", "", new ExecutionContext("r", "t", null, null, null));
            assertThat(p1.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(p2.status()).as("per-project limit").isEqualTo(RunStatus.QUEUED);
            assertThat(q1.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(r1.status()).as("deployment limit").isEqualTo(RunStatus.QUEUED);
            assertThat(engine.events(p2.id(), 0, 0)).extracting(e -> e.type()).containsExactly("run.queued");

            for (Run run : java.util.List.of(p1, p2, q1, r1)) {
                await().atMost(Duration.ofSeconds(20))
                        .until(() -> engine.require(run.id()).status() == RunStatus.SUCCEEDED);
            }
            assertThat(engine.events(p2.id(), 0, 0)).extracting(e -> e.type()).contains("run.queued", "run.started");

            // a run waiting for a person does not hold a slot
            Run waiting = engine.start("gate", "", new ExecutionContext("p", "t", null, null, null));
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> engine.require(waiting.id()).status() == RunStatus.WAITING);
            Run next = engine.start("quick", "", new ExecutionContext("p", "t", null, null, null));
            assertThat(next.status()).isEqualTo(RunStatus.RUNNING);

            // cancelling a queued run settles it without ever running it
            Run blocker = engine.start("quick", "", new ExecutionContext("s", "t", null, null, null));
            Run queued = engine.start("quick", "", new ExecutionContext("s", "t", null, null, null));
            assertThat(queued.status()).isEqualTo(RunStatus.QUEUED);
            assertThat(engine.cancel(queued.id()).status()).isEqualTo(RunStatus.CANCELLED);
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> engine.require(blocker.id()).status() == RunStatus.SUCCEEDED);
            assertThat(engine.require(queued.id()).status()).isEqualTo(RunStatus.CANCELLED);
        }
    }
}
