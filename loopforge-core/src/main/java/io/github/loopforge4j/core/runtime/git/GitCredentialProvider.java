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
}
