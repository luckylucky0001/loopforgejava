package io.github.loopforge4j.core.domain;

import java.time.Instant;

/**
 * Immutable, attempt-scoped snapshot of a node output. Old attempts are kept for auditability;
 * downstream nodes consume the newest version.
 */
public record Artifact(
        String id,
        String runId,
        String nodeId,
        int attempt,
        String name,
        String path,
        String mediaType,
        long size,
        String sha256,
        String content,
        Instant createdAt) {

    /** Upstream limit for one artifact body. */
    public static final int MAX_BYTES = 1 << 20;

    /** Metadata-only copy for listings. */
    public Artifact withoutContent() {
        return new Artifact(id, runId, nodeId, attempt, name, path, mediaType, size, sha256, null, createdAt);
    }
}
