package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What happens when a node or one of its gates fails. */
public enum FailurePolicy implements WireEnum {
    FAIL("fail"),
    REJECT("reject"),
    HUMAN("human"),
    LOOP("loop");

    private final String wire;

    FailurePolicy(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static FailurePolicy fromWire(String value) {
        return WireEnum.parse(FailurePolicy.class, value);
    }
}
