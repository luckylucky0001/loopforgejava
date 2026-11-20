package io.github.loopforge4j.core.eval;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/** Builds the facts of a settled run (upstream {@code BuildFacts}) and the catalog it may change. */
public final class RunEvalFacts {

    static final int DIGEST = 120;
    static final int TOP = 10;

    private RunEvalFacts() {}

    public static RunEvaluation.Facts build(Run run, List<RunEvent> events, List<Artifact> artifacts) {
        Map<String, Integer> starts = new LinkedHashMap<>();
        Map<String, Integer> gates = new LinkedHashMap<>();
        Map<String, int[]> tools = new LinkedHashMap<>();
        Map<String, int[]> errors = new LinkedHashMap<>();
        Map<String, Integer> types = new TreeMap<>();
        List<String> started = new ArrayList<>();
        List<RunEvaluation.DigestEvent> digest = new ArrayList<>();
        for (RunEvent e : events) {
            types.merge(e.category() + "/" + e.type(), 1, Integer::sum);
            String node = e.nodeId() == null ? "" : e.nodeId();
            if ("node.started".equals(e.type())) {
                starts.merge(node, 1, Integer::sum);
                started.add(node);
            }
            if (e.type() != null && e.type().startsWith("gate.")) {
                gates.merge(node, 1, Integer::sum);
            }
            Object tool = e.attributes().get("tool");
            if ("tool".equals(e.category())
                    && tool != null
                    && !"start".equals(e.attributes().get("phase"))) {
                int[] counts = tools.computeIfAbsent(node + "\u0000" + tool, k -> new int[2]);
                counts[0]++;
                if ("error".equals(e.level())
                        || Boolean.FALSE.equals(e.attributes().get("success"))) {
                    counts[1]++;
                }
            }
            boolean error = "error".equals(e.level());
            if (error && e.message() != null) {
                int[] count = errors.computeIfAbsent(
                        node + "\u0000" + clip(e.message().strip(), 200), k -> new int[1]);
                count[0]++;
            }
            if (keep(e) && digest.size() < DIGEST) {
                digest.add(new RunEvaluation.DigestEvent(
                        e.sequence(), e.nodeId(), e.category(), e.type(), e.level(), clip(e.message(), 300)));
            }
        }
        Map<String, Long> artifactsByNode = new LinkedHashMap<>();
        artifacts.forEach(a -> artifactsByNode.merge(a.nodeId() == null ? "" : a.nodeId(), 1L, Long::sum));
        List<RunEvaluation.NodeFact> nodes = new ArrayList<>();
        long exec = 0;
        for (WorkflowNode n : run.workflow().nodes()) {
            Optional<NodeRun> state = run.nodeRuns().stream()
                    .filter(r -> r.nodeId().equals(n.id()))
                    .findFirst();
            long duration = state.filter(s -> s.startedAt() != null && s.finishedAt() != null)
                    .map(s -> Duration.between(s.startedAt(), s.finishedAt()).toMillis())
                    .orElse(0L);
            exec += duration;
            nodes.add(new RunEvaluation.NodeFact(
                    n.id(),
                    n.name(),
                    n.type() == null ? null : n.type().wire(),
                    n.agentId(),
                    state.map(s -> s.status().wire()).orElse("pending"),
                    state.map(NodeRun::attempt).orElse(0),
                    state.map(NodeRun::reworkCount).orElse(0),
                    n.maxAttempts(),
                    n.maxReworks(),
                    state.map(NodeRun::toolCalls).orElse(0),
                    duration,
                    state.map(NodeRun::usage).orElse(TokenUsage.ZERO),
                    state.map(NodeRun::error).map(x -> clip(x, 500)).orElse(null),
                    starts.getOrDefault(n.id(), 0),
                    artifactsByNode.getOrDefault(n.id(), 0L).intValue(),
                    gates.getOrDefault(n.id(), 0)));
        }
        List<RunEvaluation.ToolRepeat> repeats = tools.entrySet().stream()
                .filter(e -> e.getValue()[0] > 1 || e.getValue()[1] > 0)
                .map(e -> {
                    String[] key = e.getKey().split("\u0000", 2);
                    return new RunEvaluation.ToolRepeat(
                            key[0].isEmpty() ? null : key[0], key[1], e.getValue()[0], e.getValue()[1]);
                })
                .sorted(Comparator.comparingInt(RunEvaluation.ToolRepeat::failed)
                        .thenComparingInt(RunEvaluation.ToolRepeat::count)
                        .reversed())
                .limit(TOP)
                .toList();
        List<RunEvaluation.ErrorFact> errorFacts = errors.entrySet().stream()
                .map(e -> {
                    String[] key = e.getKey().split("\u0000", 2);
                    return new RunEvaluation.ErrorFact(key[0].isEmpty() ? null : key[0], key[1], e.getValue()[0]);
                })
                .sorted(Comparator.comparingInt(RunEvaluation.ErrorFact::count).reversed())
                .limit(TOP)
                .toList();
        long wall = run.status().terminal() && run.updatedAt() != null && run.createdAt() != null
                ? Duration.between(run.createdAt(), run.updatedAt()).toMillis()
                : 0;
        return new RunEvaluation.Facts(
                run.id(),
                run.workflowId(),
                run.workflow().name(),
                run.status().wire(),
                clip(run.input(), 2000),
                run.context().projectId(),
                wall,
                exec,
                run.usage(),
                run.toolCalls(),
                artifacts.size(),
                events.size(),
                nodes,
                repeats,
                errorFacts,
                pingPong(started),
                types,
                digest);
    }

    /** A→B→A sequences of node starts: rework or rejection loops. */
    static List<String> pingPong(List<String> started) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 2; i < started.size(); i++) {
            String a = started.get(i - 2);
            String b = started.get(i - 1);
            if (a.equals(started.get(i)) && !a.equals(b)) {
                out.add(a + "→" + b + "→" + a);
            }
        }
        return List.copyOf(out);
    }

    /** Lifecycle, gate, error and failing tool events make the digest; plain output does not. */
    private static boolean keep(RunEvent e) {
        if ("error".equals(e.level()) || "warn".equals(e.level())) {
            return true;
        }
        String type = e.type() == null ? "" : e.type();
        return RunEvent.LIFECYCLE.equals(e.category())
                || type.startsWith("gate.")
                || type.startsWith("node.")
                || Boolean.FALSE.equals(e.attributes().get("success"));
    }

    /** The current definitions the evaluator may propose changes to. */
    public static RunEvaluation.Catalog catalog(
            io.github.loopforge4j.core.domain.WorkflowDefinition current,
            Function<String, Optional<AgentDefinition>> agents) {
        List<RunEvaluation.CatalogNode> nodes = new ArrayList<>();
        List<RunEvaluation.CatalogAgent> roles = new ArrayList<>();
        LinkedHashSet<String> agentIds = new LinkedHashSet<>();
        for (WorkflowNode n : current.nodes()) {
            nodes.add(new RunEvaluation.CatalogNode(
                    n.id(),
                    n.name(),
                    n.type() == null ? null : n.type().wire(),
                    n.agentId(),
                    n.maxAttempts(),
                    n.maxReworks(),
                    n.onFailure() == null ? null : n.onFailure().wire(),
                    n.retryStrategy() == null ? null : n.retryStrategy().wire()));
            if (n.agentId() != null) {
                agentIds.add(n.agentId());
            }
        }
        for (String id : agentIds) {
            agents.apply(id)
                    .ifPresent(a -> roles.add(new RunEvaluation.CatalogAgent(
                            a.id(), a.name(), clip(a.prompt(), 3000), a.verification())));
        }
        return new RunEvaluation.Catalog(nodes, roles);
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
