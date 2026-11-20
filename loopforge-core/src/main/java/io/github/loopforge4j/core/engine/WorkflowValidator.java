package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowEdge;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation of an SOP (port of upstream {@code validate}/{@code validateNode}/{@code
 * validateDAG}). Unlike upstream it reports every problem at once so the designer can show them
 * together.
 */
public final class WorkflowValidator {

    private WorkflowValidator() {}

    public static void validate(WorkflowDefinition workflow) {
        List<String> problems = problems(workflow);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
    }

    public static List<String> problems(WorkflowDefinition workflow) {
        List<String> problems = new ArrayList<>();
        if (workflow.id().isBlank()) {
            problems.add("workflow id is required");
        }
        if (workflow.sandboxProfileId() == null || workflow.sandboxProfileId().isBlank()) {
            problems.add("workflow sandbox profile is required");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (WorkflowNode node : workflow.nodes()) {
            if (node.id().isBlank()) {
                problems.add("node id is required");
                continue;
            }
            if (!ids.add(node.id())) {
                problems.add("duplicate node " + node.id());
            }
            validateNode(workflow.id(), node, problems);
        }
        for (WorkflowNode node : workflow.nodes()) {
            if (node.rejectTo() != null && !ids.contains(node.rejectTo())) {
                problems.add("node " + node.id() + " reject target '" + node.rejectTo() + "' not found");
            }
            if (node.onFailure() == FailurePolicy.REJECT && node.rejectTo() == null) {
                problems.add("node " + node.id() + " has onFailure=reject but no rejectTo target");
            }
        }
        validateDag(ids, workflow.edges(), problems);
        return problems;
    }

    private static void validateNode(String workflowId, WorkflowNode node, List<String> problems) {
        String id = node.id();
        if (node.type() == null) {
            problems.add("node " + id + " type is required");
            return;
        }
        switch (node.type()) {
            case AGENT -> {
                if (blank(node.agentId())) {
                    problems.add("node " + id + " agentId is required");
                }
            }
            case APPROVAL -> {
                // approval nodes only need an optional task prompt
            }
            case SOP -> {
                if (blank(node.sopWorkflowId())) {
                    problems.add("node " + id + " sopWorkflowId is required");
                } else if (node.sopWorkflowId().trim().equals(workflowId)) {
                    problems.add("node " + id + " cannot reference its own workflow");
                }
            }
            case SCRIPT -> {
                if (blank(node.script())) {
                    problems.add("node " + id + " script is required");
                }
            }
        }
        if (node.timeoutSeconds() != null && node.timeoutSeconds() <= 0) {
            problems.add("node " + id + " timeoutSeconds must be positive");
        }
        if (node.maxAttempts() < 0 || node.maxReworks() < 0) {
            problems.add("node " + id + " attempt and rework budgets must not be negative");
        }
        Set<String> names = new HashSet<>();
        for (ArtifactContract contract : node.artifactOutputs()) {
            if (contract.name().isBlank() || !safeRelativePath(contract.path())) {
                problems.add("node " + id + " has invalid artifact contract '" + contract.name() + "'");
            }
            if (!names.add(contract.name())) {
                problems.add("node " + id + " has duplicate artifact " + contract.name());
            }
        }
    }

    /** Relative, normalized path that stays inside the workspace. */
    public static boolean safeRelativePath(String path) {
        if (blank(path)) {
            return false;
        }
        try {
            Path candidate = Path.of(path).normalize();
            String text = candidate.toString();
            return !candidate.isAbsolute()
                    && !text.isEmpty()
                    && !text.equals(".")
                    && !text.startsWith("..")
                    && !path.startsWith("/")
                    && !path.contains("\\");
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Kahn's algorithm: rejects dangling edges and cycles (rework must use {@code rejectTo}). */
    private static void validateDag(Set<String> ids, List<WorkflowEdge> edges, List<String> problems) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            if (!ids.contains(edge.source()) || !ids.contains(edge.target())) {
                problems.add("edge " + edge.id() + " references a missing node");
                return;
            }
            indegree.merge(edge.target(), 1, Integer::sum);
            adjacency.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge.target());
        }
        Deque<String> queue = new ArrayDeque<>();
        for (String id : ids) {
            if (indegree.getOrDefault(id, 0) == 0) {
                queue.add(id);
            }
        }
        int seen = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            seen++;
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (seen != ids.size()) {
            problems.add("workflow must be a DAG; use rejectTo for controlled rework");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
