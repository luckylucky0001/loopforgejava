package io.github.loopforge4j.core.delivery;

import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import java.util.List;

/** Queries pull requests of run repositories on their hosting platform. */
public interface DeliveryProvider {

    DeliveryProvider NONE = new DeliveryProvider() {
        @Override
        public boolean supports(RepositoryContext repository) {
            return false;
        }

        @Override
        public List<PullRequest> pullRequests(RepositoryContext repository, String head, String base) {
            return List.of();
        }
    };

    /** Whether this provider can query pull requests of the repository. */
    boolean supports(RepositoryContext repository);

    /** Pull requests from {@code head} into {@code base}, newest first, any state. */
    List<PullRequest> pullRequests(RepositoryContext repository, String head, String base) throws DeliveryException;
}
