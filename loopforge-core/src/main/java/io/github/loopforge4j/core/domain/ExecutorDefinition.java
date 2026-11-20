package io.github.loopforge4j.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * A coding-agent CLI. {@code kind} selects the provider adapter (argument conventions and stream
 * protocol); {@code binary} and {@code args} describe how it is launched.
 */
public record ExecutorDefinition(
        String id,
        String name,
        String kind,
        String binary,
        List<String> args,
        String protocol,
        String model,
        String effort) {

    public ExecutorDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        args = args == null ? List.of() : List.copyOf(args);
        name = name == null || name.isBlank() ? id : name;
    }

    public ExecutorDefinition withModel(String newModel, String newEffort) {
        return new ExecutorDefinition(id, name, kind, binary, args, protocol, newModel, newEffort);
    }
}
