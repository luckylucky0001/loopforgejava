package io.github.loopforge4j.core.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * Durable snapshots of run workspaces (upstream {@code run_workspace_checkpoints}): one per
 * workspace owner, replaced on every save and deleted with the run.
 */
public interface WorkspaceSnapshotStore {

    /** What a snapshot holds. */
    record Info(String runId, long size, int files, long bytes, String sha256, Instant savedAt) {}

    /** Reads a stored snapshot. */
    @FunctionalInterface
    interface Reader<T> {
        T read(InputStream in) throws IOException;
    }

    /** Stores the zip at {@code archive} (a file outside every workspace) as the snapshot of {@code runId}. */
    void save(String runId, Path archive, int files, long bytes, String sha256) throws IOException;

    Optional<Info> info(String runId);

    /** Hands the snapshot to {@code reader}; empty when there is none. */
    <T> Optional<T> read(String runId, Reader<T> reader) throws IOException;

    void delete(String runId);

    /** Snapshots are not kept: released workspaces are gone. */
    WorkspaceSnapshotStore NONE = new WorkspaceSnapshotStore() {
        @Override
        public void save(String runId, Path archive, int files, long bytes, String sha256) {}

        @Override
        public Optional<Info> info(String runId) {
            return Optional.empty();
        }

        @Override
        public <T> Optional<T> read(String runId, Reader<T> reader) {
            return Optional.empty();
        }

        @Override
        public void delete(String runId) {}

        @Override
        public boolean enabled() {
            return false;
        }
    };

    /** Whether released workspaces are snapshotted at all. */
    default boolean enabled() {
        return true;
    }
}
