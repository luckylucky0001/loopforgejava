package io.github.loopforge4j.core.runtime.git;

import io.github.loopforge4j.core.domain.Run;

/** Resolves the Git credentials of a run (deployment-wide today, per responsible user later). */
@FunctionalInterface
public interface GitCredentialProvider {

    GitCredentialProvider NONE = run -> GitCredentials.NONE;

    GitCredentials credentialsFor(Run run);
}
