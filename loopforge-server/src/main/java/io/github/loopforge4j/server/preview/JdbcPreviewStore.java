package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.domain.PreviewSettings;
import io.github.loopforge4j.core.preview.PreviewArchive;
import io.github.loopforge4j.core.store.PreviewStore;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Previews and their files in the database, so every instance serves them and they go with their
 * run; sessions store only the hash of their token.
 */
@Repository
public class JdbcPreviewStore implements PreviewStore {

    /** A run's preview, without its files (upstream {@code RunPreview}). */
    public record Preview(
            String runId,
            String nodeId,
            String status,
            String path,
            String index,
            boolean spa,
            int files,
            long bytes,
            String error,
            String backendUrl,
            Instant expiresAt,
            Instant lastAccessedAt,
            Instant createdAt,
            Instant updatedAt) {

        public boolean serveable() {
            return READY.equals(status);
        }
    }

    /** An access grant for {@code /preview/{token}/} (upstream {@code PreviewSession}). */
    public record Session(
            String id,
            String runId,
            String backendUrl,
            String createdBy,
            Instant expiresAt,
            Instant createdAt,
            Instant lastAccessedAt,
            Instant revokedAt) {

        public boolean live(Instant now) {
            return revokedAt == null && expiresAt.isAfter(now);
        }
    }

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

    // ------------------------------------------------------------------ engine port

    @Override
    public boolean save(
            String runId, String nodeId, PreviewSettings settings, PreviewArchive.Site site, Duration retention) {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            // a new build replaces the previous site; the backend address chosen for the run stays
            String backend = find(runId).map(Preview::backendUrl).orElse(null);
            jdbc.sql("DELETE FROM run_previews WHERE run_id = :run")
                    .param("run", runId)
                    .update();
            insert(
                    runId,
                    nodeId,
                    READY,
                    settings,
                    site.files().size(),
                    site.bytes(),
                    null,
                    backend,
                    now.plus(retention),
                    now);
            long total = 0;
            for (String file : site.files()) {
                byte[] content;
                try {
                    // never through a link swapped in after the site was collected, never beyond the limits
                    content = io.github.loopforge4j.core.runtime.SafeFiles.read(
                            site.root(), file, site.limits().maxFileBytes());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                total += content.length;
                if (total > site.limits().maxTotalBytes()) {
                    throw new UncheckedIOException(new IOException("preview grew beyond its size limit"));
                }
                jdbc.sql("INSERT INTO run_preview_files (run_id, path, content) VALUES (:run, :path, :content)")
                        .param("run", runId)
                        .param("path", file)
                        .param("content", content)
                        .update();
            }
        });
        return true;
    }

    @Override
    public void skip(String runId, String nodeId, PreviewSettings settings, String reason) {
        tx.executeWithoutResult(status -> {
            Optional<Preview> existing = find(runId);
            // a site that was archived (or expired) is not replaced by a later skip
            if (existing.isPresent() && !FAILED.equals(existing.get().status())) {
                return;
            }
            replace(
                    runId,
                    nodeId,
                    SKIPPED,
                    settings,
                    reason,
                    existing.map(Preview::backendUrl).orElse(null));
        });
    }

    @Override
    public void fail(String runId, String nodeId, PreviewSettings settings, String reason) {
        tx.executeWithoutResult(status -> replace(
                runId,
                nodeId,
                FAILED,
                settings,
                reason,
                find(runId).map(Preview::backendUrl).orElse(null)));
    }

    @Override
    public Optional<String> status(String runId) {
        return find(runId).map(Preview::status);
    }

    private void replace(
            String runId, String nodeId, String status, PreviewSettings settings, String reason, String backend) {
        jdbc.sql("DELETE FROM run_previews WHERE run_id = :run")
                .param("run", runId)
                .update();
        insert(runId, nodeId, status, settings, 0, 0, clip(reason), backend, null, clock.instant());
    }

    private void insert(
            String runId,
            String nodeId,
            String status,
            PreviewSettings settings,
            int count,
            long bytes,
            String error,
            String backend,
            Instant expiresAt,
            Instant now) {
        jdbc.sql(
                        "INSERT INTO run_previews (run_id, node_id, status, site_path, index_file, spa, file_count, total_bytes,"
                                + " error_text, backend_url, expires_at, created_at, updated_at) VALUES (:run, :node, :status,"
                                + " :path, :index, :spa, :count, :bytes, :error, :backend, :expires, :now, :now)")
                .param("run", runId)
                .param("node", nodeId)
                .param("status", status)
                .param("path", settings.path())
                .param("index", settings.index())
                .param("spa", settings.spa())
                .param("count", count)
                .param("bytes", bytes)
                .param("error", error)
                .param("backend", backend)
                .param("expires", expiresAt == null ? null : SqlTime.of(expiresAt))
                .param("now", SqlTime.of(now))
                .update();
    }

    private static String clip(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    // ------------------------------------------------------------------ previews

    public Optional<Preview> find(String runId) {
        return jdbc.sql("SELECT * FROM run_previews WHERE run_id = :run")
                .param("run", runId)
                .query((rs, n) -> new Preview(
                        rs.getString("run_id"),
                        rs.getString("node_id"),
                        rs.getString("status"),
                        rs.getString("site_path"),
                        rs.getString("index_file"),
                        rs.getBoolean("spa"),
                        rs.getInt("file_count"),
                        rs.getLong("total_bytes"),
                        rs.getString("error_text"),
                        rs.getString("backend_url"),
                        SqlTime.read(rs, "expires_at"),
                        SqlTime.read(rs, "last_accessed_at"),
                        SqlTime.read(rs, "created_at"),
                        SqlTime.read(rs, "updated_at")))
                .optional();
    }

    void backend(String runId, String backendUrl) {
        jdbc.sql("UPDATE run_previews SET backend_url = :backend, updated_at = :now WHERE run_id = :run")
                .param("run", runId)
                .param("backend", backendUrl)
                .param("now", SqlTime.of(clock.instant()))
                .update();
    }

    boolean delete(String runId) {
        return jdbc.sql("DELETE FROM run_previews WHERE run_id = :run")
                        .param("run", runId)
                        .update()
                > 0;
    }

    /** Drops the files of ready previews past their retention and marks them expired. */
    int expire(Instant now) {
        List<String> due = jdbc.sql("SELECT run_id FROM run_previews WHERE status = :ready AND expires_at < :now")
                .param("ready", READY)
                .param("now", SqlTime.of(now))
                .query(String.class)
                .list();
        for (String runId : due) {
            tx.executeWithoutResult(status -> {
                jdbc.sql("DELETE FROM run_preview_files WHERE run_id = :run")
                        .param("run", runId)
                        .update();
                jdbc.sql("UPDATE run_previews SET status = :expired, updated_at = :now WHERE run_id = :run")
                        .param("run", runId)
                        .param("expired", EXPIRED)
                        .param("now", SqlTime.of(now))
                        .update();
            });
        }
        return due.size();
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

    /** Whether any script of the site uses the History API (a client-side router), scanned once per site. */
    boolean usesHistory(String runId) {
        boolean[] found = {false};
        jdbc.sql("SELECT path, content FROM run_preview_files WHERE run_id = :run")
                .param("run", runId)
                .query(rs -> {
                    String path = rs.getString("path").toLowerCase(java.util.Locale.ROOT);
                    if (!found[0] && (path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".html"))) {
                        String text = new String(rs.getBytes("content"), java.nio.charset.StandardCharsets.UTF_8);
                        found[0] = text.contains("pushState") || text.contains("replaceState");
                    }
                });
        return found[0];
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

    // ------------------------------------------------------------------ sessions

    void insertSession(Session session, String tokenHash) {
        jdbc.sql(
                        "INSERT INTO preview_sessions (id, run_id, token_hash, backend_url, created_by, expires_at, created_at)"
                                + " VALUES (:id, :run, :hash, :backend, :by, :expires, :now)")
                .param("id", session.id())
                .param("run", session.runId())
                .param("hash", tokenHash)
                .param("backend", session.backendUrl())
                .param("by", session.createdBy())
                .param("expires", SqlTime.of(session.expiresAt()))
                .param("now", SqlTime.of(session.createdAt()))
                .update();
    }

    Optional<Session> sessionByHash(String tokenHash) {
        return jdbc.sql("SELECT * FROM preview_sessions WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .query((rs, n) -> new Session(
                        rs.getString("id"),
                        rs.getString("run_id"),
                        rs.getString("backend_url"),
                        rs.getString("created_by"),
                        SqlTime.read(rs, "expires_at"),
                        SqlTime.read(rs, "created_at"),
                        SqlTime.read(rs, "last_accessed_at"),
                        SqlTime.read(rs, "revoked_at")))
                .optional();
    }

    /** Slides the session's expiry and records the access on it and its preview. */
    void touch(Session session, Instant expiresAt, Instant now) {
        jdbc.sql("UPDATE preview_sessions SET expires_at = :expires, last_accessed_at = :now WHERE id = :id"
                        + " AND revoked_at IS NULL")
                .param("id", session.id())
                .param("expires", SqlTime.of(expiresAt))
                .param("now", SqlTime.of(now))
                .update();
        jdbc.sql("UPDATE run_previews SET last_accessed_at = :now WHERE run_id = :run")
                .param("run", session.runId())
                .param("now", SqlTime.of(now))
                .update();
    }

    Optional<String> sessionCreator(String runId, String sessionId) {
        return jdbc.sql("SELECT created_by FROM preview_sessions WHERE id = :id AND run_id = :run")
                .param("id", sessionId)
                .param("run", runId)
                .query((rs, n) -> Optional.ofNullable(rs.getString("created_by")))
                .optional()
                .map(o -> o.orElse(""));
    }

    boolean revoke(String runId, String sessionId, Instant now) {
        return jdbc.sql("UPDATE preview_sessions SET revoked_at = :now WHERE id = :id AND run_id = :run"
                                + " AND revoked_at IS NULL")
                        .param("id", sessionId)
                        .param("run", runId)
                        .param("now", SqlTime.of(now))
                        .update()
                > 0;
    }

    /** Sessions that ended a day ago are of no use any more. */
    int pruneSessions(Instant before) {
        return jdbc.sql("DELETE FROM preview_sessions WHERE expires_at < :before OR revoked_at < :before")
                .param("before", SqlTime.of(before))
                .update();
    }
}
