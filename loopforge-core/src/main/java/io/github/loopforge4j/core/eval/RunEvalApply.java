package io.github.loopforge4j.core.eval;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.RetryStrategy;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.json.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a recommendation to the current definition (upstream {@code ApplyRecommendation}); never
 * to the run's frozen snapshot. Only the bounded changes of the contract are possible.
 */
public final class RunEvalApply {

    private static final Set<String> NODE_FIELDS =
            Set.of("maxAttempts", "maxReworks", "onFailure", "retryStrategy", "timeoutSeconds");

    private RunEvalApply() {}

    public static WorkflowDefinition apply(RunEvaluation.Recommendation rec, WorkflowDefinition workflow) {
        if (!"update_node".equals(rec.action())) {
            throw new IllegalArgumentException("action " + rec.action() + " changes a role, not the SOP");
        }
        String nodeId = rec.nodeId() != null ? rec.nodeId() : rec.targetId();
        WorkflowNode node = workflow.node(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("node '" + nodeId + "' is not in the current SOP"));
        if (rec.set().isEmpty() || !NODE_FIELDS.containsAll(rec.set().keySet())) {
            throw new IllegalArgumentException("update_node may only set " + NODE_FIELDS);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = Json.mapper().convertValue(node, Map.class);
        java.util.Map<String, Object> changed = new java.util.LinkedHashMap<>(fields);
        rec.set().forEach((key, value) -> {
            switch (key) {
                case "maxAttempts", "maxReworks" -> changed.put(key, bounded(value, key, 0, 20));
                case "timeoutSeconds" -> changed.put(key, bounded(value, key, 30, 86_400));
                case "onFailure" ->
                    changed.put(
                            key, FailurePolicy.fromWire(String.valueOf(value)).wire());
                case "retryStrategy" ->
                    changed.put(
                            key, RetryStrategy.fromWire(String.valueOf(value)).wire());
                default -> throw new IllegalArgumentException("cannot set " + key);
            }
        });
        WorkflowNode updated = Json.mapper().convertValue(changed, WorkflowNode.class);
        List<WorkflowNode> nodes = new ArrayList<>(workflow.nodes());
        nodes.set(nodes.indexOf(node), updated);
        return new WorkflowDefinition(
                workflow.id(),
                workflow.name(),
                workflow.version(),
                workflow.revision(),
                workflow.status(),
                workflow.projectId(),
                workflow.owner(),
                workflow.tags(),
                workflow.description(),
                workflow.sandboxProfileId(),
                nodes,
                workflow.edges(),
                workflow.createdAt(),
                workflow.updatedAt(),
                workflow.preview());
    }

    public static AgentDefinition apply(RunEvaluation.Recommendation rec, AgentDefinition agent) {
        List<VerificationGate> gates = new ArrayList<>(agent.verification());
        String prompt = agent.prompt();
        switch (rec.action()) {
            case "append_task_prompt" -> {
                if (rec.text() == null || rec.text().isBlank()) {
                    throw new IllegalArgumentException("append_task_prompt needs text");
                }
                if (prompt != null && prompt.contains(rec.text().strip())) {
                    throw new IllegalArgumentException("the role prompt already contains this text");
                }
                prompt = (prompt == null || prompt.isBlank() ? "" : prompt.stripTrailing() + "\n\n")
                        + rec.text().strip();
            }
            case "upsert_gate" -> {
                VerificationGate gate = rec.gate();
                if (gate == null || gate.id() == null || gate.id().isBlank()) {
                    throw new IllegalArgumentException("upsert_gate needs gate.id");
                }
                gates.removeIf(g -> g.id().equals(gate.id()));
                gates.add(gate);
            }
            case "remove_gate" -> {
                String id = rec.gate() != null ? rec.gate().id() : rec.text();
                if (id == null || !gates.removeIf(g -> g.id().equals(id))) {
                    throw new IllegalArgumentException("gate '" + id + "' is not on the role");
                }
            }
            default -> throw new IllegalArgumentException("action " + rec.action() + " changes the SOP, not a role");
        }
        return new AgentDefinition(
                agent.id(),
                agent.name(),
                agent.version(),
                agent.description(),
                prompt,
                agent.rules(),
                agent.skills(),
                agent.mcpServers(),
                agent.hooks(),
                agent.executorId(),
                agent.model(),
                agent.effort(),
                agent.loop(),
                agent.interventions(),
                agent.artifactOutputs(),
                gates,
                agent.platformMcp(),
                agent.knowledgeBase(),
                agent.updatedAt());
    }

    private static int bounded(Object value, String key, int min, int max) {
        int v;
        try {
            v = value instanceof Number n
                    ? n.intValue()
                    : Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        }
        return v;
    }
}
