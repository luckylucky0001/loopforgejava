package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tooling for a sandbox (upstream {@code EnvironmentDefinition}): variables every process gets,
 * workspace-relative directories put first on {@code PATH}, and a script run once per workspace
 * before its first command (installing tools into the workspace, for example).
 */
public record EnvironmentDefinition(
        String id,
        String name,
        String description,
        Map<String, String> variables,
        List<String> pathEntries,
        String installScript,
        Instant updatedAt)
        implements CatalogAsset {

    public EnvironmentDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name.strip();
        variables = variables == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(variables));
        pathEntries = pathEntries == null ? List.of() : List.copyOf(pathEntries);
        installScript = installScript == null || installScript.isBlank() ? null : installScript;
    }

    @Override
    public EnvironmentDefinition withUpdatedAt(Instant instant) {
        return new EnvironmentDefinition(id, name, description, variables, pathEntries, installScript, instant);
    }
}
