package io.github.loopforge4j.server.work;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Scheduled runs. */
@Repository
public class ScheduleStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public ScheduleStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Schedule save(Schedule schedule) {
        Instant now = clock.instant();
        Optional<Schedule> existing = find(schedule.id());
        Schedule stamped = new Schedule(
                schedule.id(),
                schedule.projectId(),
                schedule.name(),
                schedule.enabled(),
                schedule.cron(),
                schedule.zone(),
                schedule.workflowId(),
                schedule.input(),
                schedule.repositories(),
                schedule.skipIfRunning(),
                schedule.nextRunAt(),
                schedule.lastRunAt(),
                schedule.lastRunId(),
                schedule.lastError(),
                schedule.createdBy(),
                existing.map(Schedule::createdAt).orElse(now),
                now);
        String sql = existing.isPresent()
                ? "UPDATE schedules SET project_id = :p, enabled = :enabled, next_run_at = :next, payload = :payload,"
                        + " updated_at = :now WHERE id = :id"
                : "INSERT INTO schedules (id, project_id, enabled, next_run_at, payload, created_at, updated_at)"
                        + " VALUES (:id, :p, :enabled, :next, :payload, :now, :now)";
        jdbc.sql(sql)
                .param("id", stamped.id())
                .param("p", stamped.projectId())
                .param("enabled", stamped.enabled())
                .param("next", SqlTime.of(stamped.nextRunAt()))
                .param("payload", JsonRows.write(stamped))
                .param("now", SqlTime.of(now))
                .update();
        return stamped;
    }

    public Optional<Schedule> find(String id) {
        return jdbc.sql("SELECT payload, next_run_at FROM schedules WHERE id = :id")
                .param("id", id)
                .query(ScheduleStore::map)
                .optional();
    }

    /**
     * The indexed {@code next_run_at} column is the source of truth for the slot: a crash between a
     * claim (which moves only the column) and the payload update must not leave a stale payload slot
     * that no later claim matches.
     */
    private static Schedule map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Schedule s = JsonRows.read(rs.getString("payload"), Schedule.class);
        Instant column = SqlTime.read(rs, "next_run_at");
        return new Schedule(
                s.id(),
                s.projectId(),
                s.name(),
                s.enabled(),
                s.cron(),
                s.zone(),
                s.workflowId(),
                s.input(),
                s.repositories(),
                s.skipIfRunning(),
                column,
                s.lastRunAt(),
                s.lastRunId(),
                s.lastError(),
                s.createdBy(),
                s.createdAt(),
                s.updatedAt());
    }

    public List<Schedule> list(String projectId) {
        return jdbc.sql("SELECT payload, next_run_at FROM schedules WHERE project_id = :p ORDER BY id")
                .param("p", projectId)
                .query(ScheduleStore::map)
                .list();
    }

    public List<Schedule> due(Instant now, int limit) {
        return jdbc.sql("SELECT payload, next_run_at FROM schedules WHERE enabled = TRUE AND next_run_at <= :now"
                        + " ORDER BY next_run_at LIMIT :limit")
                .param("now", SqlTime.of(now))
                .param("limit", limit)
                .query(ScheduleStore::map)
                .list();
    }

    /**
     * Moves a due schedule to its next slot if nobody else did: the compare-and-set on {@code
     * next_run_at} makes exactly one instance fire each slot.
     */
    public boolean claim(Schedule schedule, Instant next) {
        return jdbc.sql("UPDATE schedules SET next_run_at = :next WHERE id = :id AND next_run_at = :due")
                        .param("id", schedule.id())
                        .param("next", SqlTime.of(next))
                        .param("due", SqlTime.of(schedule.nextRunAt()))
                        .update()
                == 1;
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM schedules WHERE id = :id").param("id", id).update() > 0;
    }
}
