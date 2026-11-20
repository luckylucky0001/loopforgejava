package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.BranchMode;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositorySelection;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.engine.RunListener;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.RunStateException;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Requirement use cases and the run → requirement status bridge. */
@Service
public class RequirementService implements RunListener {

    private static final Logger log = LoggerFactory.getLogger(RequirementService.class);
    private static final DateTimeFormatter BRANCH_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    /** States a person set deliberately; a run outcome does not override them. */
    private static final Set<String> MANUAL = Set.of("closed", "cancelled");

    private final RequirementStore store;
    /** The engine also notifies this listener, so it is looked up lazily (RunEngine is final, no proxy). */
    private final org.springframework.beans.factory.ObjectProvider<RunEngine> engines;

    private final Clock clock;

    public RequirementService(
            RequirementStore store, org.springframework.beans.factory.ObjectProvider<RunEngine> engines, Clock clock) {
        this.store = store;
        this.engines = engines;
        this.clock = clock;
    }

    public Requirement create(String projectId, Requirement body, String actor) {
        validate(body);
        return store.create(new Requirement(
                null,
                projectId,
                0,
                body.title().strip(),
                body.description(),
                body.status(),
                body.priority(),
                body.assignee(),
                body.workflowId(),
                body.repositoryIds(),
                body.baseBranch(),
                null,
                null,
                null,
                actor,
                null,
                null));
    }

    public Requirement update(String projectId, int number, Requirement body) {
        validate(body);
        Requirement existing = require(projectId, number);
        return store.update(
                        existing.id(),
                        current -> new Requirement(
                                current.id(),
                                current.projectId(),
                                current.number(),
                                body.title().strip(),
                                body.description(),
                                body.status(),
                                body.priority(),
                                body.assignee(),
                                body.workflowId(),
                                body.repositoryIds(),
                                body.baseBranch(),
                                current.runIds(),
                                current.latestRunId(),
                                current.deliveries(),
                                current.createdBy(),
                                current.createdAt(),
                                null))
                .orElseThrow();
    }

    public Requirement require(String projectId, int number) {
        return store.find(projectId, number)
                .orElseThrow(() -> new RunNotFoundException("requirement #" + number + " not found"));
    }

    /**
     * Starts an SOP for the requirement on its repositories (upstream issue dispatch). The task input
     * is the requirement itself; every repository gets the requirement's work branch.
     */
    public Run start(String projectId, int number, String workflowId, String extraInput, String actor) {
        Requirement requirement = require(projectId, number);
        if (MANUAL.contains(requirement.status()) || "done".equals(requirement.status())) {
            throw new RunStateException("requirement #" + number + " is " + requirement.status());
        }
        String workflow = workflowId != null && !workflowId.isBlank() ? workflowId : requirement.workflowId();
        if (workflow == null || workflow.isBlank()) {
            throw new WorkflowValidationException(
                    List.of("workflowId is required (on the requirement or the request)"));
        }
        if (requirement.latestRunId() != null) {
            try {
                Run latest = engines.getObject().require(requirement.latestRunId());
                if (!latest.status().terminal()) {
                    throw new RunStateException("requirement #" + number + " already has an active run " + latest.id());
                }
            } catch (RunNotFoundException e) {
                // deleted by retention
            }
        }
        String branch = "feature/requirement-" + number + "-"
                + BRANCH_TIME.format(clock.instant().atOffset(ZoneOffset.UTC));
        List<RepositorySelection> selections = requirement.repositoryIds().stream()
                .map(id -> new RepositorySelection(id, requirement.baseBranch(), branch, BranchMode.AUTO))
                .toList();
        StringBuilder input = new StringBuilder("# 需求 #")
                .append(number)
                .append("：")
                .append(requirement.title())
                .append('\n');
        if (requirement.description() != null && !requirement.description().isBlank()) {
            input.append('\n').append(requirement.description().strip()).append('\n');
        }
        if (extraInput != null && !extraInput.isBlank()) {
            input.append("\n## 补充说明\n\n").append(extraInput.strip()).append('\n');
        }
        Run run = engines.getObject()
                .start(
                        workflow,
                        input.toString(),
                        new ExecutionContext(projectId, actor, null, null, null),
                        selections);
        store.update(requirement.id(), current -> {
            List<String> runs = new ArrayList<>(current.runIds());
            runs.add(run.id());
            return current.with("in_progress", runs, run.id(), current.deliveries());
        });
        // a run that settled before it was linked (fast failure) is reflected now
        try {
            runChanged(engines.getObject().require(run.id()));
        } catch (RunNotFoundException e) {
            // deleted meanwhile
        }
        return run;
    }

    /** Follows the latest run of a requirement: status and delivered pull requests. */
    @Override
    public void runChanged(Run run) {
        if (run.context().childRun() || run.context().projectId() == null) {
            return;
        }
        try {
            store.byLatestRun(run.id()).ifPresent(requirement -> {
                if (MANUAL.contains(requirement.status())) {
                    return;
                }
                String status = statusFor(run);
                List<PullRequest> deliveries = run.deliveries().isEmpty() ? requirement.deliveries() : run.deliveries();
                if (!status.equals(requirement.status()) || !deliveries.equals(requirement.deliveries())) {
                    store.update(
                            requirement.id(),
                            current -> current.with(status, current.runIds(), current.latestRunId(), deliveries));
                }
            });
        } catch (RuntimeException e) {
            log.warn("cannot update the requirement of run {}: {}", run.id(), e.toString());
        }
    }

    static String statusFor(Run run) {
        RunStatus status = run.status();
        return switch (status) {
            case QUEUED, RUNNING, WAITING -> "in_progress";
            case FAILED -> "blocked";
            case CANCELLED -> "todo";
            case SUCCEEDED -> {
                boolean anyOpen = run.deliveries().stream().anyMatch(PullRequest::open);
                boolean anyMerged = run.deliveries().stream().anyMatch(p -> "merged".equals(p.state()));
                yield anyMerged && !anyOpen ? "done" : "in_review";
            }
        };
    }

    private static void validate(Requirement body) {
        List<String> problems = new ArrayList<>();
        if (body.title() == null || body.title().isBlank()) {
            problems.add("title is required");
        } else if (body.title().length() > 500) {
            problems.add("title is too long");
        }
        if (body.description() != null && body.description().length() > 100_000) {
            problems.add("description is too long");
        }
        if (!Requirement.STATUSES.contains(body.status())) {
            problems.add("status must be one of " + new java.util.TreeSet<>(Requirement.STATUSES));
        }
        if (!Requirement.PRIORITIES.contains(body.priority())) {
            problems.add("priority must be one of " + new java.util.TreeSet<>(Requirement.PRIORITIES));
        }
        if (body.repositoryIds().size() > 20) {
            problems.add("at most 20 repositories");
        }
        if (body.baseBranch() != null
                && !body.baseBranch().isBlank()
                && !io.github.loopforge4j.core.runtime.git.GitWorkspace.validBranch(body.baseBranch())) {
            problems.add("baseBranch is invalid");
        }
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
    }
}
