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
    private final RequirementStore requirementStore;
    private final org.springframework.beans.factory.ObjectProvider<RequirementService> requirements;
    private final org.springframework.beans.factory.ObjectProvider<
                    io.github.loopforge4j.server.assistant.AssistantService>
            assistant;
    private final io.github.loopforge4j.server.account.UserStore users;
    private final io.github.loopforge4j.server.account.AccessPolicy access;

    public ScheduleService(
            ScheduleStore store,
            ScheduleExecutionStore executions,
            RunEngine engine,
            Clock clock,
            RequirementStore requirementStore,
            org.springframework.beans.factory.ObjectProvider<RequirementService> requirements,
            org.springframework.beans.factory.ObjectProvider<io.github.loopforge4j.server.assistant.AssistantService>
                    assistant,
            io.github.loopforge4j.server.account.UserStore users,
            io.github.loopforge4j.server.account.AccessPolicy access) {
        this.store = store;
        this.executions = executions;
        this.engine = engine;
        this.clock = clock;
        this.requirementStore = requirementStore;
        this.requirements = requirements;
        this.assistant = assistant;
        this.users = users;
        this.access = access;
    }

    /** Statuses a dispatch may pick requirements from (not settled or manually stopped ones). */
    static final java.util.Set<String> DISPATCHABLE = java.util.Set.of("backlog", "todo", "blocked", "in_review");

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
                    return e.withRunStatus(status);
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
        String kind = body.kind();
        switch (kind) {
            case Schedule.SOP -> {
                if (body.workflowId() == null || body.workflowId().isBlank()) {
                    problems.add("workflowId is required");
                }
            }
            case Schedule.ASSISTANT -> {
                if (body.prompt() == null
                        || body.prompt().isBlank()
                        || body.prompt().length() > 20_000) {
                    problems.add("prompt is required (at most 20,000 characters)");
                }
                if (users.byUsername(actor).isEmpty()) {
                    problems.add("an AI task runs as the account that saves it; sign in with an account");
                }
            }
            case Schedule.DISPATCH -> {
                if (!DISPATCHABLE.containsAll(body.dispatchStatuses())) {
                    problems.add("dispatchStatuses must be among " + new java.util.TreeSet<>(DISPATCHABLE));
                }
                if (body.maxDispatch() > 50) {
                    problems.add("maxDispatch is at most 50");
                }
            }
            default -> problems.add("kind must be sop, assistant or dispatch");
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
                null,
                kind,
                Schedule.ASSISTANT.equals(kind) ? body.prompt().strip() : null,
                Schedule.DISPATCH.equals(kind) ? body.dispatchStatuses() : List.of(),
                body.maxDispatch(),
                // the task works as whoever saved it last (upstream: 执行身份由最后保存任务的人决定)
                actor));
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
        Outcome outcome = execute(schedule, "cron", "schedule", now);
        // the claim moved only the indexed column; the payload follows with the same slot
        Schedule latest = store.find(schedule.id()).orElse(schedule);
        store.save(latest.fired(now, latest.enabled() ? next : null, outcome.runId(), outcome.error()));
    }

    /** Manual trigger: runs the task now without moving the cron slot. */
    public void runNow(Schedule schedule, String actor) {
        Instant now = clock.instant();
        Outcome outcome = execute(schedule, "manual", actor, now);
        Schedule latest = store.find(schedule.id()).orElse(schedule);
        store.save(latest.fired(now, latest.nextRunAt(), outcome.runId(), outcome.error()));
    }

    private record Outcome(String runId, String error) {}

    private Outcome execute(Schedule schedule, String trigger, String actor, Instant now) {
        return switch (schedule.kind()) {
            case Schedule.DISPATCH -> dispatch(schedule, trigger, actor, now);
            case Schedule.ASSISTANT -> assistantTask(schedule, trigger, actor, now);
            default -> sop(schedule, trigger, actor, now);
        };
    }

    private Outcome sop(Schedule schedule, String trigger, String actor, Instant now) {
        String runId = null;
        String error = null;
        if ("cron".equals(trigger) && schedule.skipIfRunning() && previousActive(schedule)) {
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
        executions.record(schedule, trigger, runId, error, actor, now);
        return new Outcome(runId, error);
    }

    /**
     * Starts the SOP of each requirement in the chosen statuses, most urgent first (upstream trigger
     * configuration). Requirements whose latest run is still active are left alone.
     */
    private Outcome dispatch(Schedule schedule, String trigger, String actor, Instant now) {
        io.github.loopforge4j.server.account.CurrentUser runAs;
        try {
            runAs = principal(schedule);
        } catch (RuntimeException e) {
            executions.record(schedule, trigger, "failed", List.of(), null, e.getMessage(), actor, now);
            return new Outcome(null, e.getMessage());
        }
        List<Requirement> due = requirementStore.list(schedule.projectId(), null).stream()
                .filter(r -> schedule.dispatchStatuses().contains(r.status()))
                .filter(r -> !active(r))
                .sorted(java.util.Comparator.comparingInt((Requirement r) -> PRIORITY.indexOf(r.priority()))
                        .thenComparingInt(Requirement::number))
                .toList();
        List<String> runIds = new ArrayList<>();
        List<String> started = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Requirement r : due) {
            if (runIds.size() >= schedule.maxDispatch()) {
                break;
            }
            String workflow =
                    schedule.workflowId() != null && !schedule.workflowId().isBlank()
                            ? schedule.workflowId()
                            : r.workflowId();
            if (workflow == null || workflow.isBlank()) {
                problems.add("#" + r.number() + " 没有绑定 SOP");
                continue;
            }
            try {
                if (runAs != null) {
                    access.requireIsolatedSandbox(runAs, workflow);
                }
                Run run = requirements
                        .getObject()
                        .start(schedule.projectId(), r.number(), workflow, null, "schedule:" + schedule.id());
                runIds.add(run.id());
                started.add("#" + r.number());
            } catch (RuntimeException e) {
                problems.add("#" + r.number() + "：" + e.getMessage());
            }
        }
        String reply = runIds.isEmpty()
                ? (due.isEmpty() ? "没有待派发的需求" : "没有派发")
                : "派发 " + runIds.size() + " 个需求：" + String.join(" ", started);
        String error = problems.isEmpty() ? null : String.join("；", problems);
        String status = !runIds.isEmpty() ? "started" : problems.isEmpty() ? "skipped" : "failed";
        executions.record(schedule, trigger, status, runIds, reply, error, actor, now);
        return new Outcome(runIds.isEmpty() ? null : runIds.getFirst(), error);
    }

    private static final List<String> PRIORITY = List.of("urgent", "high", "medium", "low");

    private boolean active(Requirement r) {
        if (r.latestRunId() == null) {
            return false;
        }
        try {
            return !engine.require(r.latestRunId()).status().terminal();
        } catch (RunNotFoundException e) {
            return false;
        }
    }

    /**
     * Runs the task's prompt as an assistant turn in the project, as the account that last saved it;
     * the execution stays {@code running} until the turn ends.
     */
    private Outcome assistantTask(Schedule schedule, String trigger, String actor, Instant now) {
        io.github.loopforge4j.server.account.User owner = users.byUsername(schedule.responsible())
                .filter(u -> !u.disabled())
                .orElse(null);
        if (owner == null) {
            String error = "执行账号 " + schedule.responsible() + " 不存在或已停用";
            executions.record(schedule, trigger, "failed", List.of(), null, error, actor, now);
            return new Outcome(null, error);
        }
        java.util.concurrent.atomic.AtomicLong execution = new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.CountDownLatch recorded = new java.util.concurrent.CountDownLatch(1);
        try {
            String title = schedule.name() + " · "
                    + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
                            .format(now.atZone(ZoneId.of(schedule.zone())));
            String sessionId = assistant
                    .getObject()
                    .runScheduled(schedule.projectId(), owner, title, schedule.prompt(), outcome -> {
                        try {
                            recorded.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        Instant end = clock.instant();
                        List<String> runIds = engine
                                .list(new io.github.loopforge4j.core.store.RunQuery(
                                        java.util.Set.of(),
                                        null,
                                        null,
                                        100,
                                        java.util.Set.of(schedule.projectId()),
                                        null))
                                .stream()
                                .filter(r -> !r.context().childRun()
                                        && owner.username().equals(r.context().triggeredBy())
                                        && !r.createdAt().isBefore(now)
                                        && !r.createdAt().isAfter(end))
                                .map(Run::id)
                                .toList();
                        executions.finish(
                                execution.get(),
                                outcome.failed() ? "failed" : "succeeded",
                                runIds,
                                outcome.reply(),
                                outcome.failed() ? outcome.error() : null,
                                end);
                    });
            execution.set(executions.begin(schedule, trigger, sessionId, actor, now));
            recorded.countDown();
            return new Outcome(null, null);
        } catch (RuntimeException e) {
            recorded.countDown();
            executions.record(schedule, trigger, "failed", List.of(), null, e.getMessage(), actor, now);
            return new Outcome(null, e.getMessage());
        }
    }

    /** Turns left running by a stopped instance are marked failed. */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
    public void failInterrupted() {
        Instant now = clock.instant();
        executions.failInterrupted(now.minus(java.time.Duration.ofHours(2)), now);
    }

    /** Who a dispatch acts as: the last saver's account, or {@code null} for the service token. */
    private io.github.loopforge4j.server.account.CurrentUser principal(Schedule schedule) {
        String name = schedule.responsible();
        var user = users.byUsername(name).filter(u -> !u.disabled());
        if (user.isEmpty()) {
            if (io.github.loopforge4j.server.account.CurrentUser.SERVICE
                    .username()
                    .equals(name)) {
                return null;
            }
            throw new IllegalStateException("执行账号 " + name + " 不存在或已停用");
        }
        var current = io.github.loopforge4j.server.account.CurrentUser.of(user.get());
        access.requireProject(
                current, schedule.projectId(), io.github.loopforge4j.server.account.ProjectRole.DEVELOPER);
        return current;
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
