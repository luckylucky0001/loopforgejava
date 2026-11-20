package io.github.loopforge4j.core.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a repository is reached. {@code github} additionally enables PR delivery through the GitHub
 * API; {@code https} and {@code ssh} are plain Git remotes (clone and push only), like upstream's
 * Git-only providers.
 */
public enum RepositoryProvider implements WireEnum {
    GITHUB("github"),
    HTTPS("https"),
    SSH("ssh"),
    /** Local path or file:// URL; used for tests and air-gapped mirrors. */
    LOCAL("local");

    private final String wire;

    RepositoryProvider(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RepositoryProvider fromWire(String value) {
        return WireEnum.parse(RepositoryProvider.class, value);
    }

    /** Hosting API actions (PR/MR) are available. */
    public boolean hostsPullRequests() {
        return this == GITHUB;
    }
}
