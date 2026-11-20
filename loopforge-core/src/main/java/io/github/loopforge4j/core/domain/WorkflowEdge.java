package io.github.loopforge4j.core.domain;

/** Dependency edge: {@code target} starts once {@code source} succeeded. */
public record WorkflowEdge(String id, String source, String target, String when) {

    public static final String WHEN_SUCCESS = "success";

    public WorkflowEdge {
        source = source == null ? "" : source;
        target = target == null ? "" : target;
        id = id == null || id.isBlank() ? source + "->" + target : id;
        when = when == null || when.isBlank() ? WHEN_SUCCESS : when;
    }

    public static WorkflowEdge of(String source, String target) {
        return new WorkflowEdge(null, source, target, WHEN_SUCCESS);
    }
}
