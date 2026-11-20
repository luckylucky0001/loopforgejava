package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.store.InstanceRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Engine instance registry shared by every server of a deployment. */
@Repository
public class JdbcInstanceRegistry implements InstanceRegistry {

    /** Rows older than this are pruned on heartbeat; leases only need minutes. */
    private static final Duration PRUNE_AFTER = Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcInstanceRegistry(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void heartbeat(Instance instance) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.sql("UPDATE engine_instances SET host = :host, pid = :pid, started_at = :startedAt,"
                            + " heartbeat_at = :heartbeatAt WHERE id = :id")
                    .param("id", instance.id())
                    .param("host", instance.host())
                    .param("pid", instance.pid())
                    .param("startedAt", Timestamps.of(instance.startedAt()))
                    .param("heartbeatAt", Timestamps.of(instance.heartbeatAt()))
                    .update();
            if (updated == 0) {
                try {
                    jdbc.sql("INSERT INTO engine_instances (id, host, pid, started_at, heartbeat_at)"
                                    + " VALUES (:id, :host, :pid, :startedAt, :heartbeatAt)")
                            .param("id", instance.id())
                            .param("host", instance.host())
                            .param("pid", instance.pid())
                            .param("startedAt", Timestamps.of(instance.startedAt()))
                            .param("heartbeatAt", Timestamps.of(instance.heartbeatAt()))
                            .update();
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    // registered concurrently by the same id; the next heartbeat updates it
                }
            }
        });
        jdbc.sql("DELETE FROM engine_instances WHERE heartbeat_at < :before")
                .param("before", Timestamps.of(instance.heartbeatAt().minus(PRUNE_AFTER)))
                .update();
    }

    @Override
    public Optional<Instance> find(String id) {
        return jdbc.sql("SELECT * FROM engine_instances WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> new Instance(
                        rs.getString("id"),
                        rs.getString("host"),
                        rs.getLong("pid"),
                        Timestamps.read(rs, "started_at"),
                        Timestamps.read(rs, "heartbeat_at")))
                .optional();
    }

    @Override
    public List<Instance> instances() {
        return jdbc.sql("SELECT * FROM engine_instances ORDER BY id")
                .query((rs, row) -> new Instance(
                        rs.getString("id"),
                        rs.getString("host"),
                        rs.getLong("pid"),
                        Timestamps.read(rs, "started_at"),
                        Timestamps.read(rs, "heartbeat_at")))
                .list();
    }

    @Override
    public void remove(String id) {
        jdbc.sql("DELETE FROM engine_instances WHERE id = :id").param("id", id).update();
    }
}
