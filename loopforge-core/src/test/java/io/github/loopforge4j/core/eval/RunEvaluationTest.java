package io.github.loopforge4j.core.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RunEvaluationTest {

    static WorkflowDefinition workflow() {
        return WorkflowDefinition.builder("wf")
                .name("评审流")
                .node(WorkflowNode.builder("impl", NodeType.AGENT)
                        .agent("dev")
                        .maxAttempts(2)
                        .build())
                .node(WorkflowNode.builder("review", NodeType.AGENT)
                        .agent("rev")
                        .rejectTo("impl")
                        .build())
                .edge("impl", "review")
                .build();
    }

    static RunEvent event(
            long seq,
            String node,
            String category,
            String type,
            String level,
            String message,
            Map<String, Object> attributes) {
        return new RunEvent(seq, "r", node, category, type, level, message, attributes, Instant.EPOCH);
    }

    @Test
    void factsSummarizeLoopsToolsAndErrors() {
        Run run = Run.create("r", workflow(), "做登录页", ExecutionContext.EMPTY, Instant.EPOCH);
        List<RunEvent> events = List.of(
                event(1, "impl", "lifecycle", "node.started", "info", "impl 开始", Map.of()),
                event(2, "impl", "tool", "tool.call", "info", "Read", Map.of("tool", "Read", "phase", "start")),
                event(3, "impl", "tool", "tool.call", "error", "Bash failed", Map.of("tool", "Bash", "success", false)),
                event(4, "impl", "tool", "tool.call", "error", "Bash failed", Map.of("tool", "Bash", "success", false)),
                event(5, "review", "lifecycle", "node.started", "info", "review 开始", Map.of()),
                event(6, "review", "lifecycle", "gate.rejected", "warn", "打回 impl", Map.of()),
                event(7, "impl", "lifecycle", "node.started", "info", "impl 开始", Map.of()),
                event(8, "impl", "output", "script.output", "info", "noise", Map.of()));
        RunEvaluation.Facts facts = RunEvalFacts.build(run, events, List.of());
        assertThat(facts.pingPong()).containsExactly("impl→review→impl");
        assertThat(facts.toolRepeats()).singleElement().satisfies(t -> {
            assertThat(t.tool()).isEqualTo("Bash");
            assertThat(t.count()).isEqualTo(2);
            assertThat(t.failed()).isEqualTo(2);
        });
        assertThat(facts.errors()).singleElement().satisfies(e -> {
            assertThat(e.text()).isEqualTo("Bash failed");
            assertThat(e.count()).isEqualTo(2);
        });
        assertThat(facts.nodes()).extracting(RunEvaluation.NodeFact::starts).containsExactly(2, 1);
        assertThat(facts.nodes()).extracting(RunEvaluation.NodeFact::gateEvents).containsExactly(0, 1);
        assertThat(facts.digest()).extracting(RunEvaluation.DigestEvent::seq).containsExactly(1L, 3L, 4L, 5L, 6L, 7L);
        assertThat(facts.eventTypes()).containsEntry("tool/tool.call", 3);
    }

    @Test
    void theScoreIsTheWeightedAverageAndTheContractIsChecked() throws Exception {
        RunEvaluation.Policy policy = RunEvaluation.Policy.DEFAULT;
        String json = """
                结论如下
                ```json
                {"dimensions": {"control": 40, "latency": 80, "tools": 60, "tokens": 90, "recovery": 50, "delivery": 100},
                 "findings": [{"severity": "fatal", "dimension": "control", "nodeId": "impl", "message": "反复打回"}, {"message": ""}],
                 "summary": "评审反复打回实现",
                 "recommendations": [
                   {"id": "a", "title": "限制返工", "action": "update_node", "nodeId": "impl", "set": {"maxReworks": 2}},
                   {"id": "a", "title": "加门禁", "action": "upsert_gate", "targetId": "dev",
                    "gate": {"id": "unit", "commands": ["mvn -q test"], "required": true}},
                   {"title": "重写", "action": "rewrite_everything"}]}
                ```""";
        RunEvaluation.Result r = RunEvalPrompt.parse(json, policy);
        // (40*25 + 80*20 + 60*20 + 90*15 + 50*10 + 100*10) / 100
        assertThat(r.score()).isEqualTo(67);
        assertThat(r.passed()).isFalse();
        assertThat(r.findings())
                .singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo("info"));
        assertThat(r.recommendations())
                .extracting(RunEvaluation.Recommendation::id)
                .containsExactly("a", "rec-2");
        assertThat(r.recommendations().get(1).target()).isEqualTo("agent");
        assertThatThrownBy(() -> RunEvalPrompt.parse("{\"summary\": \"x\", \"dimensions\": {\"control\": 1}}", policy))
                .hasMessageContaining("latency");
        assertThatThrownBy(() -> RunEvalPrompt.parse("no json", policy)).hasMessageContaining("JSON");
        assertThat(RunEvalPrompt.build(
                        policy,
                        RunEvalFacts.build(
                                Run.create("r", workflow(), "", ExecutionContext.EMPTY, Instant.EPOCH),
                                List.of(),
                                List.of()),
                        RunEvalFacts.catalog(workflow(), id -> Optional.empty())))
                .contains("准入线 70 分", "control（控制流健康，权重 25）", "\"runId\":\"r\"");
    }

    @Test
    void recommendationsChangeOnlyWhatTheContractAllows() {
        WorkflowDefinition wf = workflow();
        var rec = new RunEvaluation.Recommendation(
                "r1",
                "t",
                null,
                "workflow",
                null,
                "update_node",
                "impl",
                Map.of("maxReworks", 2, "onFailure", "human"),
                null,
                null,
                null,
                null,
                null);
        WorkflowDefinition changed = RunEvalApply.apply(rec, wf);
        assertThat(changed.requireNode("impl").maxReworks()).isEqualTo(2);
        assertThat(changed.requireNode("impl").onFailure()).isEqualTo(FailurePolicy.HUMAN);
        assertThat(changed.requireNode("impl").agentId()).isEqualTo("dev");
        assertThat(changed.edges()).isEqualTo(wf.edges());
        assertThatThrownBy(() -> RunEvalApply.apply(
                        new RunEvaluation.Recommendation(
                                "r",
                                "t",
                                null,
                                "workflow",
                                null,
                                "update_node",
                                "impl",
                                Map.of("script", "rm -rf /"),
                                null,
                                null,
                                null,
                                null,
                                null),
                        wf))
                .hasMessageContaining("may only set");
        assertThatThrownBy(() -> RunEvalApply.apply(
                        new RunEvaluation.Recommendation(
                                "r",
                                "t",
                                null,
                                "workflow",
                                null,
                                "update_node",
                                "impl",
                                Map.of("maxAttempts", 999),
                                null,
                                null,
                                null,
                                null,
                                null),
                        wf))
                .hasMessageContaining("between");
        assertThatThrownBy(() -> RunEvalApply.apply(
                        new RunEvaluation.Recommendation(
                                "r",
                                "t",
                                null,
                                "workflow",
                                null,
                                "update_node",
                                "ghost",
                                Map.of("maxAttempts", 1),
                                null,
                                null,
                                null,
                                null,
                                null),
                        wf))
                .hasMessageContaining("ghost");

        AgentDefinition dev =
                AgentDefinition.builder("dev").name("开发").prompt("写代码").build();
        AgentDefinition withGate = RunEvalApply.apply(
                new RunEvaluation.Recommendation(
                        "g",
                        "t",
                        null,
                        "agent",
                        "dev",
                        "upsert_gate",
                        null,
                        null,
                        VerificationGate.command("unit", "单测", FailurePolicy.LOOP, "mvn -q test"),
                        null,
                        null,
                        null,
                        null),
                dev);
        assertThat(withGate.verification()).extracting(VerificationGate::id).containsExactly("unit");
        AgentDefinition appended = RunEvalApply.apply(
                new RunEvaluation.Recommendation(
                        "p",
                        "t",
                        null,
                        "agent",
                        "dev",
                        "append_task_prompt",
                        null,
                        null,
                        null,
                        "提交前先跑单测。",
                        null,
                        null,
                        null),
                withGate);
        assertThat(appended.prompt()).isEqualTo("写代码\n\n提交前先跑单测。");
        assertThatThrownBy(() -> RunEvalApply.apply(
                        new RunEvaluation.Recommendation(
                                "p",
                                "t",
                                null,
                                "agent",
                                "dev",
                                "append_task_prompt",
                                null,
                                null,
                                null,
                                "提交前先跑单测。",
                                null,
                                null,
                                null),
                        appended))
                .hasMessageContaining("already");
        AgentDefinition removed = RunEvalApply.apply(
                new RunEvaluation.Recommendation(
                        "x", "t", null, "agent", "dev", "remove_gate", null, null, null, "unit", null, null, null),
                appended);
        assertThat(removed.verification()).isEmpty();
    }
}
