package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Aggregate status of a run. */
public enum RunStatus implements WireEnum {
    /** Persisted but not yet claimed by a scheduler. */
    QUEUED("queued"),
    /** A scheduler is executing ready nodes. */
    RUNNING("running"),
    /** Parked at a human decision point (approval, pause, exhausted budget). */
    WAITING("waiting"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String wire;

    RunStatus(String wire) {
        this.wire = wire;
    }

    /** Succeeded, failed and cancelled runs have no scheduler; only operator actions reopen them. */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** Running and waiting runs still belong to the live pipeline. */
    public boolean live() {
        return this == RUNNING || this == WAITING;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RunStatus fromWire(String value) {
        return WireEnum.parse(RunStatus.class, value);
    }
}
