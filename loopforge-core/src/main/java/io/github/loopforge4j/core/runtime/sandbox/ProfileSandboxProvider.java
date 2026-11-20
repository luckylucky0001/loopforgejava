package io.github.loopforge4j.core.runtime.sandbox;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A catalog sandbox profile: the driver's provider plus the profile's tooling (see {@link
 * EnvironmentSandbox}).
 */
public final class ProfileSandboxProvider implements SandboxProvider {

    /** Prepares what the driver needs before the first sandbox (building a container image). */
    @FunctionalInterface
    public interface Preparation {
        void prepare() throws IOException;
    }

    private final String profileId;
    private final SandboxProvider driver;
    private final Preparation preparation;
    private final String environmentId;
    private final Map<String, String> variables;
    private final List<String> pathEntries;
    private final String installScript;

    public ProfileSandboxProvider(
            String profileId,
            SandboxProvider driver,
            Preparation preparation,
            String environmentId,
            Map<String, String> variables,
            List<String> pathEntries,
            String installScript) {
        this.profileId = profileId;
        this.driver = driver;
        this.preparation = preparation == null ? () -> {} : preparation;
        this.environmentId = environmentId;
        this.variables = Map.copyOf(variables);
        this.pathEntries = List.copyOf(pathEntries);
        this.installScript = installScript;
    }

    @Override
    public boolean supports(String id) {
        return profileId.equals(id);
    }

    @Override
    public Sandbox acquire(String workspaceId) throws IOException {
        preparation.prepare();
        Sandbox sandbox = driver.acquire(workspaceId);
        if (variables.isEmpty() && pathEntries.isEmpty() && installScript == null) {
            return sandbox;
        }
        return new EnvironmentSandbox(sandbox, environmentId, variables, pathEntries, installScript);
    }

    @Override
    public void release(String workspaceId) throws IOException {
        driver.release(workspaceId);
    }

    @Override
    public int reclaim(String workspaceId) throws IOException {
        return driver.reclaim(workspaceId);
    }
}
