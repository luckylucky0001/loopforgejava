package io.github.loopforge4j.core.domain;

import java.time.Instant;

/**
 * A pull/merge request delivering a run repository (upstream {@code DeliveryMR}).
 *
 * @param state {@code open}, {@code merged} or {@code closed}
 */
public record PullRequest(
        String repositoryId,
        int number,
        String url,
        String state,
        String headBranch,
        String baseBranch,
        String headSha,
        Instant mergedAt,
        Instant updatedAt) {

    public boolean open() {
        return "open".equals(state);
    }

    public boolean merged() {
        return "merged".equals(state);
    }
}
