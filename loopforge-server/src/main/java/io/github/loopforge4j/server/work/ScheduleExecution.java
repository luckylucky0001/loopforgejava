package io.github.loopforge4j.server.work;

import java.time.Instant;

/**
 * One firing of a schedule (upstream schedule job execution).
 *
 * @param trigger {@code cron} or {@code manual}
 * @param status {@code started} (a run was started), {@code skipped} (the previous run was still
 *     active) or {@code failed} (the run could not start)
 * @param runStatus the started run's current status, read when listing; {@code null} once the run
 *     was deleted
 */
public record ScheduleExecution(
        long id,
        String scheduleId,
        String trigger,
        String status,
        String runId,
        String runStatus,
        String error,
        String actor,
        Instant firedAt) {

    /** Executions kept per schedule. */
    public static final int KEEP = 200;
}
