package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.domain.PreviewSettings;
import io.github.loopforge4j.core.store.PreviewStore;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Preview sites in the database, so every instance can serve them and they go with their run. */
@Repository
public class JdbcPreviewStore implements PreviewStore {

    /** What a run's preview is, without its files. */
    public record Preview(
            String runId,
            String nodeId,
            String path,
            String index,
            boolean spa,
            int files,
            long bytes,
            Instant createdAt) {}

    /** Receives the files of a preview one at a time. */
    @FunctionalInterface
    public interface FileConsumer {
        void accept(String path, byte[] content) throws IOException;
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JdbcPreviewStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public boolean save(String runId, String nodeId, PreviewSettings settings, List<File> files) {
        long bytes = files.stream().mapToLong(f -> f.content().length).sum();
        tx.executeWithoutResult(status -> {
            // the newest preview of the run replaces the previous one (a rework rebuilds it)
            jdbc.sql("DELETE FROM run_previews WHERE run_id = :run")
                    .param("run", runId)
                    .update();
            jdbc.sql("INSERT INTO run_previews (run_id, node_id, site_path, index_file, spa, file_count, total_bytes,"
                            + " created_at) VALUES (:run, :node, :path, :index, :spa, :count, :bytes, :now)")
                    .param("run", runId)
                    .param("node", nodeId)
                    .param("path", settings.path())
                    .param("index", settings.index())
                    .param("spa", settings.spa())
                    .param("count", files.size())
                    .param("bytes", bytes)
                    .param("now", SqlTime.of(clock.instant()))
                    .update();
            for (File file : files) {
                jdbc.sql("INSERT INTO run_preview_files (run_id, path, content) VALUES (:run, :path, :content)")
                        .param("run", runId)
                        .param("path", file.path())
                        .param("content", file.content())
                        .update();
            }
        });
        return true;
    }

    public Optional<Preview> find(String runId) {
        return jdbc.sql("SELECT run_id, node_id, site_path, index_file, spa, file_count, total_bytes, created_at"
                        + " FROM run_previews WHERE run_id = :run")
                .param("run", runId)
                .query((rs, n) -> new Preview(
                        rs.getString("run_id"),
                        rs.getString("node_id"),
                        rs.getString("site_path"),
                        rs.getString("index_file"),
                        rs.getBoolean("spa"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        SqlTime.read(rs, "created_at")))
                .optional();
    }

    public Optional<byte[]> file(String runId, String path) {
        return jdbc.sql("SELECT content FROM run_preview_files WHERE run_id = :run AND path = :path")
                .param("run", runId)
                .param("path", path)
                .query((rs, n) -> rs.getBytes("content"))
                .optional();
    }

    public boolean exists(String runId, String path) {
        return jdbc.sql("SELECT COUNT(*) FROM run_preview_files WHERE run_id = :run AND path = :path")
                        .param("run", runId)
                        .param("path", path)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Streams the files in path order. */
    public void each(String runId, FileConsumer consumer) {
        jdbc.sql("SELECT path, content FROM run_preview_files WHERE run_id = :run ORDER BY path")
                .param("run", runId)
                .query(rs -> {
                    try {
                        consumer.accept(rs.getString("path"), rs.getBytes("content"));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }
}
