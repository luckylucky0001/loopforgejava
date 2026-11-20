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
    /** Writes deliveries back to synced trackers; looked up lazily (it depends on this package). */
    private final org.springframework.beans.factory.ObjectProvider<io.github.loopforge4j.server.tracker.TrackerService>
            trackers;

    private final io.github.loopforge4j.server.account.ProjectStore projects;
    private final io.github.loopforge4j.core.store.CatalogStore catalog;

    public RequirementService(
            RequirementStore store,
            org.springframework.beans.factory.ObjectProvider<RunEngine> engines,
            Clock clock,
            org.springframework.beans.factory.ObjectProvider<io.github.loopforge4j.server.tracker.TrackerService>
                    trackers,
            io.github.loopforge4j.server.account.ProjectStore projects,
            io.github.loopforge4j.core.store.CatalogStore catalog) {
        this.projects = projects;
        this.catalog = catalog;
        this.store = store;
        this.engines = engines;
        this.clock = clock;
        this.trackers = trackers;
    }

    public Requirement create(String projectId, Requirement body, String actor) {
        validate(body);
        String playbookId = body.playbookId();
        List<SopBinding> bindings = body.sopBindings() == null ? null : chain(projectId, body.sopBindings());
        String workflowId = body.workflowId();
        if (bindings == null && (workflowId == null || workflowId.isBlank())) {
            // a new requirement starts with the project's chosen or default SOP chain (upstream playbook)
            List<SopPlaybook> books = playbooks(projectId);
            String wanted = playbookId;
            SopPlaybook book = books.stream()
                    .filter(b -> b.id().equals(wanted))
                    .findFirst()
                    .orElse(books.stream()
                            .filter(SopPlaybook::isDefault)
                            .findFirst()
                            .orElse(null));
            if (book != null && !book.bindings().isEmpty()) {
                playbookId = book.id();
                bindings = book.bindings();
                workflowId = bindings.getFirst().workflowId();
            }
        } else if (bindings != null && !bindings.isEmpty()) {
            workflowId = bindings.getFirst().workflowId();
        }
        final String chosenPlaybook = playbookId;
        final List<SopBinding> chosenBindings = bindings;
        final String chosenWorkflow = workflowId;
        return store.create(new Requirement(
                null,
                projectId,
                0,
                body.title().strip(),
                body.description(),
                body.status(),
                body.priority(),
                body.assignee(),
                chosenWorkflow,
                body.repositoryIds(),
                body.baseBranch(),
                null,
                null,
                null,
                "local",
                null,
                actor,
                null,
                null,
                chosenPlaybook,
                chosenBindings));
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
                                current.source(),
                                current.external(),
                                current.createdBy(),
                                current.createdAt(),
                                null,
                                current.playbookId(),
                                current.sopBindings()))
                .orElseThrow();
    }

    public Requirement require(String projectId, int number) {
        return store.find(projectId, number)
                .orElseThrow(() -> new RunNotFoundException("requirement #" + number + " not found"));
    }

    /** Compatibility: start the requirement's default SOP lane for the first time. */
    public Run start(String projectId, int number, String workflowId, String extraInput, String actor) {
        return start(projectId, number, workflowId, extraInput, actor, false);
    }

    /**
     * Starts one SOP lane of the requirement on its repositories (upstream issue dispatch). Each SOP
     * runs once per requirement; running it again is an explicit rerun, and lanes of different SOPs
     * run side by side. The run's input names the deliverables of the steps it continues from.
     */
    public Run start(String projectId, int number, String workflowId, String extraInput, String actor, boolean rerun) {
        Requirement requirement = require(projectId, number);
        if (MANUAL.contains(requirement.status()) || "done".equals(requirement.status())) {
            throw new RunStateException("requirement #" + number + " is " + requirement.status());
        }
        String workflow = workflowId != null && !workflowId.isBlank() ? workflowId.strip() : null;
        if (workflow == null) {
            List<SopBinding> chain = requirement.effectiveBindings();
            workflow = chain.isEmpty() ? null : chain.getFirst().workflowId();
        }
        if (workflow == null) {
            throw new WorkflowValidationException(
                    List.of("workflowId is required (on the requirement or the request)"));
        }
        List<Run> lane = laneRuns(requirement, workflow);
        if (lane.stream().anyMatch(r -> !r.status().terminal())) {
            throw new RunStateException("该需求的这条 SOP 正在运行（" + workflow + "）");
        }
        if (!rerun && !lane.isEmpty()) {
            throw new RunStateException("该需求的这条 SOP 已经跑过，如需重新执行请明确选择重新运行");
        }
        if (rerun && lane.isEmpty()) {
            throw new WorkflowValidationException(List.of("该需求尚未运行这条 SOP，不能使用重新运行"));
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
        input.append(handoff(requirement, workflow));
        int generation = lane.size();
        if (!store.reserveLane(requirement.id(), workflow, generation)) {
            throw new RunStateException("该需求的这条 SOP 正在创建或已经启动，请刷新后再试");
        }
        Run run;
        try {
            run = engines.getObject()
                    .start(
                            workflow,
                            input.toString(),
                            new ExecutionContext(projectId, actor, null, null, null),
                            selections);
        } catch (RuntimeException e) {
            store.releaseLane(requirement.id(), workflow, generation);
            throw e;
        }
        store.linkRun(requirement.id(), run.id(), workflow);
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

    /** The requirement's runs of one SOP lane. */
    List<Run> laneRuns(Requirement requirement, String workflowId) {
        return runs(requirement).stream()
                .filter(r -> r.workflowId().equals(workflowId))
                .toList();
    }

    /** The requirement's top-level runs that still exist, oldest first. */
    List<Run> runs(Requirement requirement) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(requirement.runIds());
        store.linkedRuns(requirement.id()).forEach(l -> ids.add(l.runId()));
        RunEngine engine = engines.getObject();
        List<Run> out = new ArrayList<>();
        List<String> recent = new ArrayList<>(ids);
        for (String id : recent.subList(Math.max(0, recent.size() - 200), recent.size())) {
            try {
                out.add(engine.require(id));
            } catch (RunNotFoundException e) {
                // deleted by retention or a person
            }
        }
        out.sort(java.util.Comparator.comparing(Run::createdAt));
        return out;
    }

    /** Upstream {@code appendSOPHandoff}: what the lane continues from. */
    static String handoff(Requirement requirement, String workflowId) {
        List<SopBinding> chain = requirement.effectiveBindings();
        int index = -1;
        for (int i = 0; i < chain.size(); i++) {
            if (chain.get(i).workflowId().equals(workflowId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return "";
        }
        SopBinding current = chain.get(index);
        List<SopBinding> previous = new ArrayList<>();
        if (!current.dependsOn().isEmpty()) {
            chain.stream().filter(b -> current.dependsOn().contains(b.id())).forEach(previous::add);
        } else if (index > 0) {
            previous.add(chain.get(index - 1));
        }
        if (previous.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\n## 前序 SOP 交接\n\n本需求的前序 SOP 步骤：\n");
        for (SopBinding b : previous) {
            text.append("- ").append(b.workflowId());
            if (!current.handoff().isEmpty()) {
                text.append(" → 预期产物：").append(String.join("、", current.handoff()));
            }
            text.append('\n');
        }
        text.append("\n在这些交付物的基础上继续，不要重做前序 SOP。\n");
        return text.toString();
    }

    /** The requirement with its SOP lanes, node stages and totals. */
    public RequirementSnapshot snapshot(String projectId, int number) {
        Requirement requirement = require(projectId, number);
        RunEngine engine = engines.getObject();
        return RequirementSnapshot.build(
                requirement,
                runs(requirement),
                new RequirementSnapshot.Source() {
                    @Override
                    public java.util.Optional<Run> run(String runId) {
                        try {
                            return java.util.Optional.of(engine.require(runId));
                        } catch (RunNotFoundException e) {
                            return java.util.Optional.empty();
                        }
                    }

                    @Override
                    public java.util.Optional<io.github.loopforge4j.core.domain.WorkflowDefinition> workflow(
                            String id) {
                        return catalog.workflow(id).filter(w -> visible(w, projectId));
                    }
                },
                clock.instant());
    }

    // ------------------------------------------------------------------ SOP chains

    public List<SopPlaybook> playbooks(String projectId) {
        return projects.playbooks(projectId);
    }

    public List<SopPlaybook> savePlaybooks(String projectId, List<SopPlaybook> books) {
        if (books != null && books.size() > 20) {
            throw new WorkflowValidationException(List.of("at most 20 playbooks"));
        }
        List<SopPlaybook> normalized = SopPlaybook.normalize(books);
        List<SopPlaybook> checked = new ArrayList<>();
        for (SopPlaybook book : normalized) {
            if (book.name().length() > 100) {
                throw new WorkflowValidationException(List.of("playbook name is too long"));
            }
            checked.add(new SopPlaybook(book.id(), book.name(), book.isDefault(), chain(projectId, book.bindings())));
        }
        projects.savePlaybooks(projectId, checked);
        return checked;
    }

    /** The requirement's chain, its playbook and the project's playbooks. */
    public java.util.Map<String, Object> sops(String projectId, int number) {
        Requirement requirement = require(projectId, number);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("playbookId", requirement.playbookId());
        out.put("playbooks", playbooks(projectId));
        out.put("bindings", requirement.effectiveBindings());
        return out;
    }

    /** Chooses a playbook (its chain is copied unless bindings are given) and/or sets the chain. */
    public Requirement setSops(String projectId, int number, String playbookId, List<SopBinding> bindings) {
        Requirement requirement = require(projectId, number);
        String chosen = requirement.playbookId();
        List<SopBinding> chain = requirement.sopBindings();
        if (playbookId != null && !playbookId.isBlank()) {
            SopPlaybook book = playbooks(projectId).stream()
                    .filter(b -> b.id().equals(playbookId.strip()))
                    .findFirst()
                    .orElseThrow(() -> new WorkflowValidationException(List.of("未知的需求套餐")));
            chosen = book.id();
            if (bindings == null) {
                chain = book.bindings();
            }
        }
        if (bindings != null) {
            chain = chain(projectId, bindings);
        }
        final String finalPlaybook = chosen;
        final List<SopBinding> finalChain = chain;
        return store.update(requirement.id(), current -> current.withChain(finalPlaybook, finalChain))
                .orElseThrow();
    }

    /** Normalizes a chain and checks that every SOP exists for the project. */
    List<SopBinding> chain(String projectId, List<SopBinding> bindings) {
        if (bindings.size() > 20) {
            throw new WorkflowValidationException(List.of("at most 20 SOP steps"));
        }
        List<SopBinding> normalized = SopBinding.normalize(bindings);
        List<String> problems = new ArrayList<>();
        for (SopBinding b : normalized) {
            if (b.id().length() > 100 || b.workflowId().length() > 200) {
                problems.add("SOP step id is too long");
            } else if (catalog.workflow(b.workflowId())
                    .filter(w -> visible(w, projectId))
                    .isEmpty()) {
                problems.add("SOP '" + b.workflowId() + "' not found");
            }
            if (b.assignee() != null && b.assignee().length() > 128) {
                problems.add("assignee is too long");
            }
            if (b.handoff().size() > 20 || b.handoff().stream().anyMatch(h -> h.length() > 200)) {
                problems.add("handoff lists at most 20 deliverables of 200 characters");
            }
        }
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
        return normalized;
    }

    private static boolean visible(io.github.loopforge4j.core.domain.WorkflowDefinition w, String projectId) {
        return w.projectId() == null || w.projectId().isBlank() || w.projectId().equals(projectId);
    }

    /**
     * Follows a requirement's runs: its status comes from the latest run of every SOP lane, its
     * deliveries are those lanes' pull requests.
     */
    @Override
    public void runChanged(Run run) {
        if (run.context().childRun() || run.context().projectId() == null) {
            return;
        }
        try {
            store.byRun(run.id()).ifPresent(requirement -> {
                if (MANUAL.contains(requirement.status())) {
                    return;
                }
                java.util.Map<String, Run> latest = new java.util.LinkedHashMap<>();
                List<Run> all = new ArrayList<>(runs(requirement));
                if (all.stream().noneMatch(r -> r.id().equals(run.id()))) {
                    all.add(run);
                }
                for (Run r : all) {
                    Run current = r.id().equals(run.id()) ? run : r;
                    latest.merge(
                            current.workflowId(),
                            current,
                            (a, b) -> b.createdAt().isBefore(a.createdAt()) ? a : b);
                }
                String status = aggregateStatus(latest.values());
                java.util.Map<String, PullRequest> byUrl = new java.util.LinkedHashMap<>();
                latest.values().forEach(r -> r.deliveries().forEach(p -> byUrl.putIfAbsent(p.url(), p)));
                List<PullRequest> deliveries = byUrl.isEmpty() ? requirement.deliveries() : List.copyOf(byUrl.values());
                if (!status.equals(requirement.status()) || !deliveries.equals(requirement.deliveries())) {
                    store.update(
                                    requirement.id(),
                                    current ->
                                            current.with(status, current.runIds(), current.latestRunId(), deliveries))
                            .ifPresent(updated -> {
                                var tracker = trackers.getIfAvailable();
                                if (tracker != null) {
                                    tracker.deliveriesChanged(updated);
                                }
                            });
                }
            });
        } catch (RuntimeException e) {
            log.warn("cannot update the requirement of run {}: {}", run.id(), e.toString());
        }
    }

    /** Any lane running wins, then any failed lane; all lanes done decide by their deliveries. */
    static String aggregateStatus(java.util.Collection<Run> latestPerLane) {
        List<String> states =
                latestPerLane.stream().map(RequirementService::statusFor).toList();
        if (states.contains("in_progress")) {
            return "in_progress";
        }
        if (states.contains("blocked")) {
            return "blocked";
        }
        if (states.stream().allMatch("todo"::equals)) {
            return "todo";
        }
        boolean anyOpen =
                latestPerLane.stream().flatMap(r -> r.deliveries().stream()).anyMatch(PullRequest::open);
        boolean anyMerged =
                latestPerLane.stream().flatMap(r -> r.deliveries().stream()).anyMatch(p -> "merged".equals(p.state()));
        return anyMerged && !anyOpen ? "done" : "in_review";
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
