package io.github.loopforge4j.server.work;

import java.time.Instant;
import java.util.List;

/**
 * One firing of a schedule (upstream schedule job execution).
 *
 * @param trigger {@code cron} or {@code manual}
 * @param status {@code started} (runs were started), {@code skipped} (the previous run was still
 *     active, or nothing was due for dispatch), {@code failed}, {@code running} (an AI task's turn is
 *     in progress) or {@code succeeded} (an AI task's turn finished)
 * @param runId the first run started, {@code runIds} all of them
 * @param runStatus the first run's current status, read when listing; {@code null} once deleted
 * @param reply what an AI task answered, or what a dispatch did
 * @param sessionId the assistant conversation of an AI task
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
        Instant firedAt,
        List<String> runIds,
        String reply,
        String sessionId,
        Instant finishedAt) {

    /** Executions kept per schedule. */
    public static final int KEEP = 200;

    public ScheduleExecution {
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
    }

    ScheduleExecution withRunStatus(String status) {
        return new ScheduleExecution(
                id,
                scheduleId,
                trigger,
                this.status,
                runId,
                status,
                error,
                actor,
                firedAt,
                runIds,
                reply,
                sessionId,
                finishedAt);
    }
}
