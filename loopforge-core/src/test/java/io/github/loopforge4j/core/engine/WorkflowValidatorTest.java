package io.github.loopforge4j.core.engine;

import static io.github.loopforge4j.core.domain.NodeType.AGENT;
import static io.github.loopforge4j.core.domain.NodeType.SCRIPT;
import static io.github.loopforge4j.core.domain.NodeType.SOP;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkflowValidatorTest {

    @Test
    void acceptsValidDag() {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", SCRIPT).script("true").build())
                .node(WorkflowNode.builder("b", AGENT)
                        .agent("dev")
                        .rejectTo("a")
                        .build())
                .chain("a", "b")
                .build();
        assertThat(WorkflowValidator.problems(wf)).isEmpty();
    }

    @Test
    void reportsEveryProblemAtOnce() {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", SCRIPT).build())
                .node(WorkflowNode.builder("a", AGENT).build())
                .node(WorkflowNode.builder("c", AGENT)
                        .agent("x")
                        .onFailure(FailurePolicy.REJECT)
                        .build())
                .node(WorkflowNode.builder("d", AGENT)
                        .agent("x")
                        .rejectTo("ghost")
                        .build())
                .node(WorkflowNode.builder("e", SOP).sopWorkflow("wf").build())
                .build();
        assertThat(WorkflowValidator.problems(wf))
                .anyMatch(p -> p.contains("script is required"))
                .anyMatch(p -> p.contains("duplicate node a"))
                .anyMatch(p -> p.contains("agentId is required"))
                .anyMatch(p -> p.contains("no rejectTo target"))
                .anyMatch(p -> p.contains("'ghost' not found"))
                .anyMatch(p -> p.contains("its own workflow"));
    }

    @Test
    void rejectsCyclesAndDanglingEdges() {
        WorkflowDefinition cyclic = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", SCRIPT).script("true").build())
                .node(WorkflowNode.builder("b", SCRIPT).script("true").build())
                .chain("a", "b", "a")
                .build();
        assertThat(WorkflowValidator.problems(cyclic)).anyMatch(p -> p.contains("DAG"));
        WorkflowDefinition dangling = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", SCRIPT).script("true").build())
                .edge("a", "missing")
                .build();
        assertThat(WorkflowValidator.problems(dangling)).anyMatch(p -> p.contains("missing node"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/etc/passwd", "../escape.md", ".", "", "a\\b.md"})
    void rejectsUnsafeArtifactPaths(String path) {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", SCRIPT)
                        .script("true")
                        .artifacts(new ArtifactContract("out", path, null, true))
                        .build())
                .build();
        assertThat(WorkflowValidator.problems(wf)).anyMatch(p -> p.contains("invalid artifact contract"));
    }

    @Test
    void acceptsNestedRelativeArtifactPaths() {
        assertThat(WorkflowValidator.safeRelativePath("reports/2026/summary.md"))
                .isTrue();
        assertThat(WorkflowValidator.safeRelativePath("a/../b.md")).isTrue();
        assertThat(WorkflowValidator.safeRelativePath("a/../../b.md")).isFalse();
    }

    @Test
    void enforcesIdentifierAndLengthLimits() {
        String longId = "a".repeat(201);
        WorkflowDefinition wf = WorkflowDefinition.builder(longId)
                .name("名".repeat(501))
                .node(WorkflowNode.builder("ok", SCRIPT).script("true").build())
                .node(WorkflowNode.builder("has space", SCRIPT).script("true").build())
                .node(WorkflowNode.builder("ref", AGENT).agent("bad id").build())
                .build();
        assertThat(WorkflowValidator.problems(wf))
                .anyMatch(p -> p.startsWith("workflow id") && p.contains("200 characters"))
                .anyMatch(p -> p.contains("workflow name must be at most 500"))
                .anyMatch(p -> p.startsWith("node id 'has space'"))
                .anyMatch(p -> p.contains("references an invalid id 'bad id'"));
        assertThat(WorkflowValidator.problems(WorkflowDefinition.builder("a".repeat(200))
                        .node(WorkflowNode.builder("n", SCRIPT).script("true").build())
                        .build()))
                .isEmpty();
    }

    @Test
    void validatesAgents() {
        assertThat(WorkflowValidator.agentProblems(io.github.loopforge4j.core.domain.AgentDefinition.builder("")
                        .build()))
                .contains("agent id is required");
        assertThat(WorkflowValidator.agentProblems(io.github.loopforge4j.core.domain.AgentDefinition.builder("dev")
                        .artifacts(java.util.List.of(new ArtifactContract("x", "../escape.md", null, true)))
                        .build()))
                .anyMatch(p -> p.contains("invalid artifact contract"));
        assertThat(WorkflowValidator.agentProblems(io.github.loopforge4j.core.domain.AgentDefinition.builder("dev")
                        .prompt("p")
                        .build()))
                .isEmpty();
    }
}
