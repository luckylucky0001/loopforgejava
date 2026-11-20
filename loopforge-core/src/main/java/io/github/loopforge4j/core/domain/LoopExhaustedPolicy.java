package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What happens when an agent self-correction loop runs out of iterations. */
public enum LoopExhaustedPolicy implements WireEnum {
    FAIL("fail"),
    HUMAN("human");

    private final String wire;

    LoopExhaustedPolicy(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static LoopExhaustedPolicy fromWire(String value) {
        return WireEnum.parse(LoopExhaustedPolicy.class, value);
    }
}
