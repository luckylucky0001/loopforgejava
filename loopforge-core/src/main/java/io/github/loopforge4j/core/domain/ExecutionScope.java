package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where a script or gate command runs: once at the workspace root, or once per bound repository. */
public enum ExecutionScope implements WireEnum {
    RUN("run"),
    REPOSITORIES("repositories");

    private final String wire;

    ExecutionScope(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ExecutionScope fromWire(String value) {
        return WireEnum.parse(ExecutionScope.class, value);
    }
}
