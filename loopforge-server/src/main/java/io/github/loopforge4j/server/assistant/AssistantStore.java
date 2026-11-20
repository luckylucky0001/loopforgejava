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
    String claimTurn(String id, Instant staleBefore) {
        Instant now = clock.instant();
        String turn = java.util.UUID.randomUUID().toString();
        boolean claimed = jdbc.sql("UPDATE assistant_sessions SET status = 'generating', turn_id = :turn,"
                                + " turn_started_at = :now, updated_at = :now"
                                + " WHERE id = :id AND (status = 'idle' OR turn_started_at < :stale)")
                        .param("now", SqlTime.of(now))
                        .param("turn", turn)
                        .param("id", id)
                        .param("stale", SqlTime.of(staleBefore))
                        .update()
                == 1;
        return claimed ? turn : null;
    }

    /**
     * Ends turns left generating by a stopped instance, so their tokens stop working.
     *
     * @return how many were ended
     */
    int endStaleTurns(Instant staleBefore) {
        return jdbc.sql("UPDATE assistant_sessions SET status = 'idle', turn_id = NULL, turn_started_at = NULL"
                        + " WHERE status IN ('generating', 'cancelling') AND turn_started_at < :stale")
                .param("stale", SqlTime.of(staleBefore))
                .update();
    }

    boolean requestCancel(String id) {
        return jdbc.sql("UPDATE assistant_sessions SET status = 'cancelling' WHERE id = :id AND status = 'generating'")
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Ends the turn and stores what it left (CLI session, usage, last error). */
    void finishTurn(AssistantSession session) {
        jdbc.sql("UPDATE assistant_sessions SET status = 'idle', turn_id = NULL, turn_started_at = NULL,"
                        + " payload = :payload,"
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

    /** A message waiting for the current reply to end ({@code priority} 1 steers: it runs first). */
    public record Queued(
            String id,
            String sessionId,
            String speakerId,
            String speaker,
            String content,
            int priority,
            Instant createdAt) {}

    Queued enqueue(String sessionId, String speakerId, String speaker, String content, boolean steer) {
        Queued queued = new Queued(
                "q_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                sessionId,
                speakerId,
                speaker,
                content,
                steer ? 1 : 0,
                clock.instant());
        jdbc.sql("INSERT INTO assistant_queue (id, session_id, speaker_id, speaker, content, priority, created_at)"
                        + " VALUES (:id, :s, :uid, :name, :content, :priority, :now)")
                .param("id", queued.id())
                .param("s", sessionId)
                .param("uid", speakerId)
                .param("name", speaker)
                .param("content", content)
                .param("priority", queued.priority())
                .param("now", SqlTime.of(queued.createdAt()))
                .update();
        return queued;
    }

    /** In the order they will run: steering messages first, then oldest first. */
    List<Queued> queued(String sessionId) {
        return jdbc.sql("SELECT * FROM assistant_queue WHERE session_id = :s ORDER BY priority DESC, created_at, id")
                .param("s", sessionId)
                .query((rs, i) -> new Queued(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("speaker_id"),
                        rs.getString("speaker"),
                        rs.getString("content"),
                        rs.getInt("priority"),
                        SqlTime.read(rs, "created_at")))
                .list();
    }

    /** Takes the next queued message; only one caller gets each. */
    Optional<Queued> takeNext(String sessionId) {
        for (Queued next : queued(sessionId)) {
            if (removeQueued(sessionId, next.id())) {
                return Optional.of(next);
            }
        }
        return Optional.empty();
    }

    boolean removeQueued(String sessionId, String id) {
        return jdbc.sql("DELETE FROM assistant_queue WHERE session_id = :s AND id = :id")
                        .param("s", sessionId)
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Idle conversations whose queue was left behind (an instance stopped between turns). */
    List<String> idleWithQueue() {
        return jdbc.sql("SELECT DISTINCT q.session_id FROM assistant_queue q JOIN assistant_sessions s"
                        + " ON s.id = q.session_id WHERE s.status = 'idle'")
                .query(String.class)
                .list();
    }

    /** Gives back a claimed turn that had nothing to do. */
    void releaseTurn(String id, String turn) {
        jdbc.sql("UPDATE assistant_sessions SET status = 'idle', turn_id = NULL, turn_started_at = NULL"
                        + " WHERE id = :id AND turn_id = :turn")
                .param("id", id)
                .param("turn", turn)
                .update();
    }

    boolean steerOnSend(String userId) {
        return userId != null
                && jdbc.sql("SELECT steer_on_send FROM assistant_preferences WHERE user_id = :u")
                        .param("u", userId)
                        .query(Boolean.class)
                        .optional()
                        .orElse(false);
    }

    void steerOnSend(String userId, boolean value) {
        int updated = jdbc.sql(
                        "UPDATE assistant_preferences SET steer_on_send = :v, updated_at = :now WHERE user_id = :u")
                .param("v", value)
                .param("now", SqlTime.of(clock.instant()))
                .param("u", userId)
                .update();
        if (updated == 0) {
            try {
                jdbc.sql("INSERT INTO assistant_preferences (user_id, steer_on_send, updated_at) VALUES (:u, :v, :now)")
                        .param("u", userId)
                        .param("v", value)
                        .param("now", SqlTime.of(clock.instant()))
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                steerOnSend(userId, value);
            }
        }
    }

    /** Marks everything up to the latest message as seen by {@code userId}. */
    void markRead(String sessionId, String userId) {
        if (userId == null) {
            return;
        }
        long latest = jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM assistant_messages WHERE session_id = :s")
                .param("s", sessionId)
                .query(Long.class)
                .single();
        int updated = jdbc.sql("UPDATE assistant_reads SET last_seq = :seq WHERE session_id = :s AND user_id = :u"
                        + " AND last_seq < :seq")
                .param("seq", latest)
                .param("s", sessionId)
                .param("u", userId)
                .update();
        if (updated == 0) {
            try {
                jdbc.sql("INSERT INTO assistant_reads (session_id, user_id, last_seq) VALUES (:s, :u, :seq)")
                        .param("s", sessionId)
                        .param("u", userId)
                        .param("seq", latest)
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // already read at least this far
            }
        }
    }

    /** Conversations among {@code sessionIds} with messages {@code userId} has not seen. */
    java.util.Set<String> unread(String userId, java.util.Collection<String> sessionIds) {
        if (userId == null || sessionIds.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(jdbc.sql("SELECT m.session_id FROM assistant_messages m"
                        + " LEFT JOIN assistant_reads r ON r.session_id = m.session_id AND r.user_id = :u"
                        + " WHERE m.session_id IN (:ids) GROUP BY m.session_id, r.last_seq"
                        + " HAVING MAX(m.seq) > COALESCE(r.last_seq, 0)")
                .param("u", userId)
                .param("ids", sessionIds)
                .query(String.class)
                .list());
    }

    /** Records the requirement the conversation works on. */
    void linkRequirement(String id, String requirementId) {
        find(id).ifPresent(session -> jdbc.sql("UPDATE assistant_sessions SET payload = :payload WHERE id = :id")
                .param("payload", Json.write(session.withRequirement(requirementId)))
                .param("id", id)
                .update());
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
