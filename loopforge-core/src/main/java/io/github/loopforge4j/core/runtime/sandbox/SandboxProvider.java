package io.github.loopforge4j.core.runtime.sandbox;

import java.io.IOException;

/** Creates and reclaims run sandboxes for a sandbox profile. */
public interface SandboxProvider {

    /** Profile ids this provider serves, e.g. {@code local}. */
    boolean supports(String profileId);

    /**
     * Returns the sandbox of a workspace (a run id, or the root run id of a child chain), creating it
     * on first use. Idempotent.
     */
    Sandbox acquire(String workspaceId) throws IOException;

    /** Deletes the workspace; no-op when it does not exist. */
    void release(String workspaceId) throws IOException;

    /**
     * Terminates processes that a previous, crashed engine left running in the workspace. Called
     * before interrupted runs are re-executed.
     *
     * @return number of process groups that were killed
     */
    default int reclaim(String workspaceId) throws IOException {
        return 0;
    }
}
