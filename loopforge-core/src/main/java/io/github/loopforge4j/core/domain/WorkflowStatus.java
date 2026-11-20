package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Publication state of a workflow definition. */
public enum WorkflowStatus implements WireEnum {
    DRAFT("draft"),
    PUBLISHED("published"),
    ARCHIVED("archived");

    private final String wire;

    WorkflowStatus(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static WorkflowStatus fromWire(String value) {
        return WireEnum.parse(WorkflowStatus.class, value);
    }
}
