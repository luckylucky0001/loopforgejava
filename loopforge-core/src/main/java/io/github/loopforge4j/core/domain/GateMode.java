package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a verification gate is evaluated. */
public enum GateMode implements WireEnum {
    COMMAND("command"),
    MANUAL("manual");

    private final String wire;

    GateMode(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static GateMode fromWire(String value) {
        return WireEnum.parse(GateMode.class, value);
    }
}
