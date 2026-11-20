package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * An agent skill: a {@code SKILL.md} body plus optional supporting text files ({@code scripts/},
 * {@code references/}, {@code assets/}) keyed by relative path.
 */
public record SkillDefinition(
        String id,
        String name,
        String description,
        String content,
        Map<String, String> files,
        Instant updatedAt,
        String projectId)
        implements CatalogAsset {

    public SkillDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name.strip();
        content = content == null ? "" : content;
        files = files == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(files));
        projectId = projectId == null || projectId.isBlank() ? null : projectId.strip();
    }

    /** A shared skill (no owning project). */
    public SkillDefinition(
            String id, String name, String description, String content, Map<String, String> files, Instant updatedAt) {
        this(id, name, description, content, files, updatedAt, null);
    }

    @Override
    public SkillDefinition withUpdatedAt(Instant instant) {
        return new SkillDefinition(id, name, description, content, files, instant, projectId);
    }
}
