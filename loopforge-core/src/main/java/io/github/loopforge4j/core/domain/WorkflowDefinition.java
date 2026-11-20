package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * An SOP: a DAG of nodes plus controlled rework edges ({@link WorkflowNode#rejectTo()}). A copy is
 * frozen into every run so later edits never change an in-flight execution.
 */
public record WorkflowDefinition(
        String id,
        String name,
        int version,
        int revision,
        WorkflowStatus status,
        String projectId,
        String owner,
        List<String> tags,
        String description,
        String sandboxProfileId,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        Instant createdAt,
        Instant updatedAt,
        PreviewContract preview,
        String sopLane) {

    public static final String DEFAULT_SANDBOX_PROFILE = "local";

    public WorkflowDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name;
        version = Math.max(1, version);
        status = status == null ? WorkflowStatus.DRAFT : status;
        tags = tags == null ? List.of() : List.copyOf(tags);
        sandboxProfileId =
                sandboxProfileId == null || sandboxProfileId.isBlank() ? DEFAULT_SANDBOX_PROFILE : sandboxProfileId;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        // a disabled contract is the same as none
        preview = preview == null || !preview.enabled() ? null : preview;
        // the role-chain tag is controlled: unknown values are stored as the normalized lane
        sopLane = sopLane == null || sopLane.isBlank() ? null : SopLanes.normalize(sopLane);
    }

    /** A copy with other nodes and edges (the preview build step is attached or removed this way). */
    public WorkflowDefinition withGraph(List<WorkflowNode> newNodes, List<WorkflowEdge> newEdges) {
        return new WorkflowDefinition(
                id,
                name,
                version,
                revision,
                status,
                projectId,
                owner,
                tags,
                description,
                sandboxProfileId,
                newNodes,
                newEdges,
                createdAt,
                updatedAt,
                preview,
                sopLane);
    }

    public Optional<WorkflowNode> node(String nodeId) {
        return nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst();
    }

    public WorkflowNode requireNode(String nodeId) {
        return node(nodeId).orElseThrow(() -> new IllegalArgumentException("node '" + nodeId + "' not found"));
    }

    /** Returns a copy stamped with the next revision and timestamps, as done on every catalog write. */
    public WorkflowDefinition nextRevision(Instant now) {
        return new WorkflowDefinition(
                id,
                name,
                version,
                revision + 1,
                status,
                projectId,
                owner,
                tags,
                description,
                sandboxProfileId,
                nodes,
                edges,
                createdAt == null ? now : createdAt,
                now,
                preview,
                sopLane);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Fluent builder for tests and catalog code. */
    public static final class Builder {
        private final String id;
        private String name;
        private String description;
        private WorkflowStatus status = WorkflowStatus.PUBLISHED;
        private String sandboxProfileId;
        private PreviewContract preview;
        private String sopLane;
        private final List<WorkflowNode> nodes = new ArrayList<>();
        private final List<WorkflowEdge> edges = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder status(WorkflowStatus value) {
            this.status = value;
            return this;
        }

        public Builder sandbox(String value) {
            this.sandboxProfileId = value;
            return this;
        }

        public Builder sopLane(String value) {
            this.sopLane = value;
            return this;
        }

        public Builder preview(PreviewContract value) {
            this.preview = value;
            return this;
        }

        public Builder node(WorkflowNode node) {
            nodes.add(node);
            return this;
        }

        public Builder edge(String source, String target) {
            edges.add(WorkflowEdge.of(source, target));
            return this;
        }

        /** Chains the given node ids with success edges in order. */
        public Builder chain(String... ids) {
            for (int i = 1; i < ids.length; i++) {
                edge(ids[i - 1], ids[i]);
            }
            return this;
        }

        public WorkflowDefinition build() {
            return new WorkflowDefinition(
                    id,
                    name,
                    1,
                    1,
                    status,
                    null,
                    null,
                    List.of(),
                    description,
                    sandboxProfileId,
                    nodes,
                    edges,
                    null,
                    null,
                    preview,
                    sopLane);
        }
    }
}
