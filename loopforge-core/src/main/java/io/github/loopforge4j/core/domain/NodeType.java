package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Kind of a workflow node. */
public enum NodeType implements WireEnum {
    AGENT("agent"),
    SCRIPT("script"),
    APPROVAL("approval"),
    SOP("sop"),
    /** Builds (optionally) and archives a static site for review (upstream preview build). */
    PREVIEW("preview");

    private final String wire;

    NodeType(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static NodeType fromWire(String value) {
        return WireEnum.parse(NodeType.class, value);
    }
}
