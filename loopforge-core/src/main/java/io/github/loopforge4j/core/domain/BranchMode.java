package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a run treats the work branch of a repository (upstream {@code branchMode}). */
public enum BranchMode implements WireEnum {
    /** Reuse the remote work branch when it exists, else create it from the base branch. */
    AUTO("auto"),
    /** Continue an existing remote branch; fail when it is missing. */
    CONTINUE("continue"),
    /** Create a new branch; fail when a remote branch of that name exists. */
    NEW("new"),
    /** Check the base branch out without a work branch; never commit or push. */
    READ_ONLY("read_only");

    private final String wire;

    BranchMode(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static BranchMode fromWire(String value) {
        return WireEnum.parse(BranchMode.class, value);
    }
}
