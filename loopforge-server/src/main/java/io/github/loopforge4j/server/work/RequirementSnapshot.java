package io.github.loopforge4j.server.work;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.SopLanes;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowEdge;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A requirement with its SOP lanes, their node stages and the totals (upstream {@code
 * RequirementSnapshot}): one lane per bound SOP plus lanes for SOPs that ran without a binding.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequirementSnapshot(Requirement requirement, Overall overall, List<Lane> lanes, List<Stage> stages) {

    static final int MAX_DEPTH = 6;

    /** A node of a lane's SOP, with the child SOP's nodes for an SOP node. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NodeStage(
            String nodeId, String name, String status, Long durationMs, String childRunId, List<NodeStage> children) {}

    /** One SOP lane of the requirement. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lane(
            String bindingId,
            String lane,
            String label,
            String workflowId,
            String workflowName,
            String latestRunId,
            String status,
            boolean started,
            int runCount,
            int failedRuns,
            long tokens,
            double costUsd,
            long executionDurationMs,
            String assignee,
            Integer order,
            List<String> dependsOn,
            List<String> handoff,
            boolean orphan,
            List<NodeStage> stages) {}

    /** The requirement's lifecycle as the primary lane's nodes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stage(String key, String label, String status, String hint) {}

    /** Totals over the requirement's runs (child runs add usage but not time). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Overall(
            int runCount,
            int failedRuns,
            int succeededLanes,
            int activeLanes,
            long tokens,
            double costUsd,
            long executionDurationMs,
            long cycleDurationMs,
            Instant firstRunAt,
            Instant lastRunAt,
            String status) {}

    /** What the builder reads. */
    interface Source {
        Optional<Run> run(String runId);

        Optional<WorkflowDefinition> workflow(String workflowId);
    }

    static RequirementSnapshot build(Requirement requirement, List<Run> runs, Source source, Instant now) {
        Map<String, Run> children = new HashMap<>();
        Function<String, Optional<Run>> child = id -> {
            if (children.containsKey(id)) {
                return Optional.ofNullable(children.get(id));
            }
            Optional<Run> found = source.run(id);
            children.put(id, found.orElse(null));
            return found;
        };
        List<Lane> lanes = lanes(requirement, runs, source, child, now);
        return new RequirementSnapshot(
                requirement, overall(requirement, lanes, runs, child), lanes, lifecycle(requirement, lanes));
    }

    // ------------------------------------------------------------------ lanes

    private static final class Acc {
        String workflowId;
        String workflowName;
        String lane;
        boolean started;
        int runCount;
        int failedRuns;
        long tokens;
        double cost;
        long duration;
        Run latest;
    }

    static List<Lane> lanes(
            Requirement requirement,
            List<Run> runs,
            Source source,
            Function<String, Optional<Run>> child,
            Instant now) {
        Map<String, Acc> byWorkflow = new LinkedHashMap<>();
        for (Run run : runs) {
            Acc acc = byWorkflow.computeIfAbsent(run.workflowId(), id -> {
                Acc a = new Acc();
                a.workflowId = id;
                return a;
            });
            WorkflowDefinition workflow = run.workflow();
            acc.started = true;
            acc.runCount++;
            acc.tokens += run.usage().totalTokens() + childTokens(run, child, 0);
            acc.cost += run.usage().costUsd() + childCost(run, child, 0);
            acc.duration += executionMillis(run, now);
            if (run.status() == RunStatus.FAILED) {
                acc.failedRuns++;
            }
            if (acc.latest == null || !run.createdAt().isBefore(acc.latest.createdAt())) {
                acc.latest = run;
                acc.workflowName = workflow.name();
                acc.lane = SopLanes.infer(workflow);
            }
        }
        List<SopBinding> bindings = requirement.effectiveBindings();
        List<Lane> out = new ArrayList<>();
        Set<String> bound = new HashSet<>();
        for (SopBinding binding : bindings) {
            bound.add(binding.workflowId());
            Acc acc = byWorkflow.get(binding.workflowId());
            Optional<WorkflowDefinition> workflow = source.workflow(binding.workflowId());
            out.add(lane(binding, acc, workflow, source, child, now, false));
        }
        // a single binding with one started SOP is one slot, even when the SOP differs (upstream)
        if (bindings.size() == 1) {
            List<Acc> started =
                    byWorkflow.values().stream().filter(a -> a.started).toList();
            if (started.size() == 1
                    && !started.getFirst().workflowId.equals(bindings.getFirst().workflowId())) {
                return List.of(lane(
                        bindings.getFirst(),
                        started.getFirst(),
                        source.workflow(started.getFirst().workflowId),
                        source,
                        child,
                        now,
                        false));
            }
        }
        byWorkflow.values().stream()
                .filter(a -> a.started && !bound.contains(a.workflowId))
                .sorted(Comparator.comparing(a -> a.workflowName == null ? a.workflowId : a.workflowName))
                .forEach(a -> out.add(lane(null, a, source.workflow(a.workflowId), source, child, now, true)));
        return out;
    }

    private static Lane lane(
            SopBinding binding,
            Acc acc,
            Optional<WorkflowDefinition> catalog,
            Source source,
            Function<String, Optional<Run>> child,
            Instant now,
            boolean orphan) {
        String workflowId = acc != null ? acc.workflowId : binding.workflowId();
        WorkflowDefinition definition =
                acc != null && acc.latest != null ? acc.latest.workflow() : catalog.orElse(null);
        String name = definition != null
                        && definition.name() != null
                        && !definition.name().isBlank()
                ? definition.name()
                : workflowId;
        String laneTag = definition != null ? SopLanes.infer(definition) : SopLanes.OTHER;
        String status;
        List<NodeStage> stages;
        if (acc != null && acc.latest != null) {
            stages = runStages(acc.latest, source, child, now, 0);
            status = laneStatus(acc.latest);
            if (status.equals("active") && waitsForHuman(stages)) {
                status = "waiting";
            }
        } else {
            status = "not_started";
            stages = catalog.map(w -> plannedStages(w, source, 0)).orElse(List.of());
        }
        return new Lane(
                binding == null ? null : binding.id(),
                laneTag,
                name,
                workflowId,
                name,
                acc == null || acc.latest == null ? null : acc.latest.id(),
                status,
                acc != null && acc.started,
                acc == null ? 0 : acc.runCount,
                acc == null ? 0 : acc.failedRuns,
                acc == null ? 0 : acc.tokens,
                acc == null ? 0 : acc.cost,
                acc == null ? 0 : acc.duration,
                binding == null ? null : binding.assignee(),
                binding == null ? null : binding.order(),
                binding == null ? null : binding.dependsOn(),
                binding == null ? null : binding.handoff(),
                orphan,
                stages);
    }

    static String laneStatus(Run run) {
        for (NodeRun nr : run.nodeRuns()) {
            if (nr.status() == NodeStatus.FAILED) {
                return "attention";
            }
            if (nr.status() == NodeStatus.WAITING_APPROVAL) {
                return "waiting";
            }
        }
        return switch (run.status()) {
            case RUNNING, QUEUED -> "active";
            case WAITING -> "waiting";
            case SUCCEEDED -> "succeeded";
            case FAILED, CANCELLED -> "attention";
        };
    }

    private static boolean waitsForHuman(List<NodeStage> stages) {
        for (NodeStage s : stages) {
            if ("waiting_approval".equals(s.status()) || s.children() != null && waitsForHuman(s.children())) {
                return true;
            }
        }
        return false;
    }

    /** Nodes in execution order: a stable topological sort over the edges (upstream). */
    static List<WorkflowNode> ordered(WorkflowDefinition workflow) {
        List<WorkflowNode> nodes = workflow.nodes();
        if (nodes.size() < 2 || workflow.edges().isEmpty()) {
            return nodes;
        }
        Set<String> known = new HashSet<>();
        nodes.forEach(n -> known.add(n.id()));
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> next = new HashMap<>();
        for (WorkflowEdge e : workflow.edges()) {
            if (e.source().equals(e.target()) || !known.contains(e.source()) || !known.contains(e.target())) {
                continue;
            }
            indegree.merge(e.target(), 1, Integer::sum);
            next.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
        }
        Set<String> placed = new HashSet<>();
        List<WorkflowNode> out = new ArrayList<>();
        while (out.size() < nodes.size()) {
            boolean progress = false;
            for (WorkflowNode n : nodes) {
                if (placed.contains(n.id()) || indegree.getOrDefault(n.id(), 0) > 0) {
                    continue;
                }
                out.add(n);
                placed.add(n.id());
                next.getOrDefault(n.id(), List.of()).forEach(t -> indegree.merge(t, -1, Integer::sum));
                progress = true;
                break;
            }
            if (!progress) {
                nodes.stream().filter(n -> placed.add(n.id())).forEach(out::add);
            }
        }
        return out;
    }

    static List<NodeStage> plannedStages(WorkflowDefinition workflow, Source source, int depth) {
        List<NodeStage> out = new ArrayList<>();
        for (WorkflowNode node : ordered(workflow)) {
            out.add(new NodeStage(node.id(), name(node), "pending", null, null, plannedChildren(node, source, depth)));
        }
        return out;
    }

    private static List<NodeStage> plannedChildren(WorkflowNode node, Source source, int depth) {
        if (node.type() != NodeType.SOP || node.sopWorkflowId() == null || depth >= MAX_DEPTH) {
            return null;
        }
        return source.workflow(node.sopWorkflowId())
                .map(w -> plannedStages(w, source, depth + 1))
                .orElse(null);
    }

    static List<NodeStage> runStages(
            Run run, Source source, Function<String, Optional<Run>> child, Instant now, int depth) {
        List<NodeStage> out = new ArrayList<>();
        for (WorkflowNode node : ordered(run.workflow())) {
            NodeRun nr = run.node(node.id()).orElse(null);
            String status = nr == null ? "pending" : nr.status().wire();
            Long duration = null;
            if (nr != null && nr.startedAt() != null) {
                if (nr.finishedAt() != null && nr.finishedAt().isAfter(nr.startedAt())) {
                    duration = Duration.between(nr.startedAt(), nr.finishedAt()).toMillis();
                } else if (nr.status() == NodeStatus.RUNNING && now.isAfter(nr.startedAt())) {
                    duration = Duration.between(nr.startedAt(), now).toMillis();
                }
            }
            String childRunId = null;
            List<NodeStage> children = null;
            if (depth < MAX_DEPTH && nr != null && nr.childRunId() != null) {
                Optional<Run> childRun = child.apply(nr.childRunId());
                if (childRun.isPresent()) {
                    childRunId = childRun.get().id();
                    children = runStages(childRun.get(), source, child, now, depth + 1);
                }
            }
            if (children == null) {
                children = plannedChildren(node, source, depth);
            }
            out.add(new NodeStage(node.id(), name(node), status, duration, childRunId, children));
        }
        return out;
    }

    private static String name(WorkflowNode node) {
        return node.name() == null || node.name().isBlank() ? node.id() : node.name();
    }

    private static long childTokens(Run run, Function<String, Optional<Run>> child, int depth) {
        if (depth > MAX_DEPTH) {
            return 0;
        }
        long total = 0;
        for (NodeRun nr : run.nodeRuns()) {
            if (nr.childRunId() != null) {
                Optional<Run> c = child.apply(nr.childRunId());
                if (c.isPresent()) {
                    total += c.get().usage().totalTokens() + childTokens(c.get(), child, depth + 1);
                }
            }
        }
        return total;
    }

    private static double childCost(Run run, Function<String, Optional<Run>> child, int depth) {
        if (depth > MAX_DEPTH) {
            return 0;
        }
        double total = 0;
        for (NodeRun nr : run.nodeRuns()) {
            if (nr.childRunId() != null) {
                Optional<Run> c = child.apply(nr.childRunId());
                if (c.isPresent()) {
                    total += c.get().usage().costUsd() + childCost(c.get(), child, depth + 1);
                }
            }
        }
        return total;
    }

    /** Time the run's nodes spent executing (overlapping parallel nodes counted once). */
    public static long executionMillis(Run run, Instant now) {
        Instant start = null;
        Instant end = null;
        for (NodeRun nr : run.nodeRuns()) {
            if (nr.startedAt() == null) {
                continue;
            }
            start = start == null || nr.startedAt().isBefore(start) ? nr.startedAt() : start;
            Instant finished =
                    nr.finishedAt() != null ? nr.finishedAt() : (nr.status() == NodeStatus.RUNNING ? now : null);
            if (finished != null && (end == null || finished.isAfter(end))) {
                end = finished;
            }
        }
        return start == null || end == null || !end.isAfter(start)
                ? 0
                : Duration.between(start, end).toMillis();
    }

    // ------------------------------------------------------------------ overall

    static Overall overall(
            Requirement requirement, List<Lane> lanes, List<Run> runs, Function<String, Optional<Run>> child) {
        int failed = 0;
        long tokens = 0;
        double cost = 0;
        long execution = 0;
        Instant first = null;
        Instant last = null;
        for (Run run : runs) {
            tokens += run.usage().totalTokens() + childTokens(run, child, 0);
            cost += run.usage().costUsd() + childCost(run, child, 0);
            execution += executionMillis(run, Instant.now());
            if (run.status() == RunStatus.FAILED) {
                failed++;
            }
            first = first == null || run.createdAt().isBefore(first) ? run.createdAt() : first;
            Instant ended = run.status().terminal() ? run.updatedAt() : Instant.now();
            last = last == null || ended.isAfter(last) ? ended : last;
        }
        int succeeded = 0;
        int active = 0;
        for (Lane lane : lanes) {
            switch (lane.status()) {
                case "succeeded" -> succeeded++;
                case "active", "waiting" -> active++;
                default -> {}
            }
        }
        long cycle = first != null && last != null && last.isAfter(first)
                ? Duration.between(first, last).toMillis()
                : 0;
        return new Overall(
                runs.size(),
                failed,
                succeeded,
                active,
                tokens,
                cost,
                execution,
                cycle,
                first,
                last,
                status(requirement, lanes));
    }

    static String status(Requirement requirement, List<Lane> lanes) {
        if (List.of("closed", "cancelled", "done").contains(requirement.status())) {
            return "closed";
        }
        List<String> rank = List.of("attention", "waiting", "active", "succeeded", "closed", "not_started");
        String best = "not_started";
        int started = 0;
        for (Lane lane : lanes) {
            if (lane.started()) {
                started++;
            }
            int r = rank.indexOf(lane.status());
            if (r >= 0 && r < rank.indexOf(best)) {
                best = lane.status();
            }
        }
        return started == 0 ? "not_started" : best;
    }

    static List<Stage> lifecycle(Requirement requirement, List<Lane> lanes) {
        Lane primary = null;
        String want = requirement.workflowId();
        for (Lane lane : lanes) {
            if (!lane.orphan() && want != null && want.equals(lane.workflowId())) {
                primary = lane;
                break;
            }
        }
        if (primary == null) {
            primary = lanes.stream()
                    .filter(l -> !l.orphan())
                    .findFirst()
                    .orElse(lanes.isEmpty() ? null : lanes.getFirst());
        }
        if (primary == null) {
            return List.of();
        }
        if (!primary.stages().isEmpty()) {
            return primary.stages().stream()
                    .map(s -> new Stage(s.nodeId(), s.name(), s.status(), null))
                    .toList();
        }
        return List.of(new Stage(primary.workflowId(), primary.label(), primary.status(), primary.workflowName()));
    }
}
