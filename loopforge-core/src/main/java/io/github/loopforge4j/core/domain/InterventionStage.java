package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Lifecycle point at which an agent-owned human checkpoint applies. */
public enum InterventionStage implements WireEnum {
    BEFORE_EXECUTION("before_execution"),
    AFTER_EXECUTION("after_execution"),
    BEFORE_PUBLISH("before_publish"),
    ON_FAILURE("on_failure");

    private final String wire;

    InterventionStage(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static InterventionStage fromWire(String value) {
        return WireEnum.parse(InterventionStage.class, value);
    }
}
