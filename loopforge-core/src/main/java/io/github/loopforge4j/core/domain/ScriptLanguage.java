package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Interpreter of a script node. */
public enum ScriptLanguage implements WireEnum {
    SHELL("shell"),
    PYTHON("python");

    private final String wire;

    ScriptLanguage(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ScriptLanguage fromWire(String value) {
        return WireEnum.parse(ScriptLanguage.class, value);
    }
}
