package io.github.loopforge4j.core.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshots in memory (tests and embedded use). */
public final class InMemoryWorkspaceSnapshotStore implements WorkspaceSnapshotStore {

    private record Stored(Info info, byte[] data) {}

    private final Map<String, Stored> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(String runId, Path archive, int files, long bytes, String sha256) throws IOException {
        byte[] data = Files.readAllBytes(archive);
        snapshots.put(runId, new Stored(new Info(runId, data.length, files, bytes, sha256, Instant.now()), data));
    }

    @Override
    public Optional<Info> info(String runId) {
        return Optional.ofNullable(snapshots.get(runId)).map(Stored::info);
    }

    @Override
    public <T> Optional<T> read(String runId, Reader<T> reader) throws IOException {
        Stored stored = snapshots.get(runId);
        if (stored == null) {
            return Optional.empty();
        }
        try (ByteArrayInputStream in = new ByteArrayInputStream(stored.data())) {
            return Optional.ofNullable(reader.read(in));
        }
    }

    @Override
    public void delete(String runId) {
        snapshots.remove(runId);
    }
}
