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
}
