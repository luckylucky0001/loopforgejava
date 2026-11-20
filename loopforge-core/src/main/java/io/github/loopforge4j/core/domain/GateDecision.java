package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Terminal decision an agent submits for its node. */
public enum GateDecision implements WireEnum {
    PASS("pass"),
    REWORK("rework"),
    FAIL("fail"),
    HUMAN("human");

    private final String wire;

    GateDecision(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static GateDecision fromWire(String value) {
        return WireEnum.parse(GateDecision.class, value);
    }
}
