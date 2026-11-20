package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Status of one node inside a run. */
public enum NodeStatus implements WireEnum {
    PENDING("pending"),
    RUNNING("running"),
    WAITING_APPROVAL("waiting_approval"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wire;

    NodeStatus(String wire) {
        this.wire = wire;
    }

    /** A node that occupies an executor right now. */
    public boolean active() {
        return this == RUNNING;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static NodeStatus fromWire(String value) {
        return WireEnum.parse(NodeStatus.class, value);
    }
}
