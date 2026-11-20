package io.github.loopforge4j.server.ops;

import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Engine metrics (upstream analytics, as Micrometer meters exported at {@code /actuator/prometheus}):
 *
 * <ul>
 *   <li>{@code loopforge.runs.finished{status,workflow}}, {@code loopforge.runs.active}
 *   <li>{@code loopforge.nodes.finished{type,status}}, timer {@code loopforge.node.duration{type,status}}
 *   <li>{@code loopforge.node.reworks{type}}, {@code loopforge.gate.decisions{decision}}, {@code
 *       loopforge.gate.checks{result}}
 *   <li>{@code loopforge.tokens{direction}}, {@code loopforge.cost.usd}
 * </ul>
 *
 * Each instance reports the transitions it observes; workflow tags use the catalog id (bounded).
 */
@Component
public class RunMetrics implements RunListener {

    private final MeterRegistry registry;
    /** Last observed node states per active run, to count transitions once. */
    private final Map<String, Map<String, NodeSnapshot>> nodes = new ConcurrentHashMap<>();

    private final Map<String, RunStatus> runs = new ConcurrentHashMap<>();

    private record NodeSnapshot(NodeStatus status, int reworks) {}

    public RunMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void runChanged(Run run) {
        RunStatus previous = runs.put(run.id(), run.status());
        Map<String, NodeSnapshot> seen = nodes.computeIfAbsent(run.id(), id -> new ConcurrentHashMap<>());
        for (NodeRun nr : run.nodeRuns()) {
            NodeSnapshot before = seen.put(nr.nodeId(), new NodeSnapshot(nr.status(), nr.reworkCount()));
            String type =
                    run.workflow().node(nr.nodeId()).map(n -> n.type().wire()).orElse("unknown");
            if (before != null && nr.reworkCount() > before.reworks()) {
                registry.counter("loopforge.node.reworks", "type", type).increment(nr.reworkCount() - before.reworks());
            }
            boolean finished = nr.status() == NodeStatus.SUCCEEDED || nr.status() == NodeStatus.FAILED;
            if (finished && (before == null || before.status() != nr.status())) {
                String status = nr.status() == NodeStatus.SUCCEEDED ? "succeeded" : "failed";
                registry.counter("loopforge.nodes.finished", "type", type, "status", status)
                        .increment();
                if (nr.startedAt() != null && nr.finishedAt() != null && before != null) {
                    Timer.builder("loopforge.node.duration")
                            .tags("type", type, "status", status)
                            .publishPercentileHistogram()
                            .register(registry)
                            .record(Duration.between(nr.startedAt(), nr.finishedAt()));
                }
            }
        }
        if (run.status().terminal()) {
            if (previous != null && previous != run.status()) {
                registry.counter(
                                "loopforge.runs.finished",
                                "status",
                                run.status().wire(),
                                "workflow",
                                run.workflowId())
                        .increment();
            }
            nodes.remove(run.id());
            runs.remove(run.id());
        }
    }

    @Override
    public void eventsAppended(String runId, List<RunEvent> events) {
        for (RunEvent event : events) {
            switch (event.type()) {
                case "gate.submit.received" ->
                    registry.counter(
                                    "loopforge.gate.decisions",
                                    "decision",
                                    String.valueOf(event.attributes().get("decision")))
                            .increment();
                case "gate.passed", "gate.mr.passed" ->
                    registry.counter("loopforge.gate.checks", "result", "passed")
                            .increment();
                case "gate.failed", "gate.mr.failed" ->
                    registry.counter("loopforge.gate.checks", "result", "failed")
                            .increment();
                case "usage.final" -> {
                    count(
                            "loopforge.tokens",
                            "direction",
                            "input",
                            event.attributes().get("inputTokens"));
                    count(
                            "loopforge.tokens",
                            "direction",
                            "output",
                            event.attributes().get("outputTokens"));
                    if (event.attributes().get("costUsd") instanceof Number cost && cost.doubleValue() > 0) {
                        Counter.builder("loopforge.cost.usd").register(registry).increment(cost.doubleValue());
                    }
                }
                default -> {}
            }
        }
    }

    private void count(String name, String tag, String value, Object amount) {
        if (amount instanceof Number n && n.longValue() > 0) {
            registry.counter(name, tag, value).increment(n.doubleValue());
        }
    }
}
