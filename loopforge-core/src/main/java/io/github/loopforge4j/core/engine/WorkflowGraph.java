package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowEdge;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Graph queries over a frozen workflow snapshot and the run state (port of upstream workflow_graph.go). */
public final class WorkflowGraph {

    private WorkflowGraph() {}

    /** Pending nodes whose dependencies all succeeded, in definition order. */
    public static List<WorkflowNode> readyNodes(Run run) {
        WorkflowDefinition workflow = run.workflow();
        Map<String, List<String>> deps = incoming(workflow);
        List<WorkflowNode> ready = new ArrayList<>();
        for (WorkflowNode node : workflow.nodes()) {
            if (status(run, node.id()) != NodeStatus.PENDING) {
                continue;
            }
            boolean satisfied = deps.getOrDefault(node.id(), List.of()).stream()
                    .allMatch(d -> status(run, d) == NodeStatus.SUCCEEDED);
            if (satisfied) {
                ready.add(node);
            }
        }
        return ready;
    }

    /** Direct predecessors of {@code nodeId}, in edge order. */
    public static List<String> sources(WorkflowDefinition workflow, String nodeId) {
        return workflow.edges().stream()
                .filter(e -> e.target().equals(nodeId))
                .map(WorkflowEdge::source)
                .toList();
    }

    /** Every node reachable backwards from {@code nodeId} (excluding itself). */
    public static Set<String> ancestors(WorkflowDefinition workflow, String nodeId) {
        return walk(incoming(workflow), nodeId);
    }

    /** Every node reachable forwards from {@code nodeId} (excluding itself). */
    public static Set<String> descendants(WorkflowDefinition workflow, String nodeId) {
        Map<String, List<String>> outgoing = new HashMap<>();
        for (WorkflowEdge edge : workflow.edges()) {
            outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }
        return walk(outgoing, nodeId);
    }

    public static boolean allSucceeded(Run run) {
        return run.workflow().nodes().stream().allMatch(n -> status(run, n.id()) == NodeStatus.SUCCEEDED);
    }

    /** First node (definition order) with the given status. */
    public static Optional<WorkflowNode> firstWithStatus(Run run, NodeStatus status) {
        return run.workflow().nodes().stream()
                .filter(n -> status(run, n.id()) == status)
                .findFirst();
    }

    /**
     * Node an operator most likely wants to resume: the first failed, cancelled or waiting node, else
     * the first pending one (upstream {@code resumeNodeID}).
     */
    public static Optional<WorkflowNode> resumeNode(Run run) {
        for (NodeStatus status : List.of(
                NodeStatus.FAILED,
                NodeStatus.CANCELLED,
                NodeStatus.WAITING_APPROVAL,
                NodeStatus.RUNNING,
                NodeStatus.PENDING)) {
            Optional<WorkflowNode> node = firstWithStatus(run, status);
            if (node.isPresent()) {
                return node;
            }
        }
        return Optional.empty();
    }

    private static NodeStatus status(Run run, String nodeId) {
        return run.node(nodeId).map(NodeRun::status).orElse(null);
    }

    private static Map<String, List<String>> incoming(WorkflowDefinition workflow) {
        Map<String, List<String>> incoming = new HashMap<>();
        for (WorkflowEdge edge : workflow.edges()) {
            incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge.source());
        }
        return incoming;
    }

    private static Set<String> walk(Map<String, List<String>> adjacency, String start) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(adjacency.getOrDefault(start, List.of()));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (seen.add(next)) {
                queue.addAll(adjacency.getOrDefault(next, List.of()));
            }
        }
        seen.remove(start);
        return seen;
    }
}
