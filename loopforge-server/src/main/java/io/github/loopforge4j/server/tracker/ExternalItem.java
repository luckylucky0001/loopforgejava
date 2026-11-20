package io.github.loopforge4j.server.tracker;

import java.time.Instant;

/**
 * One work item of an external tracker.
 *
 * @param open the tracker considers it open (GitHub/GitLab {@code open(ed)}; TAPD: not closed)
 * @param status the tracker's own status text
 * @param priority local priority ({@code low}…{@code urgent})
 */
public record ExternalItem(
        String kind,
        String externalId,
        int number,
        String title,
        String description,
        boolean open,
        String status,
        String priority,
        String url,
        String owner,
        Instant updatedAt) {}
