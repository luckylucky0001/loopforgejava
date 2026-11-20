package io.github.loopforge4j.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.engine.WorkflowValidator;
import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Workflow definitions exported from upstream LoopForge load unchanged. */
class UpstreamCompatibilityTest {

    @Test
    void loadsUpstreamWelcomeWorkflow() throws IOException {
        WorkflowDefinition workflow = Json.read(resource("/upstream/welcome-workflow.json"), WorkflowDefinition.class);

        assertThat(workflow.id()).isEqualTo("welcome-workflow");
        assertThat(workflow.status()).isEqualTo(WorkflowStatus.PUBLISHED);
        assertThat(workflow.nodes())
                .extracting(WorkflowNode::type)
                .containsExactly(NodeType.SCRIPT, NodeType.APPROVAL, NodeType.SCRIPT);
        assertThat(workflow.requireNode("approve").rejectTo()).isEqualTo("prepare");
        assertThat(workflow.requireNode("prepare").artifactOutputs())
                .singleElement()
                .extracting(ArtifactContract::path)
                .isEqualTo("reports/welcome.md");
        assertThat(workflow.edges()).hasSize(2);
        assertThat(WorkflowValidator.problems(workflow)).isEmpty();
    }

    @Test
    void roundTripKeepsWireValues() {
        WorkflowDefinition workflow = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", NodeType.AGENT)
                        .agent("dev")
                        .onFailure(FailurePolicy.REJECT)
                        .rejectTo("a0")
                        .retryStrategy(RetryStrategy.FULL_REDO)
                        .build())
                .build();
        String json = Json.write(workflow);
        assertThat(json).contains("\"type\":\"agent\"", "\"onFailure\":\"reject\"", "\"retryStrategy\":\"full_redo\"");
        assertThat(Json.read(json, WorkflowDefinition.class)).isEqualTo(workflow);
    }

    @Test
    void runAggregateRoundTrips() {
        WorkflowDefinition workflow = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT).script("true").build())
                .build();
        Run run = Run.create("run-1", workflow, "input", ExecutionContext.triggeredBy("me"), java.time.Instant.EPOCH);
        run.requireNode("a").gateResult(GateResult.pass("ok"));
        Run copy = Json.copy(run, Run.class);
        assertThat(copy.requireNode("a").gateResult().decision()).isEqualTo(GateDecision.PASS);
        assertThat(copy.workflow()).isEqualTo(workflow);
        assertThat(copy.context().triggeredBy()).isEqualTo("me");
        assertThat(Json.write(copy)).doesNotContain("hasActiveNode");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = UpstreamCompatibilityTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
