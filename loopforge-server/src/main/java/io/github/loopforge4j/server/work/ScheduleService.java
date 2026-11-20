package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.RepositorySelection;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/** Validates schedules and fires due ones (every instance polls; claiming makes each slot fire once). */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final ScheduleStore store;
    private final ScheduleExecutionStore executions;
    private final RunEngine engine;
    private final Clock clock;

    public ScheduleService(ScheduleStore store, ScheduleExecutionStore executions, RunEngine engine, Clock clock) {
        this.store = store;
        this.executions = executions;
        this.engine = engine;
        this.clock = clock;
    }

    /** The schedule's recent firings, newest first, with the current status of the runs they started. */
    public List<ScheduleExecution> executions(String scheduleId, int limit) {
        return executions.list(scheduleId, limit).stream()
                .map(e -> {
                    if (e.runId() == null) {
                        return e;
                    }
                    String status;
                    try {
                        status = engine.require(e.runId()).status().wire();
                    } catch (RunNotFoundException gone) {
                        status = null;
                    }
                    return new ScheduleExecution(
                            e.id(),
                            e.scheduleId(),
                            e.trigger(),
                            e.status(),
                            e.runId(),
                            status,
                            e.error(),
                            e.actor(),
                            e.firedAt());
                })
                .toList();
    }

    public Schedule save(String projectId, String id, Schedule body, String actor) {
        List<String> problems = new ArrayList<>();
        CronExpression cron = null;
        ZoneId zone = null;
        if (body.name() == null || body.name().isBlank() || body.name().length() > 500) {
            problems.add("name is required (at most 500 characters)");
        }
        try {
            cron = parse(body.cron());
        } catch (IllegalArgumentException e) {
            problems.add("cron is invalid: " + e.getMessage());
        }
        try {
            zone = ZoneId.of(body.zone());
        } catch (RuntimeException e) {
            problems.add("zone is not a known time zone");
        }
        if (body.workflowId() == null || body.workflowId().isBlank()) {
            problems.add("workflowId is required");
        }
        if (body.input() != null && body.input().length() > 200_000) {
            problems.add("input is too long");
        }
        if (body.repositories().size() > 20) {
            problems.add("at most 20 repositories");
        }
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
        Schedule existing = id == null ? null : store.find(id).orElse(null);
        Instant next = body.enabled() ? next(cron, zone, clock.instant()) : null;
        return store.save(new Schedule(
                id == null ? "sch_" + UUID.randomUUID().toString().replace("-", "") : id,
                projectId,
                body.name().strip(),
                body.enabled(),
                body.cron().strip(),
                zone.getId(),
                body.workflowId(),
                body.input(),
                body.repositories(),
                body.skipIfRunning(),
                next,
                existing == null ? null : existing.lastRunAt(),
                existing == null ? null : existing.lastRunId(),
                existing == null ? null : existing.lastError(),
                existing == null ? actor : existing.createdBy(),
                null,
                null));
    }

    @Scheduled(
            initialDelayString = "${loopforge.schedules.poll-interval:15s}",
            fixedDelayString = "${loopforge.schedules.poll-interval:15s}")
    public void fireDue() {
        Instant now = clock.instant();
        for (Schedule schedule : store.due(now, 100)) {
            try {
                fire(schedule, now);
            } catch (RuntimeException e) {
                log.warn("schedule {} failed: {}", schedule.id(), e.toString());
            }
        }
    }

    /** Fires one due schedule unless another instance already claimed the slot. */
    void fire(Schedule schedule, Instant now) {
        Instant next = next(parse(schedule.cron()), ZoneId.of(schedule.zone()), now);
        if (!store.claim(schedule, next)) {
            return;
        }
        String runId = null;
        String error = null;
        if (schedule.skipIfRunning() && previousActive(schedule)) {
            error = "skipped: the previous run " + schedule.lastRunId() + " is still active";
        } else {
            try {
                List<RepositorySelection> selections = schedule.repositories().stream()
                        .map(r -> new RepositorySelection(r.repositoryId(), r.baseBranch(), r.workBranch(), r.mode()))
                        .toList();
                Run run = engine.start(
                        schedule.workflowId(),
                        schedule.input(),
                        new ExecutionContext(schedule.projectId(), "schedule:" + schedule.id(), null, null, null),
                        selections);
                runId = run.id();
                log.info("schedule {} started run {}", schedule.id(), runId);
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
        }
        // the claim moved only the indexed column; the payload follows with the same slot
        Schedule latest = store.find(schedule.id()).orElse(schedule);
        store.save(latest.fired(now, latest.enabled() ? next : null, runId, error));
        executions.record(schedule, "cron", runId, error, "schedule", now);
    }

    /** Manual trigger: starts a run now without moving the cron slot. */
    public void runNow(Schedule schedule, String actor) {
        String runId = null;
        String error = null;
        try {
            runId = engine.start(
                            schedule.workflowId(),
                            schedule.input(),
                            new ExecutionContext(schedule.projectId(), "schedule:" + schedule.id(), null, null, null),
                            schedule.repositories().stream()
                                    .map(r -> new RepositorySelection(
                                            r.repositoryId(), r.baseBranch(), r.workBranch(), r.mode()))
                                    .toList())
                    .id();
        } catch (RuntimeException e) {
            error = e.getMessage();
        }
        Instant now = clock.instant();
        Schedule latest = store.find(schedule.id()).orElse(schedule);
        store.save(latest.fired(now, latest.nextRunAt(), runId, error));
        executions.record(schedule, "manual", runId, error, actor, now);
    }

    private boolean previousActive(Schedule schedule) {
        if (schedule.lastRunId() == null) {
            return false;
        }
        try {
            return !engine.require(schedule.lastRunId()).status().terminal();
        } catch (RunNotFoundException e) {
            return false;
        }
    }

    /** Accepts the common five-field form by adding a zero seconds field. */
    static CronExpression parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression is empty");
        }
        String trimmed = expression.strip();
        String[] fields = trimmed.split("\\s+");
        return CronExpression.parse(fields.length == 5 ? "0 " + trimmed : trimmed);
    }

    static Instant next(CronExpression cron, ZoneId zone, Instant after) {
        ZonedDateTime next = cron.next(after.atZone(zone));
        return next == null ? null : next.toInstant();
    }
}
