package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * A container image recipe (upstream {@code DockerfileDefinition}): the Dockerfile and the files of
 * its build context, keyed by relative path. Sandbox profiles with the {@code docker} driver build
 * and run it.
 */
public record DockerfileDefinition(
        String id, String name, String description, String content, Map<String, String> files, Instant updatedAt)
        implements CatalogAsset {

    public DockerfileDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name.strip();
        content = content == null ? "" : content;
        files = files == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(files));
    }

    @Override
    public DockerfileDefinition withUpdatedAt(Instant instant) {
        return new DockerfileDefinition(id, name, description, content, files, instant);
    }
}
