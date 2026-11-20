package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.node.ObjectNode;

/**
 * Notification channels. The payload column keeps the secret (the API never returns it), so rows are
 * written through a JSON tree rather than the write-only record view.
 */
@Repository
public class NotificationStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public NotificationChannel save(NotificationChannel channel) {
        Instant now = clock.instant();
        Optional<NotificationChannel> existing = find(channel.id());
        NotificationChannel stamped = new NotificationChannel(
                channel.id(),
                channel.projectId(),
                channel.name(),
                channel.enabled(),
                channel.kind(),
                channel.url(),
                channel.secret(),
                channel.events(),
                channel.lastDeliveryAt(),
                channel.lastStatus(),
                existing.map(NotificationChannel::createdAt).orElse(now),
                now,
                channel.report());
        ObjectNode tree = Json.mapper().valueToTree(stamped);
        if (stamped.secret() != null) {
            tree.put("secret", stamped.secret());
        }
        String sql = existing.isPresent()
                ? "UPDATE notification_channels SET project_id = :p, enabled = :enabled, payload = :payload,"
                        + " updated_at = :now WHERE id = :id"
                : "INSERT INTO notification_channels (id, project_id, enabled, payload, created_at, updated_at)"
                        + " VALUES (:id, :p, :enabled, :payload, :now, :now)";
        jdbc.sql(sql)
                .param("id", stamped.id())
                .param("p", stamped.projectId())
                .param("enabled", stamped.enabled())
                .param("payload", tree.toString())
                .param("now", SqlTime.of(now))
                .update();
        return stamped;
    }

    public Optional<NotificationChannel> find(String id) {
        return jdbc.sql("SELECT payload FROM notification_channels WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), NotificationChannel.class))
                .optional();
    }

    /** Channels of a project, or the global ones for {@code null}. */
    public List<NotificationChannel> list(String projectId) {
        String sql = projectId == null
                ? "SELECT payload FROM notification_channels WHERE project_id IS NULL ORDER BY id"
                : "SELECT payload FROM notification_channels WHERE project_id = :p ORDER BY id";
        var statement = jdbc.sql(sql);
        if (projectId != null) {
            statement = statement.param("p", projectId);
        }
        return statement
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), NotificationChannel.class))
                .list();
    }

    /** Enabled channels for a run of {@code projectId}: the project's and the global ones. */
    public List<NotificationChannel> forProject(String projectId) {
        return jdbc.sql("SELECT payload FROM notification_channels WHERE enabled = TRUE"
                        + " AND (project_id IS NULL OR project_id = :p)")
                .param("p", projectId == null ? "" : projectId)
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), NotificationChannel.class))
                .list();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM notification_channels WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }
}
