package io.github.loopforge4j.core.domain;

import java.util.Objects;

/**
 * Declared output of a node. Paths are relative to the run workspace root; Markdown is the default
 * interchange format.
 */
public record ArtifactContract(String name, String path, String mediaType, boolean required) {

    public static final String DEFAULT_MEDIA_TYPE = "text/markdown";

    public ArtifactContract {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        mediaType = mediaType == null || mediaType.isBlank() ? DEFAULT_MEDIA_TYPE : mediaType.trim();
    }

    public static ArtifactContract required(String name, String path) {
        return new ArtifactContract(name, path, DEFAULT_MEDIA_TYPE, true);
    }

    /** Key used when an SOP node refines a contract inherited from its agent. */
    public String mergeKey() {
        return name.isBlank() ? path.trim() : name.trim();
    }
}
