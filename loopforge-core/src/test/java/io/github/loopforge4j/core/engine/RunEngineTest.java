package io.github.loopforge4j.core.engine;

import static io.github.loopforge4j.core.domain.NodeType.AGENT;
import static io.github.loopforge4j.core.domain.NodeType.APPROVAL;
import static io.github.loopforge4j.core.domain.NodeType.SCRIPT;
import static io.github.loopforge4j.core.domain.NodeType.SOP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AgentLoopPolicy;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.GateDecision;
import io.github.loopforge4j.core.domain.LoopExhaustedPolicy;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunEngineTest {

    @TempDir
    Path root;

    EngineFixture f;

    @BeforeEach
    void setUp() {
        f = new EngineFixture(root);
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    /** The upstream welcome SOP: real script, human approval, resumed script. */
    @Test
    void scriptApprovalScriptCompletesAfterApproval() {
        f.workflow(WorkflowDefinition.builder("welcome")
                .node(WorkflowNode.builder("prepare", SCRIPT)
                        .script("mkdir -p reports\nprintf '# 验收\\n\\n已验证\\n' > reports/welcome.md")
                        .artifacts(ArtifactContract.required("welcome", "reports/welcome.md"))
                        .build())
                .node(WorkflowNode.builder("approve", APPROVAL)
                        .rejectTo("prepare")
                        .taskPrompt("确认报告")
                        .build())
                .node(WorkflowNode.builder("finish", SCRIPT)
                        .script("printf '# 完成\\n' > completed.md")
                        .artifacts(ArtifactContract.required("completed", "completed.md"))
                        .build())
                .chain("prepare", "approve", "finish")
                .build());

        Run run = f.start("welcome");
        Run waiting = f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(waiting.requireNode("prepare").status()).isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(waiting.requireNode("approve").status()).isEqualTo(NodeStatus.WAITING_APPROVAL);
        assertThat(f.engine.artifacts(run.id())).extracting(Artifact::name).containsExactly("welcome");
        assertThat(f.engine.artifacts(run.id()).getFirst().content()).contains("已验证");

        f.engine.approve(run.id(), "approve", "看过了，没问题");
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);

        assertThat(done.nodeRuns()).allMatch(n -> n.status() == NodeStatus.SUCCEEDED);
        assertThat(done.humanContext()).contains("看过了");
        assertThat(f.engine.artifacts(run.id()))
                .extracting(Artifact::name)
                .containsExactly("welcome", "human-context", "decision", "completed");
        assertThat(done.workspaceReleased()).isTrue();
        assertThat(Files.exists(Path.of(done.workspaceRoot()))).isFalse();
        assertThat(f.eventTypes(run.id())).contains("run.started", "gate.waiting", "gate.approved", "run.succeeded");
    }

    @Test
    void rejectFromApprovalReRunsUpstream() {
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("make", SCRIPT)
                        .script("echo built >> log.txt; cat log.txt")
                        .build())
                .node(WorkflowNode.builder("approve", APPROVAL)
                        .rejectTo("make")
                        .maxReworks(2)
                        .build())
                .chain("make", "approve")
                .build());
        Run run = f.start("wf");
        f.awaitStatus(run.id(), RunStatus.WAITING);

        f.engine.reject(run.id(), "approve", null, "报告不完整", "请补充日志");
        Run again = f.awaitStatus(run.id(), RunStatus.WAITING);

        assertThat(again.requireNode("make").reworkCount()).isEqualTo(1);
        assertThat(again.requireNode("make").attempt()).isEqualTo(2);
        assertThat(again.requireNode("make").output()).contains("built\nbuilt");
        f.engine.reject(run.id(), "approve", null, "再来", null);
        f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThatThrownBy(() -> f.engine.reject(run.id(), "approve", null, "第三次", null))
                .isInstanceOf(RunStateException.class)
                .hasMessageContaining("rework limit");
    }

    @Test
    void failingScriptRetriesWithinBudgetThenSucceeds() {
        f.workflow(WorkflowDefinition.builder("flaky")
                .node(WorkflowNode.builder("flaky", SCRIPT)
                        .script("if [ -f .marker ]; then echo ok; else touch .marker; echo boom >&2; exit 3; fi")
                        .maxAttempts(2)
                        .build())
                .build());
        Run run = f.start("flaky");
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("flaky").attempt()).isEqualTo(2);
        assertThat(f.eventTypes(run.id())).contains("node.retry");
    }

    @Test
    void failedScriptCanBeRetriedOrPassedByOperator() {
        f.workflow(WorkflowDefinition.builder("broken")
                .node(WorkflowNode.builder("lint", SCRIPT)
                        .script("echo 'lint error' >&2; exit 1")
                        .build())
                .node(WorkflowNode.builder("after", SCRIPT)
                        .script("echo downstream")
                        .build())
                .chain("lint", "after")
                .build());
        Run run = f.start("broken");
        Run failed = f.awaitStatus(run.id(), RunStatus.FAILED);
        assertThat(failed.requireNode("lint").error())
                .contains("exited with code 1")
                .contains("lint error");
        assertThat(failed.requireNode("after").status()).isEqualTo(NodeStatus.PENDING);

        f.engine.retry(run.id(), null, null, null);
        Run failedAgain = f.awaitStatus(run.id(), RunStatus.FAILED);
        assertThat(failedAgain.requireNode("lint").attempt()).isEqualTo(2);

        f.engine.pass(run.id(), "lint", "环境问题，人工确认可以继续");
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("lint").activity()).contains("人工通过");
        assertThat(done.requireNode("after").output()).contains("downstream");
    }

    @Test
    void reviewerReworkVerdictSendsWorkBackUpstream() {
        f.agent(AgentDefinition.builder("dev")
                        .name("开发")
                        .executor("mock")
                        .prompt("实现需求")
                        .build())
                .agent(AgentDefinition.builder("reviewer")
                        .name("评审")
                        .executor("mock")
                        .prompt("独立评审 [[mock:gate=rework,pass]] [[mock:reason=缺少测试]]")
                        .build())
                .workflow(WorkflowDefinition.builder("delivery")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .artifacts(ArtifactContract.required("impl", "implementation.md"))
                                .build())
                        .node(WorkflowNode.builder("review", AGENT)
                                .agent("reviewer")
                                .rejectTo("implement")
                                .maxReworks(2)
                                .build())
                        .chain("implement", "review")
                        .build());

        Run run = f.start("delivery");
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);

        assertThat(done.requireNode("implement").reworkCount()).isEqualTo(1);
        assertThat(done.requireNode("implement").attempt()).isEqualTo(2);
        assertThat(done.requireNode("implement").feedback()).contains("缺少测试");
        assertThat(done.requireNode("review").gateResult().decision()).isEqualTo(GateDecision.PASS);
        assertThat(done.usage().totalTokens()).isPositive();
        assertThat(f.eventTypes(run.id())).contains("gate.rejected");
        assertThat(f.engine.artifacts(run.id()))
                .filteredOn(a -> a.name().equals("impl"))
                .hasSize(2);
    }

    @Test
    void reworkWithoutBudgetParksOnHumanGate() {
        f.agent(AgentDefinition.builder("dev").executor("mock").prompt("实现").build())
                .agent(AgentDefinition.builder("reviewer")
                        .executor("mock")
                        .prompt("[[mock:gate=rework]]")
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .build())
                        .node(WorkflowNode.builder("review", AGENT)
                                .agent("reviewer")
                                .rejectTo("implement")
                                .build())
                        .chain("implement", "review")
                        .build());
        Run run = f.start("wf");
        Run waiting = f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(waiting.requireNode("implement").reworkCount()).isEqualTo(1);
        assertThat(waiting.requireNode("review").status()).isEqualTo(NodeStatus.WAITING_APPROVAL);
        assertThat(waiting.requireNode("review").activity()).contains("返工预算耗尽");

        f.engine.approve(run.id(), "review", null);
        f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
    }

    @Test
    void humanDecisionWaitsForApproval() {
        f.agent(AgentDefinition.builder("pm")
                        .executor("mock")
                        .prompt("[[mock:gate=human]] [[mock:action=请确认需求范围]]")
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("clarify", AGENT).agent("pm").build())
                        .build());
        Run run = f.start("wf");
        Run waiting = f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(waiting.requireNode("clarify").gateResult().actionRequired()).isEqualTo("请确认需求范围");
        assertThat(f.engine.events(run.id(), 0, 0)).anyMatch(e -> e.message().contains("请确认需求范围"));
        f.engine.approve(run.id(), "clarify", "范围确认");
        f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
    }

    @Test
    void failDecisionFailsWithoutRework() {
        f.agent(AgentDefinition.builder("dev").executor("mock").prompt("x").build())
                .agent(AgentDefinition.builder("qa")
                        .executor("mock")
                        .prompt("[[mock:gate=fail]] [[mock:reason=无法修复]]")
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .build())
                        .node(WorkflowNode.builder("test", AGENT)
                                .agent("qa")
                                .rejectTo("implement")
                                .build())
                        .chain("implement", "test")
                        .build());
        Run run = f.start("wf");
        Run failed = f.awaitStatus(run.id(), RunStatus.FAILED);
        assertThat(failed.requireNode("test").error()).contains("无法修复");
        assertThat(failed.requireNode("implement").reworkCount()).isZero();
    }

    @Test
    void missingGateResultAsksOnceThenWaitsForHuman() {
        f.agent(AgentDefinition.builder("dev")
                        .executor("mock")
                        .prompt("[[mock:gate=none]]")
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .build())
                        .build());
        Run run = f.start("wf");
        Run waiting = f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(waiting.requireNode("implement").error()).contains("未收到节点终态");
        assertThat(f.eventTypes(run.id())).contains("gate.submit.missing");
    }

    @Test
    void missingArtifactFailsTheContract() {
        f.agent(AgentDefinition.builder("dev")
                        .executor("mock")
                        .prompt("[[mock:artifacts=none]]")
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("design", AGENT)
                                .agent("dev")
                                .artifacts(ArtifactContract.required("design", "design.md"))
                                .build())
                        .build());
        Run run = f.start("wf");
        Run failed = f.awaitStatus(run.id(), RunStatus.FAILED);
        assertThat(failed.requireNode("design").error())
                .contains("artifact contract")
                .contains("design.md");
    }

    @Test
    void verificationGateLoopsThenEscalatesToHuman() {
        f.agent(AgentDefinition.builder("dev")
                        .executor("mock")
                        .prompt("实现")
                        .verification(List.of(VerificationGate.command(
                                "tests",
                                "单元测试",
                                FailurePolicy.LOOP,
                                "echo round >> rounds.txt; test $(wc -l < rounds.txt) -ge 5")))
                        .loop(new AgentLoopPolicy(true, 3, LoopExhaustedPolicy.HUMAN, null))
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .build())
                        .build());
        Run run = f.start("wf");
        Run waiting = f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(waiting.requireNode("implement").error()).contains("verification 单元测试");
        assertThat(f.eventTypes(run.id()).stream().filter("agent.loop"::equals)).hasSize(2);
        assertThat(f.eventTypes(run.id())).contains("agent.loop.exhausted");
    }

    @Test
    void verificationGatePassesWhenCommandSucceeds() {
        f.agent(AgentDefinition.builder("dev")
                        .executor("mock")
                        .prompt("实现")
                        .artifacts(List.of(ArtifactContract.required("impl", "impl.md")))
                        .verification(List.of(VerificationGate.command("check", "产物检查", null, "test -s impl.md")))
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .build())
                        .build());
        Run done = f.awaitStatus(f.start("wf").id(), RunStatus.SUCCEEDED);
        assertThat(f.eventTypes(done.id())).contains("gate.passed");
    }

    @Test
    void failingGateOnRejectingNodeRoutesUpstream() {
        f.agent(AgentDefinition.builder("dev").executor("mock").prompt("实现").build())
                .agent(AgentDefinition.builder("qa")
                        .executor("mock")
                        .prompt("测试")
                        .verification(List.of(VerificationGate.command(
                                "e2e",
                                "端到端",
                                null,
                                "if [ -f .passed-once ]; then exit 0; fi; touch .passed-once; exit 1")))
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .build())
                        .node(WorkflowNode.builder("test", AGENT)
                                .agent("qa")
                                .rejectTo("implement")
                                .build())
                        .chain("implement", "test")
                        .build());
        Run done = f.awaitStatus(f.start("wf").id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("implement").reworkCount()).isEqualTo(1);
        assertThat(done.requireNode("test").attempt()).isEqualTo(2);
    }

    @Test
    void maxTurnsWaitsAndDeclineFailsTheRun() {
        f.agent(AgentDefinition.builder("dev")
                        .executor("mock")
                        .prompt("[[mock:turns=exhausted]]")
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .build())
                        .build());
        Run run = f.start("wf");
        Run waiting = f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(waiting.requireNode("implement").activity()).contains("对话轮次用尽");
        Run failed = f.engine.declineRetry(run.id(), "implement");
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void executorErrorIsRetriedThenFails() {
        f.agent(AgentDefinition.builder("dev")
                        .executor("mock")
                        .prompt("[[mock:error=provider unavailable]]")
                        .build())
                .workflow(WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("implement", AGENT)
                                .agent("dev")
                                .maxAttempts(2)
                                .build())
                        .build());
        Run failed = f.awaitStatus(f.start("wf").id(), RunStatus.FAILED);
        assertThat(failed.requireNode("implement").attempt()).isEqualTo(2);
        assertThat(failed.requireNode("implement").error()).contains("provider unavailable");
    }

    @Test
    void unknownAgentIsAConfigurationFailure() {
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("implement", AGENT)
                        .agent("ghost")
                        .maxAttempts(3)
                        .build())
                .build());
        Run failed = f.awaitStatus(f.start("wf").id(), RunStatus.FAILED);
        assertThat(failed.requireNode("implement").attempt()).isEqualTo(1);
        assertThat(failed.requireNode("implement").error()).contains("agent 'ghost' not found");
    }

    @Test
    void parallelBranchesRunConcurrently() {
        f.workflow(WorkflowDefinition.builder("fan")
                .node(WorkflowNode.builder("start", SCRIPT).script("echo start").build())
                .node(WorkflowNode.builder("left", SCRIPT)
                        .script("sleep 1; date +%s%N > left.txt")
                        .build())
                .node(WorkflowNode.builder("right", SCRIPT)
                        .script("sleep 1; date +%s%N > right.txt")
                        .build())
                .node(WorkflowNode.builder("join", SCRIPT)
                        .script("cat left.txt right.txt")
                        .build())
                .edge("start", "left")
                .edge("start", "right")
                .edge("left", "join")
                .edge("right", "join")
                .build());
        Instant started = Instant.now();
        Run done = f.awaitStatus(f.start("fan").id(), RunStatus.SUCCEEDED);
        Duration elapsed = Duration.between(started, Instant.now());
        assertThat(elapsed).isLessThan(Duration.ofMillis(1900));
        assertThat(done.requireNode("join").output().lines().count()).isEqualTo(2);
        Instant left = done.requireNode("left").startedAt();
        Instant right = done.requireNode("right").startedAt();
        assertThat(Duration.between(left, right).abs()).isLessThan(Duration.ofMillis(800));
    }

    @Test
    void cancelKillsTheRunningProcess() {
        f.workflow(WorkflowDefinition.builder("slow")
                .node(WorkflowNode.builder("sleep", SCRIPT).script("sleep 30").build())
                .build());
        Run run = f.start("slow");
        f.awaitNode(run.id(), "sleep", NodeStatus.RUNNING);
        Instant before = Instant.now();
        f.engine.cancel(run.id());
        Run cancelled = f.awaitStatus(run.id(), RunStatus.CANCELLED);
        assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofSeconds(10));
        assertThat(cancelled.requireNode("sleep").status()).isEqualTo(NodeStatus.CANCELLED);
        assertThatThrownBy(() -> f.engine.cancel(run.id())).isInstanceOf(RunStateException.class);
    }

    @Test
    void cancelWaitingRunSettlesImmediately() {
        f.workflow(WorkflowDefinition.builder("gate")
                .node(WorkflowNode.builder("approve", APPROVAL).build())
                .build());
        Run run = f.start("gate");
        f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(f.engine.cancel(run.id()).status()).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void pauseParksRunningNodeAndResumeContinues() {
        f.workflow(WorkflowDefinition.builder("slow")
                .node(WorkflowNode.builder("work", SCRIPT)
                        .script("if [ -f .resumed ]; then echo finished; else touch .resumed; sleep 30; fi")
                        .build())
                .build());
        Run run = f.start("slow");
        f.awaitNode(run.id(), "work", NodeStatus.RUNNING);
        f.engine.pause(run.id());
        Run paused = f.awaitStatus(run.id(), RunStatus.WAITING);
        assertThat(paused.pauseRequested()).isTrue();
        assertThat(paused.requireNode("work").paused()).isTrue();
        assertThatThrownBy(() -> f.engine.approve(run.id(), "work", null)).isInstanceOf(RunStateException.class);

        f.engine.resume(run.id(), null);
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("work").output()).contains("finished");
        assertThat(done.requireNode("work").attempt()).isEqualTo(2);
    }

    @Test
    void subSopRunsChildInSharedWorkspace() {
        f.workflow(WorkflowDefinition.builder("child")
                        .node(WorkflowNode.builder("write", SCRIPT)
                                .script("echo from-child > shared.txt; echo child done")
                                .artifacts(ArtifactContract.required("shared", "shared.txt"))
                                .build())
                        .build())
                .workflow(WorkflowDefinition.builder("parent")
                        .node(WorkflowNode.builder("sub", SOP)
                                .sopWorkflow("child")
                                .build())
                        .node(WorkflowNode.builder("read", SCRIPT)
                                .script("cat shared.txt")
                                .build())
                        .chain("sub", "read")
                        .build());
        Run run = f.start("parent");
        Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        String childId = done.requireNode("sub").childRunId();
        assertThat(f.engine.require(childId).status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(f.engine.require(childId).context().parentRunId()).isEqualTo(run.id());
        assertThat(done.requireNode("read").output()).contains("from-child");
        assertThat(f.engine.artifacts(run.id())).extracting(Artifact::name).contains("write.shared");
    }

    @Test
    void cyclicSubSopIsRejected() {
        f.workflow(WorkflowDefinition.builder("a")
                        .node(WorkflowNode.builder("call-b", SOP)
                                .sopWorkflow("b")
                                .build())
                        .build())
                .workflow(WorkflowDefinition.builder("b")
                        .node(WorkflowNode.builder("call-a", SOP)
                                .sopWorkflow("a")
                                .build())
                        .build());
        Run run = f.start("a");
        Run failed = f.awaitStatus(run.id(), RunStatus.FAILED);
        String child = failed.requireNode("call-b").childRunId();
        assertThat(child).isNotNull();
        assertThat(f.engine.require(child).requireNode("call-a").error()).contains("循环");
    }

    @Test
    void upstreamArtifactsAreHandedOffReadOnly() throws Exception {
        f.workflow(WorkflowDefinition.builder("handoff")
                .node(WorkflowNode.builder("plan", SCRIPT)
                        .script("printf 'plan body' > plan.md")
                        .artifacts(ArtifactContract.required("plan", "plan.md"))
                        .build())
                .node(WorkflowNode.builder("use", SCRIPT)
                        .script(
                                "cat .loopforge/inputs/plan/plan.md; test ! -w .loopforge/inputs/plan/plan.md || id -u | grep -qx 0")
                        .build())
                .chain("plan", "use")
                .build());
        Run done = f.awaitStatus(f.start("handoff").id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("use").output()).contains("plan body");
    }

    @Test
    void pythonScriptsAreSupported() {
        f.workflow(WorkflowDefinition.builder("py")
                .node(WorkflowNode.builder("calc", SCRIPT)
                        .python("print(6 * 7)")
                        .build())
                .build());
        Run done = f.awaitStatus(f.start("py").id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("calc").output()).contains("42");
    }

    @Test
    void scriptTimeoutFailsTheNode() {
        f.workflow(WorkflowDefinition.builder("t")
                .node(WorkflowNode.builder("hang", SCRIPT)
                        .script("sleep 30")
                        .timeoutSeconds(1)
                        .build())
                .build());
        Run failed = f.awaitStatus(f.start("t").id(), RunStatus.FAILED);
        assertThat(failed.requireNode("hang").error()).contains("timed out");
    }

    @Test
    void scriptDoesNotInheritServerSecrets() {
        f.workflow(WorkflowDefinition.builder("env")
                .node(WorkflowNode.builder("env", SCRIPT)
                        .script("env | cut -d= -f1 | sort")
                        .build())
                .build());
        Run done = f.awaitStatus(f.start("env").id(), RunStatus.SUCCEEDED);
        assertThat(done.requireNode("env").output().lines().toList())
                .contains("HOME", "PATH", "LOOPFORGE_RUN_ID")
                .doesNotContain("JAVA_TOOL_OPTIONS", "MAVEN_OPTS");
    }

    @Test
    void invalidWorkflowIsRejectedAtStart() {
        WorkflowDefinition cyclic = WorkflowDefinition.builder("cyclic")
                .node(WorkflowNode.builder("a", SCRIPT).script("true").build())
                .node(WorkflowNode.builder("b", SCRIPT).script("true").build())
                .edge("a", "b")
                .edge("b", "a")
                .build();
        assertThatThrownBy(() -> f.engine.start(cyclic, "", null))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("DAG");
        assertThatThrownBy(() -> f.engine.start("missing", "", null)).isInstanceOf(RunNotFoundException.class);
    }

    @Test
    void retryReplaysSucceededRunFromChosenNode() {
        f.workflow(WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("one", SCRIPT).script("echo one").build())
                .node(WorkflowNode.builder("two", SCRIPT).script("echo two").build())
                .chain("one", "two")
                .build());
        Run run = f.start("wf");
        f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThatThrownBy(() -> f.engine.retry(run.id(), null, null, null))
                .isInstanceOf(RunStateException.class)
                .hasMessageContaining("explicit node");
        f.engine.retry(run.id(), "two", "replay", null);
        Run replayed = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
        assertThat(replayed.requireNode("one").attempt()).isEqualTo(1);
        assertThat(replayed.requireNode("two").attempt()).isEqualTo(2);
    }
}
