package io.github.loopforge4j.core.domain;

import java.time.Instant;

/**
 * A reusable rule. Rules bound to a role are written into its system instructions, highest priority
 * first (upstream {@code RuleDefinition}).
 */
public record RuleDefinition(
        String id, String name, String description, String content, int priority, Instant updatedAt)
        implements CatalogAsset {

    public RuleDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name.strip();
        content = content == null ? "" : content;
    }

    @Override
    public RuleDefinition withUpdatedAt(Instant instant) {
        return new RuleDefinition(id, name, description, content, priority, instant);
    }
}
