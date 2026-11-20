package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.GateDecision;
import io.github.loopforge4j.core.domain.GateFinding;
import io.github.loopforge4j.core.domain.GateResult;
import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic in-process executor for demos and tests; never calls a model. Behaviour is steered by
 * {@code [[mock:...]]} directives in the agent prompt (same convention as upstream):
 *
 * <ul>
 *   <li>{@code [[mock:gate=rework,pass]]} decision per node attempt (last value repeats);
 *       {@code none} skips the submission
 *   <li>{@code [[mock:reason=...]]}, {@code [[mock:action=...]]}, {@code [[mock:summary=...]]},
 *       {@code [[mock:finding=severity|title|evidence]]}
 *   <li>{@code [[mock:error=...]]} fail the invocation; {@code [[mock:turns=exhausted]]} simulate
 *       max turns
 *   <li>{@code [[mock:delay=ms]]} progress delay; {@code [[mock:artifacts=none]]} skip writing
 *       contracted files
 * </ul>
 */
public final class MockAgentExecutor implements AgentExecutor {

    public static final String KIND = "mock";

    private final Duration defaultDelay;

    public MockAgentExecutor() {
        this(Duration.ofMillis(30));
    }

    public MockAgentExecutor(Duration defaultDelay) {
        this.defaultDelay = defaultDelay;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public AgentResult execute(AgentInvocation invocation, Sandbox sandbox, EventSink sink, Cancellation cancellation)
            throws ExecutorException, IOException, CancelledException {
        String instructions =
                invocation.agent().prompt() == null ? "" : invocation.agent().prompt();
        String session = "mock-" + invocation.agent().id() + "-" + invocation.runId();
        long delay = parseLong(directive(instructions, "delay"), defaultDelay.toMillis());
        for (String step : List.of("读取 Agent 定义与任务书", "分析任务和约束", "生成结果并提交门禁")) {
            cancellation.sleep(Duration.ofMillis(delay));
            sink.emit(new Telemetry(
                    "lifecycle",
                    "agent.progress",
                    "progress",
                    "info",
                    step,
                    session,
                    null,
                    null,
                    java.util.Map.of("provider", KIND)));
        }
        TokenUsage usage = new TokenUsage(120, 80, 0, 0, 0, 0, 0, "estimated");
        String error = directive(instructions, "error");
        if (!error.isEmpty()) {
            throw new ExecutorException(error, new AgentResult("", session, KIND, usage, 0, false, true, false, 1));
        }
        if ("exhausted".equals(directive(instructions, "turns"))) {
            throw new ExecutorException(
                    "executor reached max turns", new AgentResult("", session, KIND, usage, 0, false, true, true, 1));
        }
        Path workspace = sandbox.workspace();
        if (!"none".equals(directive(instructions, "artifacts"))) {
            for (ArtifactContract contract : invocation.artifacts()) {
                Path target = safeResolve(workspace, contract.path());
                Files.createDirectories(target.getParent());
                Files.writeString(
                        target,
                        "# " + contract.name() + "\n\n" + invocation.agent().name() + " 的模拟产出（attempt "
                                + invocation.attempt() + "）。\n",
                        StandardCharsets.UTF_8);
            }
        }
        String decision = decision(instructions, invocation.attempt());
        if (invocation.gateResultPath() != null && !"none".equals(decision)) {
            GateResult result = gateResult(instructions, GateDecision.fromWire(decision));
            Path target = safeResolve(workspace, invocation.gateResultPath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, Json.write(result), StandardCharsets.UTF_8);
            sink.emit(new Telemetry(
                    "tool",
                    "tool.call",
                    "finish",
                    "info",
                    "submit_gate_result " + decision,
                    session,
                    null,
                    null,
                    java.util.Map.of("tool", "submit_gate_result")));
        }
        String output = invocation.agent().name() + " 已完成模拟执行（attempt " + invocation.attempt() + "）。";
        sink.emit(new Telemetry(
                "usage",
                "usage.final",
                "finish",
                "info",
                "final model usage",
                session,
                null,
                usage,
                java.util.Map.of()));
        return new AgentResult(output, session, KIND, usage, 1, true, false, false, 0);
    }

    private static GateResult gateResult(String instructions, GateDecision decision) {
        String reason = directive(instructions, "reason");
        if (reason.isEmpty()) {
            reason = "mock executor submitted " + decision.wire();
        }
        String action = directive(instructions, "action");
        if (action.isEmpty() && decision == GateDecision.HUMAN) {
            action = "请确认当前证据是否足以允许流程继续";
        }
        if (action.isEmpty() && decision == GateDecision.REWORK) {
            action = "请上游节点根据评审发现完成修复后重新提交";
        }
        List<GateFinding> findings = new ArrayList<>();
        for (String value : directives(instructions, "finding")) {
            String[] parts = value.split("\\|", 3);
            if (parts.length == 1) {
                findings.add(new GateFinding(null, parts[0].trim(), null));
            } else {
                findings.add(
                        new GateFinding(parts[0].trim(), parts[1].trim(), parts.length > 2 ? parts[2].trim() : null));
            }
        }
        String summary = directive(instructions, "summary");
        return new GateResult(
                decision, reason, action.isEmpty() ? null : action, summary.isEmpty() ? null : summary, findings);
    }

    static String decision(String instructions, int attempt) {
        String sequence = directive(instructions, "gate");
        if (sequence.isEmpty()) {
            return "pass";
        }
        String[] values = sequence.split(",");
        String value =
                values[Math.max(0, Math.min(attempt, values.length) - 1)].trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "pass", "rework", "fail", "human", "none" -> value;
            default -> "pass";
        };
    }

    static String directive(String instructions, String name) {
        List<String> values = directives(instructions, name);
        return values.isEmpty() ? "" : values.getFirst();
    }

    static List<String> directives(String instructions, String name) {
        String prefix = "[[mock:" + name + "=";
        List<String> values = new ArrayList<>();
        int from = 0;
        while (true) {
            int start = instructions.indexOf(prefix, from);
            if (start < 0) {
                return values;
            }
            start += prefix.length();
            int end = instructions.indexOf("]]", start);
            if (end < 0) {
                return values;
            }
            values.add(instructions.substring(start, end).trim());
            from = end + 2;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value.isEmpty() ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Path safeResolve(Path workspace, String relative) throws IOException {
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) {
            throw new IOException("path escapes the workspace: " + relative);
        }
        return target;
    }
}
