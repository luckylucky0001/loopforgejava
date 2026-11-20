package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.TokenUsage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compact list view of a run (no workflow snapshot). */
public record RunSummary(
        String id,
        String workflowId,
        String workflowName,
        RunStatus status,
        String parentRunId,
        Map<String, NodeStatus> nodes,
        TokenUsage usage,
        Instant createdAt,
        Instant updatedAt) {

    static RunSummary of(Run run) {
        Map<String, NodeStatus> nodes = new LinkedHashMap<>();
        for (NodeRun node : run.nodeRuns()) {
            nodes.put(node.nodeId(), node.status());
        }
        return new RunSummary(
                run.id(),
                run.workflowId(),
                run.workflow().name(),
                run.status(),
                run.context().parentRunId(),
                nodes,
                run.usage(),
                run.createdAt(),
                run.updatedAt());
    }
}
