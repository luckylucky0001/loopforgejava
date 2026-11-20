package io.github.loopforge4j.server.runner;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Registered execution machines; the token is stored as its SHA-256 only. */
@Repository
public class RunnerStore {

    /** What a machine reports about itself. */
    record Status(String version, String host, List<String> executors, int capacity) {}

    private static final String SELECT = "SELECT r.*, u.username FROM runners r LEFT JOIN users u ON u.id = r.owner_id";

    private final JdbcClient jdbc;
    private final Clock clock;

    public RunnerStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void create(String id, String name, String ownerId, boolean shared, String tokenHash, String prefix) {
        jdbc.sql("INSERT INTO runners (id, name, owner_id, shared, token_hash, token_prefix, payload, created_at)"
                        + " VALUES (:id, :name, :owner, :shared, :hash, :prefix, :payload, :now)")
                .param("id", id)
                .param("name", name)
                .param("owner", ownerId)
                .param("shared", shared)
                .param("hash", tokenHash)
                .param("prefix", prefix)
                .param("payload", Json.write(new Status(null, null, List.of(), 0)))
                .param("now", SqlTime.of(clock.instant()))
                .update();
    }

    public Optional<Runner> find(String id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id")
                .param("id", id)
                .query(RunnerStore::map)
                .optional();
    }

    Optional<Runner> byTokenHash(String hash) {
        return jdbc.sql(SELECT + " WHERE r.token_hash = :h")
                .param("h", hash)
                .query(RunnerStore::map)
                .optional();
    }

    List<Runner> list() {
        return jdbc.sql(SELECT + " ORDER BY r.created_at")
                .query(RunnerStore::map)
                .list();
    }

    boolean delete(String id) {
        return jdbc.sql("DELETE FROM runners WHERE id = :id").param("id", id).update() > 0;
    }

    void seen(String id, Status status) {
        jdbc.sql("UPDATE runners SET payload = :payload, last_seen_at = :now WHERE id = :id")
                .param("payload", Json.write(status))
                .param("now", SqlTime.of(clock.instant()))
                .param("id", id)
                .update();
    }

    private static Runner map(ResultSet rs, int row) throws SQLException {
        Status status = Json.read(rs.getString("payload"), Status.class);
        Instant seen = rs.getObject("last_seen_at") == null ? null : SqlTime.read(rs, "last_seen_at");
        return new Runner(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("owner_id"),
                rs.getString("username"),
                rs.getBoolean("shared"),
                rs.getString("token_prefix"),
                status.version(),
                status.host(),
                status.executors(),
                status.capacity(),
                SqlTime.read(rs, "created_at"),
                seen);
    }

    static Map<String, Object> none() {
        return Map.of();
    }
}
