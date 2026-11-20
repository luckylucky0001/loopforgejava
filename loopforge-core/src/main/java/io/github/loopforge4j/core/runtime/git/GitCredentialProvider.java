package io.github.loopforge4j.core.runtime.git;

import io.github.loopforge4j.core.domain.Run;

/** Resolves the Git credentials of a run (deployment-wide today, per responsible user later). */
@FunctionalInterface
public interface GitCredentialProvider {

    GitCredentialProvider NONE = run -> GitCredentials.NONE;

    /**
     * @param run the run the credentials are for; {@code null} for work outside runs (conversations,
     *     requirement clarification)
     */
    GitCredentials credentialsFor(Run run);

    /** Credentials for work outside runs done for {@code responsible}: theirs over the deployment's. */
    default GitCredentials credentialsForUser(String responsible) {
        return credentialsFor(null);
    }

    /**
     * Only {@code responsible}'s own credentials (conversation workspaces, where the person can read
     * the environment and must not receive the deployment's tokens).
     */
    default GitCredentials personalCredentials(String responsible) {
        return GitCredentials.NONE;
    }
}
