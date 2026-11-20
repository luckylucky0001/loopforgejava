package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionTest {

    @TempDir
    Path root;

    @Test
    void oldTelemetryAndRunsAreRemovedButActiveRunsStay() throws Exception {
        try (EngineFixture f = new EngineFixture(root, new InMemoryRunStore(), false)) {
            f.workflow(WorkflowDefinition.builder("wf")
                    .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                            .script("echo hi")
                            .build())
                    .build());
            f.workflow(WorkflowDefinition.builder("wait")
                    .node(WorkflowNode.builder("gate", NodeType.APPROVAL).build())
                    .build());
            Run done = f.start("wf");
            f.awaitStatus(done.id(), RunStatus.SUCCEEDED);
            Run waiting = f.start("wait");
            f.awaitStatus(waiting.id(), RunStatus.WAITING);
            f.engine.emit(done.id(), "a", Telemetry.of("output", "agent.output", "progress", "chunk"));
            Path workspace = Path.of(f.engine.require(done.id()).workspaceRoot());
            assertThat(workspace).exists();
            Thread.sleep(30);

            RunEngine.RetentionResult telemetryOnly = f.engine.applyRetention(Duration.ofMillis(10), Duration.ZERO);
            assertThat(telemetryOnly.prunedEvents()).isPositive();
            assertThat(f.eventTypes(done.id())).contains("run.succeeded").doesNotContain("agent.output");

            RunEngine.RetentionResult runs = f.engine.applyRetention(Duration.ZERO, Duration.ofMillis(10));
            assertThat(runs.deletedRuns()).isEqualTo(1);
            assertThat(f.runs.find(done.id())).isEmpty();
            assertThat(workspace).doesNotExist();
            assertThat(f.runs.find(waiting.id())).isPresent();
            assertThat(Files.exists(root)).isTrue();
        }
    }
}
