package io.github.loopforge4j.server.workspace;

import io.github.loopforge4j.core.store.WorkspaceSnapshotStore;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Workspace snapshots in the database; archives are streamed in and out, never held whole. */
@Repository
public class JdbcWorkspaceSnapshotStore implements WorkspaceSnapshotStore {

    private final JdbcTemplate template;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;

    public JdbcWorkspaceSnapshotStore(
            JdbcTemplate template,
            JdbcClient jdbc,
            TransactionTemplate tx,
            Clock clock,
            LoopForgeProperties properties) {
        this.template = template;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.enabled = properties.engine().snapshots();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void save(String runId, Path archive, int files, long bytes, String sha256) throws IOException {
        long size = Files.size(archive);
        try {
            tx.executeWithoutResult(status -> {
                template.update("DELETE FROM run_workspace_snapshots WHERE run_id = ?", runId);
                template.update(
                        "INSERT INTO run_workspace_snapshots (run_id, archive, size_bytes, files, content, sha256,"
                                + " saved_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        ps -> {
                            ps.setString(1, runId);
                            try {
                                ps.setBinaryStream(2, Files.newInputStream(archive), size);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                            ps.setLong(3, size);
                            ps.setInt(4, files);
                            ps.setLong(5, bytes);
                            ps.setString(6, sha256);
                            ps.setObject(7, SqlTime.of(clock.instant()));
                        });
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public Optional<Info> info(String runId) {
        return jdbc.sql("SELECT run_id, size_bytes, files, content, sha256, saved_at FROM run_workspace_snapshots"
                        + " WHERE run_id = :run")
                .param("run", runId)
                .query((rs, i) -> new Info(
                        rs.getString("run_id"),
                        rs.getLong("size_bytes"),
                        rs.getInt("files"),
                        rs.getLong("content"),
                        rs.getString("sha256"),
                        SqlTime.read(rs, "saved_at")))
                .optional();
    }

    @Override
    public <T> Optional<T> read(String runId, Reader<T> reader) throws IOException {
        try {
            return tx.execute(status -> template.query(
                    "SELECT archive FROM run_workspace_snapshots WHERE run_id = ?",
                    rs -> {
                        if (!rs.next()) {
                            return Optional.<T>empty();
                        }
                        try (InputStream in = rs.getBinaryStream(1)) {
                            return Optional.ofNullable(reader.read(in));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    },
                    runId));
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public void delete(String runId) {
        jdbc.sql("DELETE FROM run_workspace_snapshots WHERE run_id = :run")
                .param("run", runId)
                .update();
    }
}
