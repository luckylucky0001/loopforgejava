package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a retried agent node is asked to approach the previous failure. */
public enum RetryStrategy implements WireEnum {
    TARGETED_FIX("targeted_fix"),
    FULL_REDO("full_redo");

    private final String wire;

    RetryStrategy(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RetryStrategy fromWire(String value) {
        return WireEnum.parse(RetryStrategy.class, value);
    }
}
