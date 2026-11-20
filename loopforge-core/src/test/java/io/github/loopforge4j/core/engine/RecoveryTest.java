package io.github.loopforge4j.core.engine;

import static io.github.loopforge4j.core.domain.NodeType.AGENT;
import static io.github.loopforge4j.core.domain.NodeType.SCRIPT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A restarted engine continues runs that were executing when the previous process stopped. */
class RecoveryTest {

    @TempDir
    Path root;

    @Test
    void shutdownParksAndRestartResumes() {
        InMemoryRunStore runs = new InMemoryRunStore();
        WorkflowDefinition wf = WorkflowDefinition.builder("slow")
                .node(WorkflowNode.builder("work", SCRIPT)
                        .script("if [ -f .second ]; then echo resumed; else touch .second; sleep 30; fi")
                        .build())
                .build();
        String runId;
        try (EngineFixture first = new EngineFixture(root, runs, false)) {
            first.workflow(wf);
            runId = first.start("slow").id();
            first.awaitNode(runId, "work", NodeStatus.RUNNING);
            first.awaitWorkspaceFile(runId, ".second");
        }
        Run parked = runs.find(runId).orElseThrow();
        assertThat(parked.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(parked.requireNode("work").status()).isEqualTo(NodeStatus.PENDING);

        try (EngineFixture second = new EngineFixture(root, runs, false)) {
            assertThat(second.engine.recoverInterruptedRuns()).containsExactly(runId);
            Run done = second.awaitStatus(runId, RunStatus.SUCCEEDED);
            assertThat(done.requireNode("work").output()).contains("resumed");
            assertThat(second.eventTypes(runId)).contains("run.parked", "run.recovered");
        }
    }

    @Test
    void interruptedAgentContinuesSameAttemptAndSession() {
        InMemoryRunStore runs = new InMemoryRunStore();
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("implement", AGENT).agent("dev").build())
                .build();
        Run crashed = Run.create("run-crashed", wf, "x", ExecutionContext.EMPTY, Instant.now());
        crashed.status(RunStatus.RUNNING);
        crashed.requireNode("implement").status(NodeStatus.RUNNING);
        crashed.requireNode("implement").attempt(1);
        crashed.requireNode("implement").sessionId("mock-dev-run-crashed");
        runs.insert(crashed, List.of());

        try (EngineFixture fixture = new EngineFixture(root, runs, false)) {
            fixture.agent(
                    AgentDefinition.builder("dev").executor("mock").prompt("实现").build());
            fixture.engine.recoverInterruptedRuns();
            Run done = fixture.awaitStatus("run-crashed", RunStatus.SUCCEEDED);
            assertThat(done.requireNode("implement").attempt()).isEqualTo(1);
            assertThat(done.generation()).isEqualTo(1);
        }
    }
}
