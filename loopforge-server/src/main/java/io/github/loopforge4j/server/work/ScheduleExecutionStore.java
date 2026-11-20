package io.github.loopforge4j.server.work;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** History of schedule firings, the newest {@link ScheduleExecution#KEEP} per schedule. */
@Repository
public class ScheduleExecutionStore {

    private final JdbcClient jdbc;

    public ScheduleExecutionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void record(Schedule schedule, String trigger, String runId, String error, String actor, Instant at) {
        String status = runId != null ? "started" : error != null && error.startsWith("skipped") ? "skipped" : "failed";
        jdbc.sql("INSERT INTO schedule_executions (schedule_id, project_id, trigger_kind, status, run_id, error, actor,"
                        + " fired_at) VALUES (:s, :p, :t, :status, :run, :error, :actor, :at)")
                .param("s", schedule.id())
                .param("p", schedule.projectId())
                .param("t", trigger)
                .param("status", status)
                .param("run", runId)
                .param("error", error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error)
                .param("actor", actor)
                .param("at", SqlTime.of(at))
                .update();
        jdbc.sql(
                        "DELETE FROM schedule_executions WHERE schedule_id = :s AND seq < (SELECT MIN(seq) FROM"
                                + " (SELECT seq FROM schedule_executions WHERE schedule_id = :s ORDER BY seq DESC LIMIT :keep) kept)")
                .param("s", schedule.id())
                .param("keep", ScheduleExecution.KEEP)
                .update();
    }

    /** Newest first, without the run status (filled in by the caller). */
    public List<ScheduleExecution> list(String scheduleId, int limit) {
        return jdbc.sql("SELECT seq, schedule_id, trigger_kind, status, run_id, error, actor, fired_at"
                        + " FROM schedule_executions WHERE schedule_id = :s ORDER BY seq DESC LIMIT :limit")
                .param("s", scheduleId)
                .param("limit", limit)
                .query((rs, row) -> new ScheduleExecution(
                        rs.getLong("seq"),
                        rs.getString("schedule_id"),
                        rs.getString("trigger_kind"),
                        rs.getString("status"),
                        rs.getString("run_id"),
                        null,
                        rs.getString("error"),
                        rs.getString("actor"),
                        SqlTime.read(rs, "fired_at")))
                .list();
    }
}
