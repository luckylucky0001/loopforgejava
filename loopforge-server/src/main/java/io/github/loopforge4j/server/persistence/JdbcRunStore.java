package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.RunChange;
import io.github.loopforge4j.core.store.RunMutator;
import io.github.loopforge4j.core.store.RunQuery;
import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.core.store.StaleRunException;
import io.github.loopforge4j.core.store.StoreException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

/** {@link RunStore} on a relational database with optimistic revision checks. */
@Repository
public class JdbcRunStore implements RunStore {

    private static final int MAX_ATTEMPTS = 8;
    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RunLocks locks = new RunLocks(64);

    public JdbcRunStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void insert(Run run, List<RunEvent> initialEvents) {
        Run copy = Json.copy(run, Run.class);
        copy.revision(1);
        ReentrantLock lock = locks.forRun(run.id());
        lock.lock();
        try {
            executeWithoutResult(status -> {
                jdbc.sql("""
                        INSERT INTO runs (id, workflow_id, status, parent_run_id, revision, payload, created_at, updated_at)
                        VALUES (:id, :workflowId, :status, :parent, 1, :payload, :createdAt, :updatedAt)""")
                        .param("id", copy.id())
                        .param("workflowId", copy.workflowId())
                        .param("status", copy.status().wire())
                        .param("parent", copy.context().parentRunId())
                        .param("payload", Json.write(copy))
                        .param("createdAt", Timestamps.of(copy.createdAt()))
                        .param("updatedAt", Timestamps.of(copy.updatedAt()))
                        .update();
                insertEvents(copy.id(), initialEvents);
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Run> find(String runId) {
        return jdbc.sql("SELECT payload, revision FROM runs WHERE id = :id")
                .param("id", runId)
                .query(JdbcRunStore::mapRun)
                .optional();
    }

    @Override
    public List<Run> list(RunQuery query) {
        StringBuilder sql = new StringBuilder("SELECT payload, revision FROM runs WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (!query.statuses().isEmpty()) {
            sql.append(" AND status IN (:statuses)");
            params.put(
                    "statuses", query.statuses().stream().map(RunStatus::wire).toList());
        }
        if (query.workflowId() != null) {
            sql.append(" AND workflow_id = :workflowId");
            params.put("workflowId", query.workflowId());
        }
        if (query.parentRunId() != null) {
            sql.append(" AND parent_run_id = :parent");
            params.put("parent", query.parentRunId());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        params.put("limit", query.limit());
        return jdbc.sql(sql.toString())
                .params(params)
                .query(JdbcRunStore::mapRun)
                .list();
    }

    @Override
    public Optional<Run> update(String runId, RunMutator mutator) {
        ReentrantLock lock = locks.forRun(runId);
        lock.lock();
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                Optional<StoredRun> stored = jdbc.sql("SELECT payload, revision FROM runs WHERE id = :id")
                        .param("id", runId)
                        .query((rs, row) -> new StoredRun(rs.getString("payload"), rs.getLong("revision")))
                        .optional();
                if (stored.isEmpty()) {
                    return Optional.empty();
                }
                String payload = stored.get().payload();
                long expected = stored.get().revision();
                Run run = Json.read(payload, Run.class);
                run.revision(expected);
                RunChange change = new RunChange(run, clock, () -> artifacts(runId));
                if (!mutator.apply(change)) {
                    Run unchanged = Json.read(payload, Run.class);
                    unchanged.revision(expected);
                    return Optional.of(unchanged);
                }
                run.revision(expected + 1);
                run.updatedAt(clock.instant());
                Boolean committed = execute(status -> {
                    int rows = jdbc.sql("""
                            UPDATE runs SET payload = :payload, status = :status, revision = :next, updated_at = :updatedAt
                            WHERE id = :id AND revision = :expected""")
                            .param("payload", Json.write(run))
                            .param("status", run.status().wire())
                            .param("next", expected + 1)
                            .param("updatedAt", Timestamps.of(run.updatedAt()))
                            .param("id", runId)
                            .param("expected", expected)
                            .update();
                    if (rows == 0) {
                        return false;
                    }
                    change.pendingArtifacts().forEach(this::insertArtifact);
                    insertEvents(runId, change.pendingEvents());
                    return true;
                });
                if (Boolean.TRUE.equals(committed)) {
                    return Optional.of(run);
                }
            }
            throw new StaleRunException(runId, -1);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RunEvent> appendEvents(String runId, List<RunEvent> events) {
        ReentrantLock lock = locks.forRun(runId);
        lock.lock();
        try {
            return execute(status -> insertEvents(runId, events));
        } finally {
            lock.unlock();
        }
    }

    /** Runs a write transaction; driver and SQL details never leave the adapter (they reach node errors). */
    private <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
        try {
            return tx.execute(action);
        } catch (DataAccessException e) {
            throw new StoreException("run store write failed", e);
        }
    }

    private void executeWithoutResult(
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
        execute(status -> {
            action.accept(status);
            return null;
        });
    }

    private List<RunEvent> insertEvents(String runId, List<RunEvent> events) {
        List<RunEvent> stored = new ArrayList<>(events.size());
        for (RunEvent event : events) {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO run_events (run_id, node_id, category, type, level, message, attributes, created_at)
                    VALUES (:runId, :nodeId, :category, :type, :level, :message, :attributes, :createdAt)""")
                    .param("runId", runId)
                    .param("nodeId", event.nodeId())
                    .param("category", event.category())
                    .param("type", event.type())
                    .param("level", event.level() == null ? "info" : event.level())
                    .param("message", event.message())
                    .param("attributes", event.attributes().isEmpty() ? null : Json.write(event.attributes()))
                    .param("createdAt", Timestamps.of(event.timestamp()))
                    .update(keys, "seq");
            Number key = keys.getKey();
            if (key != null) {
                stored.add(event.withSequence(key.longValue()));
            }
        }
        return stored;
    }

    private void insertArtifact(Artifact a) {
        jdbc.sql("""
                INSERT INTO run_artifacts (id, run_id, node_id, attempt, name, path, media_type, size_bytes, sha256, content,
                    created_at)
                VALUES (:id, :runId, :nodeId, :attempt, :name, :path, :mediaType, :size, :sha256, :content, :createdAt)""")
                .param("id", a.id())
                .param("runId", a.runId())
                .param("nodeId", a.nodeId())
                .param("attempt", a.attempt())
                .param("name", a.name())
                .param("path", a.path())
                .param("mediaType", a.mediaType())
                .param("size", a.size())
                .param("sha256", a.sha256())
                .param("content", a.content())
                .param("createdAt", Timestamps.of(a.createdAt()))
                .update();
    }

    @Override
    public List<RunEvent> events(String runId, long afterSequence, int limit) {
        return jdbc.sql("""
                SELECT seq, run_id, node_id, category, type, level, message, attributes, created_at
                FROM run_events WHERE run_id = :runId AND seq > :after ORDER BY seq LIMIT :limit""")
                .param("runId", runId)
                .param("after", afterSequence)
                .param("limit", limit <= 0 ? 10_000 : limit)
                .query(JdbcRunStore::mapEvent)
                .list();
    }

    @Override
    public List<Artifact> artifacts(String runId) {
        return jdbc.sql("""
                SELECT id, run_id, node_id, attempt, name, path, media_type, size_bytes, sha256, content, created_at
                FROM run_artifacts WHERE run_id = :runId ORDER BY seq""")
                .param("runId", runId)
                .query(JdbcRunStore::mapArtifact)
                .list();
    }

    @Override
    public Optional<Artifact> artifact(String runId, String artifactId) {
        return jdbc.sql("""
                SELECT id, run_id, node_id, attempt, name, path, media_type, size_bytes, sha256, content, created_at
                FROM run_artifacts WHERE run_id = :runId AND id = :id""")
                .param("runId", runId)
                .param("id", artifactId)
                .query(JdbcRunStore::mapArtifact)
                .optional();
    }

    private record StoredRun(String payload, long revision) {}

    private static Run mapRun(ResultSet rs, int row) throws SQLException {
        Run run = Json.read(rs.getString("payload"), Run.class);
        run.revision(rs.getLong("revision"));
        return run;
    }

    private static RunEvent mapEvent(ResultSet rs, int row) throws SQLException {
        String attributes = rs.getString("attributes");
        return new RunEvent(
                rs.getLong("seq"),
                rs.getString("run_id"),
                rs.getString("node_id"),
                rs.getString("category"),
                rs.getString("type"),
                rs.getString("level"),
                rs.getString("message"),
                attributes == null ? Map.of() : Json.read(attributes, ATTRIBUTES),
                Timestamps.read(rs, "created_at"));
    }

    private static Artifact mapArtifact(ResultSet rs, int row) throws SQLException {
        return new Artifact(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getString("node_id"),
                rs.getInt("attempt"),
                rs.getString("name"),
                rs.getString("path"),
                rs.getString("media_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                rs.getString("content"),
                Timestamps.read(rs, "created_at"));
    }

    @Override
    public List<String> terminalRunsBefore(java.time.Instant before, int limit) {
        return jdbc.sql("SELECT id FROM runs WHERE status IN ('succeeded', 'failed', 'cancelled')"
                        + " AND updated_at < :before ORDER BY updated_at LIMIT :limit")
                .param("before", Timestamps.of(before))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    @Override
    public boolean delete(String runId) {
        ReentrantLock lock = locks.forRun(runId);
        lock.lock();
        try {
            // events and artifacts cascade
            return execute(status -> jdbc.sql("DELETE FROM runs WHERE id = :id")
                            .param("id", runId)
                            .update())
                    > 0;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int pruneEvents(java.time.Instant before, java.util.Set<String> categories) {
        if (categories.isEmpty()) {
            return 0;
        }
        return execute(
                status -> jdbc.sql("DELETE FROM run_events WHERE created_at < :before AND category IN (:categories)")
                        .param("before", Timestamps.of(before))
                        .param("categories", List.copyOf(categories))
                        .update());
    }
}
