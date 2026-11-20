package io.github.loopforge4j.core.engine;

import static io.github.loopforge4j.core.domain.NodeType.SCRIPT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkflowGraphTest {

    private final WorkflowDefinition diamond = WorkflowDefinition.builder("d")
            .node(WorkflowNode.builder("a", SCRIPT).script("true").build())
            .node(WorkflowNode.builder("b", SCRIPT).script("true").build())
            .node(WorkflowNode.builder("c", SCRIPT).script("true").build())
            .node(WorkflowNode.builder("d", SCRIPT).script("true").build())
            .edge("a", "b")
            .edge("a", "c")
            .edge("b", "d")
            .edge("c", "d")
            .build();

    @Test
    void readyNodesFollowSucceededDependencies() {
        Run run = Run.create("r", diamond, "", ExecutionContext.EMPTY, Instant.EPOCH);
        assertThat(WorkflowGraph.readyNodes(run)).extracting(WorkflowNode::id).containsExactly("a");
        run.requireNode("a").status(NodeStatus.SUCCEEDED);
        assertThat(WorkflowGraph.readyNodes(run)).extracting(WorkflowNode::id).containsExactly("b", "c");
        run.requireNode("b").status(NodeStatus.SUCCEEDED);
        assertThat(WorkflowGraph.readyNodes(run)).extracting(WorkflowNode::id).containsExactly("c");
        run.requireNode("c").status(NodeStatus.SUCCEEDED);
        assertThat(WorkflowGraph.readyNodes(run)).extracting(WorkflowNode::id).containsExactly("d");
    }

    @Test
    void ancestorsAndDescendants() {
        assertThat(WorkflowGraph.descendants(diamond, "a")).containsExactlyInAnyOrder("b", "c", "d");
        assertThat(WorkflowGraph.descendants(diamond, "b")).containsExactly("d");
        assertThat(WorkflowGraph.ancestors(diamond, "d")).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(WorkflowGraph.sources(diamond, "d")).containsExactly("b", "c");
    }

    @Test
    void resumeNodePrefersFailures() {
        Run run = Run.create("r", diamond, "", ExecutionContext.EMPTY, Instant.EPOCH);
        run.requireNode("a").status(NodeStatus.SUCCEEDED);
        run.requireNode("c").status(NodeStatus.FAILED);
        assertThat(WorkflowGraph.resumeNode(run))
                .get()
                .extracting(WorkflowNode::id)
                .isEqualTo("c");
    }
}
