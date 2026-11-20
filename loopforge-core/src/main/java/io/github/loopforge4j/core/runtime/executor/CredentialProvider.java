package io.github.loopforge4j.core.runtime.executor;

import java.util.Map;

/**
 * Supplies the environment variables (API keys, base URLs) an executor kind needs. Secrets live only
 * here and in the launched process environment; they are never written to run state or logs.
 */
@FunctionalInterface
public interface CredentialProvider {

    CredentialProvider NONE = kind -> Map.of();

    Map<String, String> environmentFor(String executorKind);

    /**
     * The environment for work done for {@code responsible} (who started the run or speaks in the
     * conversation, as recorded; {@code null} when nobody is): their own credentials over the
     * deployment's.
     */
    default Map<String, String> environmentFor(String executorKind, String responsible) {
        return environmentFor(executorKind);
    }
}
