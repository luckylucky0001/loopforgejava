package io.github.loopforge4j.server.work;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Clarifications of requirements. The {@code status} column is the source of truth for whether a
 * round runs, so exactly one caller (on any instance) claims each round.
 */
@Repository
public class ClarificationStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public ClarificationStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<Clarification> find(String requirementId) {
        return jdbc.sql("SELECT payload, status, round_started_at FROM requirement_clarifications"
                        + " WHERE requirement_id = :id")
                .param("id", requirementId)
                .query(ClarificationStore::map)
                .optional();
    }

    private static Clarification map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Clarification c = JsonRows.read(rs.getString("payload"), Clarification.class);
        Clarification.Builder b = c.toBuilder();
        b.status = rs.getString("status");
        b.roundStartedAt = SqlTime.read(rs, "round_started_at");
        return b.build();
    }

    /**
     * Claims the next round: an idle clarification (or one whose round was left behind by a stopped
     * instance) becomes {@code analyzing} with {@code next} as its payload; a first round inserts it.
     *
     * @return whether this caller owns the round
     */
    boolean claim(Clarification next, Instant staleBefore) {
        Instant now = clock.instant();
        Clarification stamped = stamp(next, "analyzing", now, now);
        int updated = jdbc.sql("UPDATE requirement_clarifications SET status = 'analyzing', round_started_at = :now,"
                        + " payload = :payload, updated_at = :now WHERE requirement_id = :id"
                        + " AND (status IN ('new', 'in_progress', 'ready') OR round_started_at < :stale)")
                .param("id", stamped.requirementId())
                .param("now", SqlTime.of(now))
                .param("payload", JsonRows.write(stamped))
                .param("stale", SqlTime.of(staleBefore))
                .update();
        if (updated == 1) {
            return true;
        }
        if (find(stamped.requirementId()).isPresent()) {
            return false;
        }
        try {
            jdbc.sql("INSERT INTO requirement_clarifications (requirement_id, status, round_started_at, payload,"
                            + " updated_at) VALUES (:id, 'analyzing', :now, :payload, :now)")
                    .param("id", stamped.requirementId())
                    .param("now", SqlTime.of(now))
                    .param("payload", JsonRows.write(stamped))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false; // another caller started the first round
        }
    }

    boolean requestCancel(String requirementId) {
        return jdbc.sql("UPDATE requirement_clarifications SET status = 'cancelling'"
                                + " WHERE requirement_id = :id AND status = 'analyzing'")
                        .param("id", requirementId)
                        .update()
                == 1;
    }

    /** Ends the running round with {@code result}; returns what was stored. */
    Clarification finish(Clarification result) {
        Clarification stamped = stamp(result, result.status(), null, clock.instant());
        jdbc.sql("UPDATE requirement_clarifications SET status = :status, round_started_at = NULL,"
                        + " payload = :payload, updated_at = :now WHERE requirement_id = :id")
                .param("id", stamped.requirementId())
                .param("status", stamped.status())
                .param("payload", JsonRows.write(stamped))
                .param("now", SqlTime.of(stamped.updatedAt()))
                .update();
        return stamped;
    }

    /** Removes an idle clarification (start over). */
    boolean deleteIdle(String requirementId) {
        return jdbc.sql("DELETE FROM requirement_clarifications WHERE requirement_id = :id"
                                + " AND status IN ('new', 'in_progress', 'ready')")
                        .param("id", requirementId)
                        .update()
                == 1;
    }

    private static Clarification stamp(Clarification c, String status, Instant started, Instant now) {
        Clarification.Builder b = c.toBuilder();
        b.status = status;
        b.roundStartedAt = started;
        b.createdAt = c.createdAt() == null ? now : c.createdAt();
        b.updatedAt = now;
        return b.build();
    }
}
