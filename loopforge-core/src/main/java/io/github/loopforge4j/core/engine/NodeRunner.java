package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.delivery.Deliveries;
import io.github.loopforge4j.core.delivery.DeliveryException;
import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.ExecutionScope;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.GateDecision;
import io.github.loopforge4j.core.domain.GateMode;
import io.github.loopforge4j.core.domain.GateResult;
import io.github.loopforge4j.core.domain.HumanInterventionPoint;
import io.github.loopforge4j.core.domain.InterventionStage;
import io.github.loopforge4j.core.domain.LoopExhaustedPolicy;
import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.ScriptLanguage;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.runtime.executor.AgentExecutor;
import io.github.loopforge4j.core.runtime.executor.AgentInvocation;
import io.github.loopforge4j.core.runtime.executor.AgentResult;
import io.github.loopforge4j.core.runtime.executor.ExecutorException;
import io.github.loopforge4j.core.runtime.git.GitEnvironment;
import io.github.loopforge4j.core.runtime.git.GitException;
import io.github.loopforge4j.core.runtime.git.GitWorkspace;
import io.github.loopforge4j.core.runtime.materialize.AgentAssets;
import io.github.loopforge4j.core.runtime.materialize.AgentMaterializer;
import io.github.loopforge4j.core.runtime.materialize.CompiledAgent;
import io.github.loopforge4j.core.runtime.sandbox.CommandSpec;
import io.github.loopforge4j.core.runtime.sandbox.ExecResult;
import io.github.loopforge4j.core.runtime.sandbox.LineListener;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes one node attempt and commits its outcome (port of upstream {@code executeNode}, {@code
 * nodeFailedAt}, {@code executeSOPNode}). Every commit is fenced: it only lands while the node is
 * still running in the same scheduler generation and attempt, so late results of a reset or
 * superseded execution are dropped.
 */
final class NodeRunner {

    private static final Logger log = LoggerFactory.getLogger(NodeRunner.class);
    private static final int MAX_NODE_OUTPUT = 64 * 1024;
    private static final int MAX_DETAIL = 2000;

    private final RunEngine engine;
    private final AtomicInteger invocationSequence = new AtomicInteger();

    NodeRunner(RunEngine engine) {
        this.engine = engine;
    }

    void execute(String runId, int generation, WorkflowNode node, Cancellation cancellation) {
        int attempt = begin(runId, generation, node);
        if (attempt == 0) {
            return;
        }
        try {
            switch (node.type()) {
                case APPROVAL -> waitForApproval(runId, generation, attempt, node);
                case SCRIPT -> runScript(runId, generation, attempt, node, cancellation);
                case AGENT -> runAgent(runId, generation, attempt, node, cancellation);
                case SOP -> runChild(runId, generation, attempt, node, cancellation);
            }
        } catch (CancelledException e) {
            // whoever cancelled the execution (pause, cancel, rework, shutdown) settles the node
        } catch (NodeFailure failure) {
            if (!cancellation.cancelled()) {
                fail(runId, generation, attempt, node, failure, allowReject(node, failure));
            }
        } catch (IOException | RuntimeException e) {
            if (!cancellation.cancelled()) {
                log.warn("node {} of run {} failed unexpectedly", node.id(), runId, e);
                fail(runId, generation, attempt, node, new NodeFailure(FailureKind.EXECUTOR, describe(e)), false);
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    /** Marks the node running; returns the attempt number, or 0 when it is not startable. */
    private int begin(String runId, int generation, WorkflowNode node) {
        int[] attempt = {0};
        engine.mutateQuietly(runId, c -> {
            attempt[0] = 0;
            Run r = c.run();
            if (r.generation() != generation
                    || r.status() != RunStatus.RUNNING
                    || r.pauseRequested()
                    || r.cancelRequested()) {
                return false;
            }
            NodeRun nr = r.requireNode(node.id());
            if (nr.status() != NodeStatus.PENDING) {
                return false;
            }
            if (!nr.interrupted() || nr.attempt() == 0) {
                nr.attempt(nr.attempt() + 1);
            }
            nr.interrupted(false);
            nr.status(NodeStatus.RUNNING);
            nr.startedAt(c.now());
            nr.gateResult(null);
            nr.error(null);
            nr.activity(null);
            c.event(node.id(), "node.started", node.name() + " 开始执行");
            attempt[0] = nr.attempt();
            return true;
        });
        return attempt[0];
    }

    /** Write fence (upstream {@code shouldCommitNodeOutcome}). */
    static boolean owns(Run r, NodeRun nr, int generation, int attempt, boolean success) {
        if (r.generation() != generation || nr == null || nr.status() != NodeStatus.RUNNING) {
            return false;
        }
        return success || nr.attempt() == attempt;
    }

    private void waitForApproval(String runId, int generation, int attempt, WorkflowNode node) {
        String detail = node.taskPrompt() == null || node.taskPrompt().isBlank()
                ? ""
                : "：" + node.taskPrompt().strip();
        commitWaiting(runId, generation, attempt, node, "等待人工批准", null, "gate.waiting", "等待人工批准" + detail);
    }

    private void commitSuccess(
            String runId,
            int generation,
            int attempt,
            WorkflowNode node,
            String output,
            List<Artifact> artifacts,
            String manualGate) {
        engine.mutateQuietly(runId, c -> {
            Run r = c.run();
            NodeRun nr = r.requireNode(node.id());
            if (!owns(r, nr, generation, attempt, true)) {
                return false;
            }
            artifacts.forEach(c::artifact);
            nr.output(clip(output, MAX_NODE_OUTPUT));
            nr.error(null);
            if (manualGate != null) {
                nr.finish(NodeStatus.WAITING_APPROVAL, c.now());
                nr.activity("等待人工验证：" + manualGate);
                c.event(node.id(), "gate.waiting", "等待人工验证：" + manualGate);
            } else {
                nr.finish(NodeStatus.SUCCEEDED, c.now());
                c.event(node.id(), "node.succeeded", node.name() + " 已完成");
            }
            return true;
        });
    }

    private void commitWaiting(
            String runId,
            int generation,
            int attempt,
            WorkflowNode node,
            String activity,
            String error,
            String eventType,
            String message) {
        engine.mutateQuietly(runId, c -> {
            Run r = c.run();
            NodeRun nr = r.requireNode(node.id());
            if (!owns(r, nr, generation, attempt, false)) {
                return false;
            }
            nr.finish(NodeStatus.WAITING_APPROVAL, c.now());
            nr.activity(activity);
            nr.error(error);
            c.event(node.id(), eventType, message);
            return true;
        });
    }

    /**
     * Routes a failed attempt: retry within the attempt budget, park on a human gate when a rework
     * verdict has no budget left, or fail and optionally send the work back upstream.
     */
    private void fail(
            String runId, int generation, int attempt, WorkflowNode node, NodeFailure failure, boolean allowReject) {
        boolean[] reject = {false};
        String message = failure.getMessage();
        engine.mutateQuietly(runId, c -> {
            reject[0] = false;
            Run r = c.run();
            NodeRun nr = r.requireNode(node.id());
            if (!owns(r, nr, generation, attempt, false)) {
                return false;
            }
            nr.error(message);
            NodeRun target =
                    node.rejectTo() == null ? null : r.node(node.rejectTo()).orElse(null);
            boolean budgetLeft = target != null && target.reworkCount() < node.reworkBudget();
            boolean gateRework =
                    allowReject && nr.gateResult() != null && nr.gateResult().decision() == GateDecision.REWORK;
            boolean configuration = failure.kind() == FailureKind.CONFIGURATION;
            if (shouldRetry(node, nr, failure) && !gateRework) {
                nr.feedback(message);
                nr.finish(NodeStatus.PENDING, c.now());
                nr.childRunId(null);
                c.event(node.id(), "node.retry", "执行失败，准备重试：" + message);
            } else if (!configuration && gateRework && target != null && !budgetLeft) {
                nr.finish(NodeStatus.WAITING_APPROVAL, c.now());
                nr.activity("返工预算耗尽，等待人工决策");
                nr.error("上游节点 " + node.rejectTo() + " 返工次数已用尽（maxReworks=" + node.reworkBudget()
                        + "），无法再次打回，等待人工决策（批准=接受当前结果继续下游）。Agent 理由：" + message);
                c.event(node.id(), "gate.waiting", "返工预算耗尽，等待人工决策：" + message);
            } else {
                nr.finish(NodeStatus.FAILED, c.now());
                c.event(node.id(), "node.failed", message);
            }
            reject[0] = !configuration && allowReject && nr.status() == NodeStatus.FAILED && budgetLeft;
            return true;
        });
        if (reject[0]) {
            try {
                engine.reject(runId, node.id(), node.rejectTo(), "节点失败自动打回：" + message, null, false);
            } catch (RunStateException e) {
                log.info("automatic rework of run {} node {} skipped: {}", runId, node.id(), e.getMessage());
            }
        }
    }

    static boolean shouldRetry(WorkflowNode node, NodeRun nr, NodeFailure failure) {
        switch (failure.kind()) {
            case CONFIGURATION, VERIFICATION, MAX_TURNS -> {
                return false;
            }
            default -> {
                int override = nr.maxAttemptsOverride() == null ? 0 : nr.maxAttemptsOverride();
                return nr.attempt() < Math.max(node.attemptBudget(), override);
            }
        }
    }

    /** Port of upstream {@code allowRejectOnFailure}. */
    static boolean allowReject(WorkflowNode node, NodeFailure failure) {
        if (failure.action() == FailurePolicy.REJECT) {
            return true;
        }
        if (failure.action() != null || !node.rejectsOnFailure()) {
            return false;
        }
        return node.scriptNode()
                || failure.kind() == FailureKind.VERIFICATION
                || failure.kind() == FailureKind.CHILD_RUN;
    }

    // ---------------------------------------------------------------- script

    private void runScript(String runId, int generation, int attempt, WorkflowNode node, Cancellation cancellation)
            throws NodeFailure, IOException, CancelledException {
        Run run = currentRun(runId);
        Sandbox sandbox = engine.sandbox(run);
        run = engine.prepareRepositories(runId, sandbox, cancellation);
        Map<String, String> runEnvironment = engine.processEnvironment(run, sandbox);
        ArtifactSupport.materializeInputs(
                sandbox.workspace(), run, node, engine.runs().artifacts(runId));
        ScriptLanguage language = node.effectiveScriptLanguage();
        String relative = LocalSandbox.CONTROL_DIR + "/scripts/" + node.id().replaceAll("[^A-Za-z0-9._-]", "_")
                + "-attempt-" + attempt + (language == ScriptLanguage.PYTHON ? ".py" : ".sh");
        Path file = sandbox.workspace().resolve(relative);
        new io.github.loopforge4j.core.runtime.ManagedFiles(sandbox.workspace()).write(file, node.script(), false);
        // absolute path: repository-scoped scripts run inside the checkout directory
        List<String> argv = language == ScriptLanguage.PYTHON
                ? List.of("python3", file.toString())
                : List.of("sh", "-l", file.toString());
        EventSink sink = engine.sink(runId, node.id());
        sink.emit(Telemetry.of("script", "script.started", "start", language.wire() + " 脚本开始执行"));
        List<ScopeTarget> targets = targets(run, node.effectiveScope());
        StringBuilder combined = new StringBuilder();
        long durationMillis = 0;
        for (ScopeTarget target : targets) {
            Map<String, String> env = new java.util.HashMap<>(runEnvironment);
            env.putAll(scriptEnvironment(runId, node, attempt));
            env.putAll(target.environment());
            CommandSpec spec = new CommandSpec(argv, target.directory(), env, null, timeout(node), "script");
            ExecResult result = sandbox.exec(spec, LineListener.NONE, cancellation);
            durationMillis += result.duration().toMillis();
            if (targets.size() > 1) {
                combined.append("## ").append(target.label()).append("\n\n");
            }
            combined.append(result.output().strip()).append(targets.size() > 1 ? "\n\n" : "");
            if (!result.succeeded()) {
                sink.emit(new Telemetry(
                        "script",
                        "script.completed",
                        "finish",
                        "error",
                        language.wire() + " 脚本执行失败 · " + target.label(),
                        null,
                        null,
                        null,
                        Map.of("exitCode", result.exitCode(), "durationMs", durationMillis)));
                String reason = result.timedOut()
                        ? "script timed out after " + timeout(node).toSeconds() + "s"
                        : "script exited with code " + result.exitCode();
                String where = targets.size() > 1 ? target.label() + ": " : "";
                throw new NodeFailure(FailureKind.SCRIPT, where + reason + ": " + tail(result.output()));
            }
        }
        sink.emit(new Telemetry(
                "script",
                "script.completed",
                "finish",
                "info",
                language.wire() + " 脚本执行成功 · " + durationMillis + " ms",
                null,
                null,
                null,
                Map.of(
                        "exitCode",
                        0,
                        "durationMs",
                        durationMillis,
                        "outputBytes",
                        combined.length(),
                        "targets",
                        targets.size())));
        String output = combined.toString().isBlank()
                ? "# 脚本执行结果\n\n- 语言：" + language.wire() + "\n- 状态：成功\n- 耗时：" + durationMillis + " ms\n- 标准输出：无\n"
                : combined.toString().strip() + "\n";
        List<Artifact> artifacts = ArtifactSupport.collect(
                sandbox.workspace(),
                currentRun(runId),
                node,
                attempt,
                node.artifactOutputs(),
                output,
                engine.clock().instant());
        commitSuccess(runId, generation, attempt, node, output, artifacts, null);
    }

    /** Where a command runs: the workspace root, or each repository checkout (upstream executionTargets). */
    private record ScopeTarget(String label, String directory, Map<String, String> environment) {}

    private static List<ScopeTarget> targets(Run run, ExecutionScope scope) throws NodeFailure {
        if (scope == ExecutionScope.RUN) {
            return List.of(new ScopeTarget("workspace", null, Map.of()));
        }
        if (run.repositories().isEmpty()) {
            throw new NodeFailure(
                    FailureKind.CONFIGURATION,
                    "executionScope=repositories requires the run to bind at least one repository");
        }
        return run.repositories().stream()
                .map(rc -> new ScopeTarget(rc.name(), rc.workspaceDir(), GitEnvironment.forRepository(rc)))
                .toList();
    }

    private static Map<String, String> scriptEnvironment(String runId, WorkflowNode node, int attempt) {
        return Map.of(
                "LOOPFORGE_RUN_ID",
                runId,
                "LOOPFORGE_NODE_ID",
                node.id(),
                "LOOPFORGE_ATTEMPT",
                Integer.toString(attempt));
    }

    // ----------------------------------------------------------------- agent

    private record GateRun(VerificationGate gate, FailurePolicy onFailure) {}

    private void runAgent(String runId, int generation, int attempt, WorkflowNode node, Cancellation cancellation)
            throws NodeFailure, IOException, CancelledException {
        AgentDefinition agent = engine.catalog()
                .agent(node.agentId())
                .orElseThrow(
                        () -> new NodeFailure(FailureKind.CONFIGURATION, "agent '" + node.agentId() + "' not found"));
        ExecutorDefinition executor = resolveExecutor(agent, node);
        AgentExecutor adapter = engine.executors()
                .adapter(executor.kind())
                .orElseThrow(() -> new NodeFailure(
                        FailureKind.CONFIGURATION, "executor kind '" + executor.kind() + "' is not supported"));
        List<ArtifactContract> contracts =
                withKnowledgeFile(agent, ArtifactSupport.merge(agent.artifactOutputs(), node.artifactOutputs()));
        FailurePolicy defaultFailure = node.rejectsOnFailure() ? FailurePolicy.REJECT : FailurePolicy.FAIL;
        List<GateRun> gates = agent.verification().stream()
                .filter(g -> g.mode() == GateMode.MR
                        || g.mode() == GateMode.COMMAND && !g.script().isBlank())
                .map(g -> new GateRun(g, g.onFailure() == null ? defaultFailure : g.onFailure()))
                .toList();
        String manualGate = manualGateName(agent);
        int maxIterations = agent.loop().effectiveIterations();
        AgentAssets assets;
        try {
            assets = AgentAssets.resolve(agent, engine.catalog());
        } catch (IllegalArgumentException e) {
            throw new NodeFailure(FailureKind.CONFIGURATION, "agent '" + agent.id() + "': " + e.getMessage());
        }
        List<String> inlineRules = AgentAssets.inlineRules(agent, engine.catalog());

        Run run = currentRun(runId);
        Sandbox sandbox = engine.sandbox(run);
        run = engine.prepareRepositories(runId, sandbox, cancellation);
        Map<String, String> runEnvironment = engine.processEnvironment(run, sandbox);
        String manifest = ArtifactSupport.materializeInputs(
                sandbox.workspace(), run, node, engine.runs().artifacts(runId));
        String gatePath = GateResults.path(node.id(), attempt);
        EventSink sink = sessionRecordingSink(engine.sink(runId, node.id()), runId, generation, attempt, node.id());
        if (agent.platformMcp() || agent.knowledgeBase()) {
            assets = bindPlatformMcp(run, node, agent, assets, sink);
        }
        AgentCall call = new AgentCall(
                runId,
                generation,
                attempt,
                node,
                agent,
                executor,
                adapter,
                sandbox,
                sink,
                cancellation,
                contracts,
                gatePath,
                runEnvironment,
                assets,
                inlineRules);

        NodeFailure failure = null;
        AgentResult last = null;
        String loopFeedback = null;
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            failure = null;
            GateResults.clear(sandbox.workspace(), gatePath);
            Run current = currentRun(runId);
            NodeRun nr = current.requireNode(node.id());
            String resume = iteration == 1
                            && nr.resumeSessionId() != null
                            && !nr.resumeSessionId().isBlank()
                    ? nr.resumeSessionId()
                    : null;
            String prompt = resume != null
                    ? PromptCompiler.resume(nr.feedback(), gatePath, node.rejectTo())
                    : freshPrompt(current, call, manifest);
            if (loopFeedback != null) {
                prompt = PromptCompiler.withLoopFeedback(prompt, agent, loopFeedback);
            }
            try {
                last = invokeWithSessionFallback(call, prompt, resume, manifest, loopFeedback);
            } catch (NodeFailure f) {
                failure = f;
                break;
            }
            Optional<GateResult> submitted = readGateResult(call);
            if (submitted.isEmpty()
                    && last.sessionId() != null
                    && !last.sessionId().isBlank()) {
                sink.emit(Telemetry.of("verification", "gate.submit.missing", "progress", "未收到节点终态，恢复同一 CLI 会话要求重新提交")
                        .withLevel("warn"));
                try {
                    last = invoke(call, PromptCompiler.missingGateResult(gatePath, node.rejectTo()), last.sessionId());
                } catch (NodeFailure f) {
                    failure = f;
                    break;
                }
                submitted = readGateResult(call);
            }
            if (submitted.isEmpty()) {
                failure =
                        new NodeFailure(FailureKind.GATE_DECISION, "未收到节点终态：执行器没有写入 " + gatePath, FailurePolicy.HUMAN);
                break;
            }
            GateResult result = submitted.get();
            recordGateResult(runId, generation, node.id(), result);
            sink.emit(new Telemetry(
                    "verification",
                    "gate.submit.received",
                    "finish",
                    "info",
                    "收到 Agent 节点终态：" + result.decision().wire(),
                    null,
                    null,
                    null,
                    Map.of("decision", result.decision().wire(), "reason", result.reason())));
            Optional<NodeFailure> decision = GateResults.toFailure(result, node.rejectTo());
            if (decision.isPresent()) {
                failure = decision.get();
                if (!gates.isEmpty() || !contracts.isEmpty()) {
                    sink.emit(Telemetry.of(
                                    "verification",
                                    "gate.terminal",
                                    "finish",
                                    "decision="
                                            + result.decision().wire()
                                            + "; skipped by terminal decision (command gates " + gates.size()
                                            + ", artifact contracts " + contracts.size() + ")")
                            .withLevel("warn"));
                }
                break;
            }
            try {
                ArtifactSupport.validate(sandbox.workspace(), contracts);
                if (!contracts.isEmpty()) {
                    sink.emit(Telemetry.of("artifact", "artifact.contract.validated", "finish", "产物契约校验通过"));
                }
                for (GateRun gate : gates) {
                    runGate(call, gate, iteration);
                }
            } catch (NodeFailure f) {
                failure = f;
            }
            if (failure == null || iteration == maxIterations) {
                break;
            }
            if (failure.action() != FailurePolicy.LOOP) {
                sink.emit(Telemetry.of(
                        "orchestration",
                        "agent.loop",
                        "finish",
                        "门禁失败策略为 "
                                + (failure.action() == null
                                        ? "fail"
                                        : failure.action().wire()) + "，跳过自修正"));
                break;
            }
            loopFeedback = failure.getMessage();
            sink.emit(new Telemetry(
                    "orchestration",
                    "agent.loop",
                    "progress",
                    "info",
                    "第 " + iteration + " 轮未通过，进入自修正",
                    null,
                    null,
                    null,
                    Map.of("iteration", iteration, "maxIterations", maxIterations)));
        }

        if (failure != null) {
            if (failure.kind() == FailureKind.MAX_TURNS) {
                commitWaiting(
                        runId,
                        generation,
                        attempt,
                        node,
                        "对话轮次用尽，等待确认是否重试",
                        failure.getMessage(),
                        "agent.turns.exhausted",
                        "对话轮次已用尽，请确认是否重试本节点");
                return;
            }
            if (failure.action() == FailurePolicy.HUMAN) {
                String action = currentRun(runId)
                        .node(node.id())
                        .map(NodeRun::gateResult)
                        .map(GateResult::actionRequired)
                        .orElse(null);
                commitWaiting(
                        runId,
                        generation,
                        attempt,
                        node,
                        "等待人工确认",
                        failure.getMessage(),
                        "gate.waiting",
                        "Agent 请求人工确认：" + (action == null ? clip(failure.getMessage(), 600) : action));
                return;
            }
            if (maxIterations > 1 && agent.loop().onExhausted() == LoopExhaustedPolicy.HUMAN) {
                commitWaiting(
                        runId,
                        generation,
                        attempt,
                        node,
                        "自修正次数已耗尽，等待人工判断",
                        failure.getMessage(),
                        "agent.loop.exhausted",
                        "Agent 自修正次数已耗尽，转人工判断：" + failure.getMessage());
                return;
            }
            throw failure;
        }
        String output = last == null ? "" : last.output();
        List<Artifact> artifacts = ArtifactSupport.collect(
                sandbox.workspace(),
                currentRun(runId),
                node,
                attempt,
                contracts,
                output,
                engine.clock().instant());
        commitSuccess(runId, generation, attempt, node, output, artifacts, manualGate);
    }

    /** Workspace-root file a knowledge-enabled role leaves distilled knowledge in (upstream). */
    static final String KNOWLEDGE_FILE = "knowledge.md";

    /** Distilled knowledge the usage rule asks for is kept for review when the role leaves it. */
    static List<ArtifactContract> withKnowledgeFile(AgentDefinition agent, List<ArtifactContract> contracts) {
        if (!agent.knowledgeBase()
                || contracts.stream()
                        .anyMatch(c -> KNOWLEDGE_FILE.equals(c.path().strip()))) {
            return contracts;
        }
        List<ArtifactContract> out = new ArrayList<>(contracts);
        out.add(new ArtifactContract("knowledge", KNOWLEDGE_FILE, ArtifactContract.DEFAULT_MEDIA_TYPE, false));
        return out;
    }

    private AgentAssets bindPlatformMcp(
            Run run, WorkflowNode node, AgentDefinition agent, AgentAssets assets, EventSink sink) {
        io.github.loopforge4j.core.mcp.PlatformMcp.Binding binding;
        try {
            binding = engine.platformMcp()
                    .bind(new io.github.loopforge4j.core.mcp.PlatformMcp.Invocation(
                            run.id(),
                            run.context().projectId(),
                            node.id(),
                            agent.id(),
                            run.context().triggeredBy(),
                            run.workflow().sandboxProfileId(),
                            agent.platformMcp(),
                            agent.knowledgeBase()));
        } catch (RuntimeException e) {
            log.warn("cannot bind platform MCP servers for run {}: {}", run.id(), e.toString());
            binding = new io.github.loopforge4j.core.mcp.PlatformMcp.Binding(
                    List.of(), List.of(), List.of("平台 MCP 注入失败：" + e.getClass().getSimpleName()));
        }
        for (String warning : binding.warnings()) {
            sink.emit(Telemetry.of("runtime", "mcp.platform.skipped", "progress", warning)
                    .withLevel("warn"));
        }
        return assets.with(binding.servers(), binding.rules());
    }

    private record AgentCall(
            String runId,
            int generation,
            int attempt,
            WorkflowNode node,
            AgentDefinition agent,
            ExecutorDefinition executor,
            AgentExecutor adapter,
            Sandbox sandbox,
            EventSink sink,
            Cancellation cancellation,
            List<ArtifactContract> contracts,
            String gatePath,
            Map<String, String> environment,
            AgentAssets assets,
            List<String> inlineRules) {}

    private String freshPrompt(Run current, AgentCall call, String manifest) {
        return PromptCompiler.compile(
                current,
                call.node(),
                call.agent().withRules(call.inlineRules()),
                call.contracts(),
                engine.runs().artifacts(call.runId()),
                manifest,
                call.gatePath());
    }

    /** A lost native session (CLI state wiped) falls back to a fresh session once. */
    private AgentResult invokeWithSessionFallback(
            AgentCall call, String prompt, String resume, String manifest, String loopFeedback)
            throws NodeFailure, CancelledException, IOException {
        try {
            return invoke(call, prompt, resume);
        } catch (NodeFailure f) {
            if (resume == null
                    || f.kind() != FailureKind.EXECUTOR
                    || call.cancellation().cancelled()) {
                throw f;
            }
            call.sink()
                    .emit(Telemetry.of(
                                    "lifecycle",
                                    "agent.session.lost",
                                    "progress",
                                    "恢复 CLI 会话失败，改为全新会话：" + clip(f.getMessage(), 300))
                            .withLevel("warn"));
            String fresh = freshPrompt(currentRun(call.runId()), call, manifest);
            if (loopFeedback != null) {
                fresh = PromptCompiler.withLoopFeedback(fresh, call.agent(), loopFeedback);
            }
            return invoke(call, fresh, null);
        }
    }

    private AgentResult invoke(AgentCall call, String prompt, String resume)
            throws NodeFailure, CancelledException, IOException {
        String invocationId = call.runId() + "-" + call.node().id() + "-" + call.attempt() + "-"
                + invocationSequence.incrementAndGet();
        AgentInvocation invocation = new AgentInvocation(
                call.runId(),
                call.node().id(),
                call.attempt(),
                invocationId,
                call.executor(),
                call.agent(),
                prompt,
                resume,
                call.gatePath(),
                call.contracts(),
                timeout(call.node()),
                call.environment(),
                compile(call, invocationId));
        try {
            AgentResult result = call.adapter().execute(invocation, call.sandbox(), call.sink(), call.cancellation());
            recordInvocation(call, result);
            if (!result.terminal()) {
                call.sink()
                        .emit(Telemetry.of(
                                        "orchestration",
                                        "agent.no_terminal_result",
                                        "finish",
                                        "执行器未返回 result，按工作区产物继续门禁")
                                .withLevel("warn"));
            }
            return result;
        } catch (ExecutorException e) {
            if (e.partial() != null) {
                recordInvocation(call, e.partial());
            }
            throw new NodeFailure(e.maxTurns() ? FailureKind.MAX_TURNS : FailureKind.EXECUTOR, e.getMessage());
        } finally {
            if (invocation.compiled() != null) {
                try {
                    invocation.compiled().cleanup();
                } catch (IOException e) {
                    log.warn("cannot remove runtime files of {}: {}", invocationId, e.toString());
                }
            }
        }
    }

    /** Compiles the role's MCP servers, skills and hooks for one CLI invocation. */
    private CompiledAgent compile(AgentCall call, String invocationId) throws NodeFailure, IOException {
        if (!call.adapter().materializes()) {
            return null;
        }
        List<String> repositoryDirs = currentRun(call.runId()).repositories().stream()
                .map(io.github.loopforge4j.core.domain.RepositoryContext::workspaceDir)
                .toList();
        CompiledAgent compiled;
        try {
            compiled = AgentMaterializer.materialize(new AgentMaterializer.Request(
                    call.sandbox().workspace(),
                    invocationId,
                    call.executor().kind(),
                    call.agent(),
                    call.assets(),
                    call.gatePath(),
                    engine.gateMcpJava(),
                    engine.mcpSecrets(),
                    repositoryDirs));
        } catch (IllegalArgumentException e) {
            throw new NodeFailure(
                    FailureKind.CONFIGURATION, "agent '" + call.agent().id() + "': " + e.getMessage());
        }
        call.sink()
                .emit(new Telemetry(
                        "runtime",
                        "agent.materialized",
                        "finish",
                        "info",
                        "已编译角色配置：MCP " + compiled.mcpServers().size() + "，Skills "
                                + compiled.skills().size() + "，Hooks "
                                + compiled.hooks().size(),
                        null,
                        null,
                        null,
                        Map.of(
                                "mcpServers",
                                compiled.mcpServers().stream()
                                        .map(io.github.loopforge4j.core.domain.McpServerDefinition::id)
                                        .toList(),
                                "skills",
                                compiled.skills().size(),
                                "hooks",
                                compiled.hooks().size())));
        return compiled;
    }

    private void recordInvocation(AgentCall call, AgentResult result) {
        String model = result.model() != null ? result.model() : call.executor().model();
        // Codex counts cached tokens inside input tokens (OpenAI semantics)
        io.github.loopforge4j.core.domain.TokenUsage usage = engine.pricing()
                .price(model, result.usage(), "codex".equals(call.executor().kind()));
        engine.mutateQuietly(call.runId(), c -> {
            Run r = c.run();
            NodeRun nr = r.requireNode(call.node().id());
            if (!owns(r, nr, call.generation(), call.attempt(), false)) {
                return false;
            }
            if (result.sessionId() != null && !result.sessionId().isBlank()) {
                nr.sessionId(result.sessionId());
            }
            nr.provider(call.executor().kind());
            nr.model(model);
            nr.resumeSessionId(null);
            nr.addUsage(usage, result.toolCalls());
            r.addUsage(usage, result.toolCalls());
            return true;
        });
    }

    /**
     * Persists the native CLI session id as soon as the stream reports it (upstream records it from
     * telemetry too). An invocation interrupted by pause, shutdown or a crash can then be resumed
     * with {@code --resume} instead of starting over.
     */
    private EventSink sessionRecordingSink(
            EventSink delegate, String runId, int generation, int attempt, String nodeId) {
        java.util.concurrent.atomic.AtomicReference<String> recorded =
                new java.util.concurrent.atomic.AtomicReference<>();
        return telemetry -> {
            delegate.emit(telemetry);
            String session = telemetry.sessionId();
            if (session == null || session.isBlank() || session.equals(recorded.getAndSet(session))) {
                return;
            }
            engine.mutateQuietly(runId, c -> {
                Run r = c.run();
                NodeRun nr = r.requireNode(nodeId);
                if (!owns(r, nr, generation, attempt, false) || session.equals(nr.sessionId())) {
                    return false;
                }
                nr.sessionId(session);
                return true;
            });
        };
    }

    private void recordGateResult(String runId, int generation, String nodeId, GateResult result) {
        engine.mutateQuietly(runId, c -> {
            Run r = c.run();
            NodeRun nr = r.requireNode(nodeId);
            if (r.generation() != generation || nr.status() != NodeStatus.RUNNING) {
                return false;
            }
            nr.gateResult(result);
            return true;
        });
    }

    private Optional<GateResult> readGateResult(AgentCall call) throws IOException {
        try {
            return GateResults.read(call.sandbox().workspace(), call.gatePath());
        } catch (IllegalArgumentException e) {
            call.sink()
                    .emit(Telemetry.of("verification", "gate.submit.invalid", "finish", e.getMessage())
                            .withLevel("warn"));
            GateResults.clear(call.sandbox().workspace(), call.gatePath());
            return Optional.empty();
        }
    }

    private void runGate(AgentCall call, GateRun gate, int iteration)
            throws NodeFailure, IOException, CancelledException {
        String name = gate.gate().name();
        Map<String, Object> attributes =
                Map.of("gateId", String.valueOf(gate.gate().id()), "gateName", name, "iteration", iteration);
        call.sink()
                .emit(new Telemetry(
                        "verification", "gate.started", "start", "info", "门禁开始：" + name, null, null, null, attributes));
        if (gate.gate().mode() == GateMode.MR) {
            runPullRequestGate(call, gate, attributes);
            return;
        }
        Run run = currentRun(call.runId());
        for (ScopeTarget target : targets(run, gate.gate().effectiveScope())) {
            Map<String, String> env = new java.util.HashMap<>(call.environment());
            env.putAll(target.environment());
            CommandSpec spec = new CommandSpec(
                    List.of("sh", "-lc", gate.gate().script()),
                    target.directory(),
                    env,
                    null,
                    timeout(call.node()),
                    "verification");
            ExecResult result = call.sandbox().exec(spec, LineListener.NONE, call.cancellation());
            if (!result.succeeded()) {
                call.sink()
                        .emit(new Telemetry(
                                "verification",
                                "gate.failed",
                                "finish",
                                "error",
                                "门禁未通过：" + name,
                                null,
                                null,
                                null,
                                attributes));
                String detail = result.output().isBlank() ? "no command output" : tail(result.output());
                String where = target.directory() == null ? "" : " in " + target.label();
                throw new NodeFailure(
                        FailureKind.VERIFICATION,
                        "verification " + name + where + " command failed (exit " + result.exitCode() + "): " + detail,
                        gate.onFailure());
            }
        }
        call.sink()
                .emit(new Telemetry(
                        "verification", "gate.passed", "finish", "info", "门禁通过：" + name, null, null, null, attributes));
    }

    /**
     * mode=mr (upstream {@code verifyRunMRs}): every writable repository whose remote work branch
     * carries commits beyond the base branch needs a PR from the work branch into the base branch that
     * carries the pushed tip. When the hosting platform cannot be queried a human has to confirm.
     */
    private void runPullRequestGate(AgentCall call, GateRun gate, Map<String, Object> attributes)
            throws NodeFailure, CancelledException {
        Run run = currentRun(call.runId());
        GitWorkspace git = new GitWorkspace(call.sandbox(), call.environment(), call.cancellation());
        List<String> problems = new ArrayList<>();
        List<PullRequest> delivered = new ArrayList<>();
        boolean unverifiable = false;
        for (RepositoryContext rc : run.repositories()) {
            String work = rc.writableWorkBranch();
            if (work == null) {
                continue;
            }
            GitWorkspace.PushedWork pushed;
            try {
                pushed = git.pushedWork(rc);
            } catch (GitException e) {
                problems.add(rc.name() + "：" + e.getMessage());
                continue;
            }
            if (pushed.ahead() == 0) {
                continue;
            }
            if (!engine.delivery().supports(rc)) {
                problems.add(rc.name() + "：托管平台不支持自动核验 PR，请人工确认已创建 " + work + " → " + rc.baseBranch() + " 的 PR");
                unverifiable = true;
                continue;
            }
            try {
                List<PullRequest> pulls = engine.delivery().pullRequests(rc, work, rc.baseBranch());
                String problem = Deliveries.coverageProblem(pulls, pushed.tip());
                PullRequest representative = Deliveries.representative(pulls);
                if (representative != null) {
                    delivered.add(representative);
                }
                if (problem != null) {
                    problems.add(rc.name() + "：" + work + " → " + rc.baseBranch() + " " + problem);
                }
            } catch (DeliveryException e) {
                problems.add(rc.name() + "：查询 " + work + " → " + rc.baseBranch() + " 的 PR 失败：" + e.getMessage());
                unverifiable = true;
            }
        }
        String name = gate.gate().name();
        engine.recordDeliveries(call.runId(), delivered);
        if (problems.isEmpty()) {
            call.sink()
                    .emit(new Telemetry(
                            "verification",
                            "gate.mr.passed",
                            "finish",
                            "info",
                            "门禁通过：" + name,
                            null,
                            null,
                            null,
                            attributes));
            return;
        }
        call.sink()
                .emit(new Telemetry(
                        "verification",
                        "gate.mr.failed",
                        "finish",
                        "error",
                        "门禁未通过：" + name,
                        null,
                        null,
                        null,
                        attributes));
        throw new NodeFailure(
                FailureKind.VERIFICATION,
                "verification " + name + ": " + String.join("；", problems),
                unverifiable ? FailurePolicy.HUMAN : gate.onFailure());
    }

    private static String manualGateName(AgentDefinition agent) {
        for (VerificationGate gate : agent.verification()) {
            if (gate.mode() == GateMode.MANUAL && gate.required()) {
                return gate.name();
            }
        }
        for (HumanInterventionPoint point : agent.interventions()) {
            if (point.required()
                    && (point.stage() == InterventionStage.AFTER_EXECUTION
                            || point.stage() == InterventionStage.BEFORE_PUBLISH)) {
                return point.name();
            }
        }
        return null;
    }

    private ExecutorDefinition resolveExecutor(AgentDefinition agent, WorkflowNode node) throws NodeFailure {
        String id = firstNonBlank(
                agent.executorId(), node.executorId(), engine.settings().defaultExecutorId());
        ExecutorDefinition definition = engine.executors()
                .definition(id)
                .orElseThrow(() -> new NodeFailure(FailureKind.CONFIGURATION, "executor '" + id + "' not found"));
        if (agent.model() != null && !agent.model().isBlank()
                || agent.effort() != null && !agent.effort().isBlank()) {
            definition = definition.withModel(
                    firstNonBlank(agent.model(), definition.model(), null),
                    firstNonBlank(agent.effort(), definition.effort(), null));
        }
        return definition;
    }

    // --------------------------------------------------------------- sub-SOP

    private void runChild(String runId, int generation, int attempt, WorkflowNode node, Cancellation cancellation)
            throws NodeFailure, CancelledException {
        WorkflowDefinition childWorkflow = engine.catalog()
                .workflow(node.sopWorkflowId())
                .orElseThrow(
                        () -> new NodeFailure(FailureKind.CONFIGURATION, "SOP 流程 '" + node.sopWorkflowId() + "' 不存在"));
        if (childWorkflow.status() != io.github.loopforge4j.core.domain.WorkflowStatus.PUBLISHED) {
            throw new NodeFailure(
                    FailureKind.CONFIGURATION,
                    "SOP 流程 '" + childWorkflow.id() + "' 未发布（"
                            + childWorkflow.status().wire() + "）");
        }
        Run parent = currentRun(runId);
        guardCycle(parent, childWorkflow.id());
        String childId = parent.requireNode(node.id()).childRunId();
        Run child = childId == null
                ? null
                : engine.runs()
                        .find(childId)
                        .filter(c -> c.workflowId().equals(childWorkflow.id()))
                        .orElse(null);
        if (child == null) {
            // the child works in the parent's checkout: same repositories, already prepared
            child = engine.startChild(
                    childWorkflow,
                    childInput(parent, node),
                    ExecutionContext.childOf(parent, node.id()),
                    parent.repositories(),
                    parent.repositoriesPrepared());
            String startedId = child.id();
            engine.mutateQuietly(runId, c -> {
                Run r = c.run();
                NodeRun nr = r.requireNode(node.id());
                if (!owns(r, nr, generation, attempt, false)) {
                    return false;
                }
                nr.childRunId(startedId);
                nr.activity("等待子 SOP 完成");
                c.event(node.id(), "sop.child_started", "子 SOP「" + childWorkflow.name() + "」已启动：" + startedId);
                return true;
            });
        }
        String followed = child.id();
        boolean waitingNoted = false;
        while (true) {
            cancellation.check();
            Run current = engine.runs()
                    .find(followed)
                    .orElseThrow(() -> new NodeFailure(FailureKind.CHILD_RUN, "子 SOP 运行 " + followed + " 已不存在"));
            if (current.status() == RunStatus.SUCCEEDED) {
                break;
            }
            if (current.status() == RunStatus.FAILED || current.status() == RunStatus.CANCELLED) {
                throw new NodeFailure(
                        FailureKind.CHILD_RUN,
                        "子 SOP「" + childWorkflow.name() + "」运行" + (current.status() == RunStatus.FAILED ? "失败" : "已取消")
                                + "（" + followed + "）");
            }
            if (current.status() == RunStatus.WAITING && !waitingNoted) {
                waitingNoted = true;
                engine.emit(
                        runId,
                        node.id(),
                        Telemetry.of("orchestration", "sop.child_waiting", "progress", "子 SOP 等待人工处理：" + followed));
            }
            cancellation.sleep(engine.tick());
        }
        Map<String, Artifact> latest = new LinkedHashMap<>();
        for (Artifact artifact : engine.runs().artifacts(followed)) {
            latest.put(artifact.nodeId() + "." + artifact.name(), artifact);
        }
        List<Artifact> copies = new ArrayList<>();
        for (Map.Entry<String, Artifact> entry : latest.entrySet()) {
            Artifact a = entry.getValue();
            copies.add(ArtifactSupport.create(
                    runId,
                    node.id(),
                    attempt,
                    entry.getKey(),
                    a.path(),
                    a.mediaType(),
                    a.content() == null ? "" : a.content(),
                    engine.clock().instant()));
        }
        commitSuccess(
                runId,
                generation,
                attempt,
                node,
                "子 SOP「" + childWorkflow.name() + "」已完成（" + followed + "），产出 " + copies.size() + " 个产物。",
                copies,
                null);
    }

    private void guardCycle(Run parent, String childWorkflowId) throws NodeFailure {
        Set<String> chain = new HashSet<>();
        Run cursor = parent;
        while (cursor != null) {
            chain.add(cursor.workflowId());
            cursor = cursor.context().childRun()
                    ? engine.runs().find(cursor.context().parentRunId()).orElse(null)
                    : null;
        }
        if (chain.contains(childWorkflowId)) {
            throw new NodeFailure(FailureKind.CONFIGURATION, "SOP 嵌套形成循环：" + childWorkflowId);
        }
    }

    private static String childInput(Run parent, WorkflowNode node) {
        StringBuilder input = new StringBuilder(parent.input());
        List<String> upstream = PromptCompiler.upstreamOutputs(parent.workflow(), node, parent);
        if (!upstream.isEmpty()) {
            input.append("\n\n## 上游节点输出\n\n").append(String.join("\n\n", upstream));
        }
        if (node.taskPrompt() != null && !node.taskPrompt().isBlank()) {
            input.append("\n\n## 子 SOP 任务说明\n\n").append(node.taskPrompt().strip());
        }
        return input.toString();
    }

    // --------------------------------------------------------------- helpers

    private Run currentRun(String runId) throws NodeFailure {
        return engine.runs()
                .find(runId)
                .orElseThrow(() -> new NodeFailure(FailureKind.CONFIGURATION, "run " + runId + " disappeared"));
    }

    private Duration timeout(WorkflowNode node) {
        return node.timeoutSeconds() == null
                ? engine.settings().nodeTimeout()
                : Duration.ofSeconds(node.timeoutSeconds());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String describe(Exception e) {
        return e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= MAX_DETAIL ? trimmed : "…" + trimmed.substring(trimmed.length() - MAX_DETAIL);
    }
}
