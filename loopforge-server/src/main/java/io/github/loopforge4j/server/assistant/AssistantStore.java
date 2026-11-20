package io.github.loopforge4j.server.assistant;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Conversations and their messages; the turn status is a column so turns can be claimed atomically. */
@Repository
public class AssistantStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public AssistantStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void create(AssistantSession session) {
        jdbc.sql("INSERT INTO assistant_sessions (id, project_id, owner_id, status, payload, created_at, updated_at)"
                        + " VALUES (:id, :p, :o, 'idle', :payload, :now, :now)")
                .param("id", session.id())
                .param("p", session.projectId())
                .param("o", session.ownerId())
                .param("payload", Json.write(session))
                .param("now", SqlTime.of(session.createdAt()))
                .update();
    }

    public Optional<AssistantSession> find(String id) {
        return jdbc.sql("SELECT payload, status, turn_started_at FROM assistant_sessions WHERE id = :id")
                .param("id", id)
                .query(AssistantStore::map)
                .optional();
    }

    /**
     * Personal conversations of {@code ownerId} and conversations of {@code projects} ({@code null}:
     * every project, for administrators).
     */
    List<AssistantSession> visible(String ownerId, Set<String> projects) {
        StringBuilder sql = new StringBuilder(
                "SELECT payload, status, turn_started_at FROM assistant_sessions WHERE (project_id IS NULL AND owner_id = :o)");
        if (projects == null) {
            sql.append(" OR project_id IS NOT NULL");
        } else if (!projects.isEmpty()) {
            sql.append(" OR project_id IN (:projects)");
        }
        sql.append(" ORDER BY updated_at DESC LIMIT 200");
        var statement = jdbc.sql(sql.toString()).param("o", ownerId == null ? "" : ownerId);
        if (projects != null && !projects.isEmpty()) {
            statement = statement.param("projects", List.copyOf(projects));
        }
        return statement.query(AssistantStore::map).list();
    }

    /**
     * Claims the next turn: idle (or a turn left over by a stopped instance) becomes generating.
     *
     * @return whether this caller owns the turn now
     */
    boolean claimTurn(String id, Instant staleBefore) {
        Instant now = clock.instant();
        return jdbc.sql("UPDATE assistant_sessions SET status = 'generating', turn_started_at = :now, updated_at = :now"
                                + " WHERE id = :id AND (status = 'idle' OR turn_started_at < :stale)")
                        .param("now", SqlTime.of(now))
                        .param("id", id)
                        .param("stale", SqlTime.of(staleBefore))
                        .update()
                == 1;
    }

    boolean requestCancel(String id) {
        return jdbc.sql("UPDATE assistant_sessions SET status = 'cancelling' WHERE id = :id AND status = 'generating'")
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Ends the turn and stores what it left (CLI session, usage, last error). */
    void finishTurn(AssistantSession session) {
        jdbc.sql("UPDATE assistant_sessions SET status = 'idle', turn_started_at = NULL, payload = :payload,"
                        + " updated_at = :now WHERE id = :id")
                .param(
                        "payload",
                        Json.write(session.with(
                                "idle",
                                session.cliSessionId(),
                                session.lastError(),
                                session.totalTokens(),
                                session.costUsd(),
                                null)))
                .param("now", SqlTime.of(clock.instant()))
                .param("id", session.id())
                .update();
    }

    boolean delete(String id) {
        return jdbc.sql("DELETE FROM assistant_sessions WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    AssistantMessage append(AssistantMessage message) {
        Instant now = clock.instant();
        org.springframework.jdbc.support.GeneratedKeyHolder keys =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("INSERT INTO assistant_messages (session_id, payload, created_at) VALUES (:s, :payload, :now)")
                .param("s", message.sessionId())
                .param("payload", Json.write(message))
                .param("now", SqlTime.of(now))
                .update(keys, "seq");
        jdbc.sql("UPDATE assistant_sessions SET updated_at = :now WHERE id = :id")
                .param("now", SqlTime.of(now))
                .param("id", message.sessionId())
                .update();
        long seq = keys.getKey() == null ? 0 : keys.getKey().longValue();
        return new AssistantMessage(
                seq,
                message.sessionId(),
                message.role(),
                message.author(),
                message.content(),
                message.model(),
                message.tokens(),
                message.costUsd(),
                message.activity(),
                now);
    }

    List<AssistantMessage> messages(String sessionId, int limit) {
        List<AssistantMessage> newest = jdbc.sql(
                        "SELECT seq, payload, created_at FROM assistant_messages WHERE session_id = :s"
                                + " ORDER BY seq DESC LIMIT :limit")
                .param("s", sessionId)
                .param("limit", limit)
                .query((rs, row) -> {
                    AssistantMessage m = Json.read(rs.getString("payload"), AssistantMessage.class);
                    return new AssistantMessage(
                            rs.getLong("seq"),
                            m.sessionId(),
                            m.role(),
                            m.author(),
                            m.content(),
                            m.model(),
                            m.tokens(),
                            m.costUsd(),
                            m.activity(),
                            SqlTime.read(rs, "created_at"));
                })
                .list();
        return newest.reversed();
    }

    private static AssistantSession map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        AssistantSession s = Json.read(rs.getString("payload"), AssistantSession.class);
        Instant started = rs.getObject("turn_started_at") == null ? null : SqlTime.read(rs, "turn_started_at");
        return s.with(rs.getString("status"), s.cliSessionId(), s.lastError(), s.totalTokens(), s.costUsd(), started);
    }
}
