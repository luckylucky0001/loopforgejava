package io.github.loopforge4j.server.work;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** History of schedule firings, the newest {@link ScheduleExecution#KEEP} per schedule. */
@Repository
public class ScheduleExecutionStore {

    private final JdbcClient jdbc;

    public ScheduleExecutionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A finished firing (SOP run, dispatch). */
    void record(
            Schedule schedule,
            String trigger,
            String status,
            List<String> runIds,
            String reply,
            String error,
            String actor,
            Instant at) {
        insert(schedule, trigger, status, runIds, reply, error, actor, null, at, at);
    }

    /** An SOP firing: started, skipped (the previous run is active) or failed. */
    void record(Schedule schedule, String trigger, String runId, String error, String actor, Instant at) {
        String status = runId != null ? "started" : error != null && error.startsWith("skipped") ? "skipped" : "failed";
        record(schedule, trigger, status, runId == null ? List.of() : List.of(runId), null, error, actor, at);
    }

    /** An AI task's turn that has begun; {@link #finish} completes it. */
    long begin(Schedule schedule, String trigger, String sessionId, String actor, Instant at) {
        return insert(schedule, trigger, "running", List.of(), null, null, actor, sessionId, at, null);
    }

    void finish(long id, String status, List<String> runIds, String reply, String error, Instant at) {
        jdbc.sql("UPDATE schedule_executions SET status = :status, run_id = :run, run_ids = :runs, reply = :reply,"
                        + " error = :error, finished_at = :at WHERE seq = :id")
                .param("id", id)
                .param("status", status)
                .param("run", runIds.isEmpty() ? null : runIds.getFirst())
                .param("runs", runIds.isEmpty() ? null : String.join(",", runIds))
                .param("reply", clip(reply, 4000))
                .param("error", clip(error, 2000))
                .param("at", SqlTime.of(at))
                .update();
    }

    /** Turns left running by an instance that stopped are failed (upstream: interrupted by a restart). */
    int failInterrupted(Instant startedBefore, Instant now) {
        return jdbc.sql("UPDATE schedule_executions SET status = 'failed', error = :error, finished_at = :now"
                        + " WHERE status = 'running' AND fired_at < :before")
                .param("error", "上次运行中断（服务重启或超时）")
                .param("now", SqlTime.of(now))
                .param("before", SqlTime.of(startedBefore))
                .update();
    }

    private long insert(
            Schedule schedule,
            String trigger,
            String status,
            List<String> runIds,
            String reply,
            String error,
            String actor,
            String sessionId,
            Instant at,
            Instant finished) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO schedule_executions (schedule_id, project_id, trigger_kind, status, run_id, run_ids,"
                        + " reply, error, actor, session_id, fired_at, finished_at) VALUES (:s, :p, :t, :status, :run,"
                        + " :runs, :reply, :error, :actor, :session, :at, :finished)")
                .param("s", schedule.id())
                .param("p", schedule.projectId())
                .param("t", trigger)
                .param("status", status)
                .param("run", runIds.isEmpty() ? null : runIds.getFirst())
                .param("runs", runIds.isEmpty() ? null : String.join(",", runIds))
                .param("reply", clip(reply, 4000))
                .param("error", clip(error, 2000))
                .param("actor", actor)
                .param("session", sessionId)
                .param("at", SqlTime.of(at))
                .param("finished", SqlTime.of(finished))
                .update(keys, "seq");
        jdbc.sql(
                        "DELETE FROM schedule_executions WHERE schedule_id = :s AND seq < (SELECT MIN(seq) FROM"
                                + " (SELECT seq FROM schedule_executions WHERE schedule_id = :s ORDER BY seq DESC LIMIT :keep) kept)")
                .param("s", schedule.id())
                .param("keep", ScheduleExecution.KEEP)
                .update();
        Number key = keys.getKeys() != null && keys.getKeys().get("seq") instanceof Number n
                ? n
                : keys.getKeys() != null && keys.getKeys().get("SEQ") instanceof Number n2 ? n2 : keys.getKey();
        return key == null ? -1 : key.longValue();
    }

    /** Newest first, without the run status (filled in by the caller). */
    public List<ScheduleExecution> list(String scheduleId, int limit) {
        return jdbc.sql("SELECT seq, schedule_id, trigger_kind, status, run_id, run_ids, reply, session_id, error,"
                        + " actor, fired_at, finished_at FROM schedule_executions WHERE schedule_id = :s"
                        + " ORDER BY seq DESC LIMIT :limit")
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
                        SqlTime.read(rs, "fired_at"),
                        rs.getString("run_ids") == null
                                ? List.of()
                                : Arrays.asList(rs.getString("run_ids").split(",")),
                        rs.getString("reply"),
                        rs.getString("session_id"),
                        SqlTime.read(rs, "finished_at")))
                .list();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
