package io.github.loopforge4j.server.persistence;

import java.time.Instant;

/**
 * One saved state of an SOP or role definition (upstream {@code DefinitionRevision}).
 *
 * @param kind {@code workflow} or {@code agent}
 * @param summary what changed against the previous revision
 * @param payload the definition as saved; omitted in listings
 */
public record DefinitionRevision(
        String kind,
        String entityId,
        int revision,
        String name,
        String author,
        String summary,
        Object payload,
        Instant createdAt) {

    public static final String WORKFLOW = "workflow";
    public static final String AGENT = "agent";

    /** Revisions kept per definition; older ones are dropped on write. */
    public static final int KEEP = 100;
}
