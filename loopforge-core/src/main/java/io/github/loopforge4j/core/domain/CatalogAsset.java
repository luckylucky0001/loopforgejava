package io.github.loopforge4j.core.domain;

import java.time.Instant;

/** A reusable definition a role binds by id: rules, skills and MCP servers. */
public interface CatalogAsset {

    String id();

    String name();

    Instant updatedAt();

    CatalogAsset withUpdatedAt(Instant instant);
}
