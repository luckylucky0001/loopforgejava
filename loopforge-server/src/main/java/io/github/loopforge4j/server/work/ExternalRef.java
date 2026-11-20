package io.github.loopforge4j.server.work;

import java.time.Instant;
import java.util.List;

/**
 * Where a requirement came from or was pushed to (upstream {@code ExternalRef}): a GitHub or
 * GitLab/工蜂 issue, or a TAPD story, bug or task.
 *
 * @param source {@code github}, {@code gitlab} or {@code tapd}
 * @param kind {@code issue}, {@code story}, {@code bug} or {@code task}
 * @param externalId the tracker's own id
 * @param number the number people see (issue number / IID; 0 for TAPD)
 * @param repositoryId the catalog repository of an issue
 * @param workspaceId the TAPD workspace
 * @param imported added by hand (TAPD link import or push), so pool reconciliation keeps it
 * @param notified pull request URLs already written back to the tracker
 */
public record ExternalRef(
        String source,
        String kind,
        String externalId,
        int number,
        String url,
        String state,
        String repositoryId,
        String workspaceId,
        boolean imported,
        Instant syncedAt,
        List<String> notified) {

    public ExternalRef {
        notified = notified == null ? List.of() : List.copyOf(notified);
    }

    /** Unique per project: one local requirement per external item. */
    public String key() {
        return source + ":" + (repositoryId != null ? repositoryId : workspaceId) + ":" + kind + ":" + externalId;
    }

    public ExternalRef refreshed(String newUrl, String newState, Instant at) {
        return new ExternalRef(
                source,
                kind,
                externalId,
                number,
                newUrl == null ? url : newUrl,
                newState,
                repositoryId,
                workspaceId,
                imported,
                at,
                notified);
    }

    public ExternalRef markImported() {
        return new ExternalRef(
                source, kind, externalId, number, url, state, repositoryId, workspaceId, true, syncedAt, notified);
    }

    public ExternalRef withNotified(List<String> urls) {
        return new ExternalRef(
                source, kind, externalId, number, url, state, repositoryId, workspaceId, imported, syncedAt, urls);
    }
}
