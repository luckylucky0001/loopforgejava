package io.github.loopforge4j.server.eval;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.engine.Conversations;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.engine.RunStateException;
import io.github.loopforge4j.core.eval.RunEvalApply;
import io.github.loopforge4j.core.eval.RunEvalFacts;
import io.github.loopforge4j.core.eval.RunEvalPrompt;
import io.github.loopforge4j.core.eval.RunEvaluation;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.assistant.AssistantStreams;
import io.github.loopforge4j.server.catalog.CatalogService;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.web.ApiException;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI evaluation of settled runs (upstream run eval): the evaluator scores the run's facts on the
 * policy's dimensions, reports findings and proposes bounded changes that a person may apply to the
 * current SOP or role. It runs with the requester's credentials, in the background.
 */
@Service
public class RunEvalService {

    private static final Logger log = LoggerFactory.getLogger(RunEvalService.class);

    private final RunEvalStore store;
    private final ObjectProvider<RunEngine> engines;
    private final CatalogStore catalog;
    private final CatalogService catalogService;
    private final AccessPolicy access;
    private final AssistantStreams streams;
    private final LoopForgeProperties.Assistant config;
    private final Clock clock;

    public RunEvalService(
            RunEvalStore store,
            ObjectProvider<RunEngine> engines,
            CatalogStore catalog,
            CatalogService catalogService,
            AccessPolicy access,
            AssistantStreams streams,
            LoopForgeProperties properties,
            Clock clock) {
        this.store = store;
        this.engines = engines;
        this.catalog = catalog;
        this.catalogService = catalogService;
        this.access = access;
        this.streams = streams;
        this.config = properties.assistant();
        this.clock = clock;
    }

    static String topic(String runId) {
        return "eval:" + runId;
    }

    public RunEval latest(String runId, Principal principal) {
        readable(runId, principal);
        List<RunEval> evals = store.list(runId);
        return evals.isEmpty() ? null : evals.getFirst();
    }

    public List<RunEval> list(String runId, Principal principal) {
        readable(runId, principal);
        return store.list(runId);
    }

    public SseEmitter stream(String runId, Principal principal) {
        readable(runId, principal);
        return streams.subscribe(topic(runId));
    }

    public RunEval start(String runId, Principal principal) {
        Run run = readable(runId, principal);
        CurrentUser user = access.requireHuman(principal, "start evaluations");
        if (!config.enabled()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "the assistant is disabled");
        }
        if (!run.status().terminal()) {
            throw new RunStateException("only settled runs can be evaluated; run " + runId + " is "
                    + run.status().wire());
        }
        access.requireIsolatedProfile(user, config.sandboxProfile());
        RunEval eval = new RunEval(
                "eval_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                runId,
                "running",
                user.username(),
                0,
                false,
                RunEvaluation.Policy.DEFAULT.threshold(),
                Map.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                0,
                0,
                clock.instant(),
                null);
        Instant stale = clock.instant().minus(config.clarifyTimeout()).minus(Duration.ofMinutes(2));
        if (!store.start(eval, stale)) {
            throw new ApiException(HttpStatus.CONFLICT, "this run is being evaluated");
        }
        streams.publish(topic(runId), "status", Map.of("status", "running", "evalId", eval.id()));
        Thread.ofVirtual().name("eval-" + eval.id()).start(() -> evaluate(run, eval, user.username()));
        return eval;
    }

    private void evaluate(Run run, RunEval eval, String requester) {
        String topic = topic(run.id());
        List<String> activity = new ArrayList<>();
        long tokens = 0;
        double cost = 0;
        RunEngine engine = engines.getObject();
        Conversations conversations = engine.conversations();
        String workspace = "eval-" + eval.id();
        try {
            RunEvaluation.Facts facts =
                    RunEvalFacts.build(run, engine.events(run.id(), 0, 0), engine.artifacts(run.id()));
            WorkflowDefinition current = catalog.workflow(run.workflowId()).orElse(run.workflow());
            RunEvaluation.Catalog editable = RunEvalFacts.catalog(current, catalog::agent);
            String prompt = RunEvalPrompt.build(RunEvaluation.Policy.DEFAULT, facts, editable);
            AgentDefinition evaluator = AgentDefinition.builder("run-evaluator")
                    .name("运行评测员")
                    .executor(config.executor() == null || config.executor().isBlank() ? null : config.executor())
                    .build();
            String session = null;
            RunEvaluation.Result result = null;
            String problem = null;
            for (int attempt = 0; attempt < 2 && result == null; attempt++) {
                Conversations.Reply reply = conversations.turn(
                        new Conversations.Turn(
                                workspace,
                                config.sandboxProfile(),
                                evaluator,
                                prompt,
                                session,
                                config.clarifyTimeout(),
                                List.of(),
                                List.of(),
                                Map.of("LOOPFORGE_RUN_ID", run.id()),
                                requester),
                        telemetry -> {
                            if (telemetry.message() != null
                                    && Set.of("tool", "process", "runtime").contains(telemetry.category())) {
                                String line = telemetry.category() + " · "
                                        + telemetry
                                                .message()
                                                .strip()
                                                .lines()
                                                .findFirst()
                                                .orElse("");
                                synchronized (activity) {
                                    if (activity.size() < 60) {
                                        activity.add(line.length() > 300 ? line.substring(0, 300) + "…" : line);
                                    }
                                }
                                streams.publish(topic, "activity", line);
                            }
                        },
                        Cancellation.create());
                session = reply.sessionId() != null ? reply.sessionId() : session;
                tokens += reply.usage().totalTokens();
                cost += reply.usage().costUsd();
                if (reply.failed()) {
                    problem = "评测失败：" + clip(String.valueOf(reply.text()), 300);
                    break;
                }
                try {
                    result = RunEvalPrompt.parse(reply.text(), RunEvaluation.Policy.DEFAULT);
                } catch (RunEvalPrompt.InvalidResult e) {
                    problem = "评测结果无效：" + e.getMessage();
                    if (session == null) {
                        break;
                    }
                    prompt = "刚才的结果无效（" + e.getMessage() + "）。请只输出约定的 JSON 对象，覆盖全部维度并给出 summary。";
                }
            }
            RunEval done = result != null
                    ? eval.finished(result, snapshot(activity), tokens, cost, clock.instant())
                    : eval.failed(problem, snapshot(activity), tokens, cost, clock.instant());
            store.save(done);
            streams.publish(topic, "done", done);
        } catch (Exception e) {
            String message = e.getClass().getSimpleName() + ": " + clip(String.valueOf(e.getMessage()), 300);
            log.warn("evaluation {} of run {} failed: {}", eval.id(), run.id(), message);
            RunEval failed = eval.failed(message, snapshot(activity), tokens, cost, clock.instant());
            store.save(failed);
            streams.publish(topic, "done", failed);
        } finally {
            try {
                conversations.release(workspace, config.sandboxProfile());
            } catch (Exception e) {
                log.debug("cannot remove evaluation workspace {}: {}", workspace, e.toString());
            }
        }
    }

    /** What to apply. */
    public record Apply(String evalId, List<String> recommendationIds) {}

    /**
     * Applies chosen recommendations to the current SOP or roles (never to the run's snapshot):
     * SOP changes need maintainer rights on the SOP's project, role changes administrator rights.
     * Each change is a new revision of the definition.
     */
    public RunEval apply(String runId, Apply body, Principal principal) {
        Run run = readable(runId, principal);
        access.requireHuman(principal, "apply evaluation recommendations");
        RunEval eval = store.find(body.evalId())
                .filter(e -> e.runId().equals(runId))
                .orElseThrow(() -> new io.github.loopforge4j.core.engine.RunNotFoundException(
                        "evaluation '" + body.evalId() + "' not found"));
        if (!"done".equals(eval.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "the evaluation has not finished");
        }
        Set<String> chosen = body.recommendationIds() == null ? Set.of() : Set.copyOf(body.recommendationIds());
        Map<String, RunEvaluation.Recommendation> updated = new LinkedHashMap<>();
        eval.recommendations().forEach(r -> updated.put(r.id(), r));
        for (RunEvaluation.Recommendation rec : eval.recommendations()) {
            if (!chosen.contains(rec.id()) || "applied".equals(rec.status())) {
                continue;
            }
            try {
                String target;
                if ("update_node".equals(rec.action())) {
                    WorkflowDefinition current = catalog.workflow(run.workflowId())
                            .orElseThrow(() ->
                                    new IllegalArgumentException("SOP '" + run.workflowId() + "' no longer exists"));
                    access.requireProject(principal, current.projectId(), ProjectRole.MAINTAINER);
                    WorkflowDefinition changed = RunEvalApply.apply(rec, current);
                    access.requireIsolatedSandbox(principal, changed);
                    target = catalogService.saveWorkflow(changed).id();
                } else {
                    String agentId = rec.targetId();
                    AgentDefinition current = catalog.agent(agentId == null ? "" : agentId)
                            .orElseThrow(() -> new IllegalArgumentException("role '" + agentId + "' not found"));
                    // shared roles belong to administrators, a project's roles to its maintainers
                    access.requireProject(principal, current.projectId(), ProjectRole.MAINTAINER);
                    target = catalogService
                            .saveAgent(RunEvalApply.apply(rec, current))
                            .id();
                }
                updated.put(rec.id(), rec.withOutcome("applied", target, null));
            } catch (AccessPolicy.AccessDeniedException e) {
                throw e;
            } catch (RuntimeException e) {
                updated.put(rec.id(), rec.withOutcome("failed", null, clip(e.getMessage(), 300)));
            }
        }
        RunEval saved = eval.withRecommendations(List.copyOf(updated.values()));
        store.save(saved);
        streams.publish(topic(runId), "done", saved);
        return saved;
    }

    private Run readable(String runId, Principal principal) {
        Run run = engines.getObject().require(runId);
        access.requireReadRun(principal, run);
        return run;
    }

    private static List<String> snapshot(List<String> activity) {
        synchronized (activity) {
            return List.copyOf(activity);
        }
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
