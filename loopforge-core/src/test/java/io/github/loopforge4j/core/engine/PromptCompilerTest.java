package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PromptCompilerTest {

    private final WorkflowDefinition wf = WorkflowDefinition.builder("wf")
            .node(WorkflowNode.builder("design", NodeType.AGENT).agent("arch").build())
            .node(WorkflowNode.builder("review", NodeType.AGENT)
                    .agent("rev")
                    .rejectTo("design")
                    .build())
            .chain("design", "review")
            .build();

    @Test
    void wrapsRolePromptIntoTaskBook() {
        Run run = Run.create("r", wf, "做一个登录页", ExecutionContext.EMPTY, Instant.EPOCH);
        run.requireNode("design").status(NodeStatus.SUCCEEDED);
        run.requireNode("design").output("设计已完成");
        run.requireNode("review").feedback("上次缺少截图");
        AgentDefinition reviewer = AgentDefinition.builder("rev")
                .name("评审")
                .prompt("评审 {{rejectTo}} 的方案")
                .rules(List.of("不得伪造证据"))
                .verification(List.of(VerificationGate.command("t", "测试", FailurePolicy.FAIL, "true")))
                .build();
        List<ArtifactContract> contracts = List.of(ArtifactContract.required("review", "review.md"));

        String prompt = PromptCompiler.compile(
                run,
                wf.requireNode("review"),
                reviewer,
                contracts,
                List.of(),
                "## design / plan",
                GateResults.path("review", 1));

        assertThat(prompt)
                .startsWith("# 节点任务书")
                .contains("## 执行角色\n\n评审")
                .contains("评审 design 的方案")
                .contains("- review：在工作区创建 `review.md`")
                .contains("测试必须通过")
                .contains("- 不得伪造证据")
                .contains("上一轮反馈：上次缺少截图")
                .contains("## 本次运行输入\n\n做一个登录页")
                .contains("[design]\n设计已完成")
                .contains("# Upstream artifacts\n\n## design / plan")
                .contains("# Required artifact contract")
                .contains(".loopforge/gate-results/review/attempt-1.json")
                .contains("存在应由上游节点「design」返工的问题");
    }

    @Test
    void humanContextIsPreferred() {
        Run run = Run.create("r", wf, "", ExecutionContext.EMPTY, Instant.EPOCH);
        run.appendHumanContext("请使用 PostgreSQL");
        String prompt = PromptCompiler.compile(
                run,
                wf.requireNode("design"),
                AgentDefinition.builder("arch").prompt("设计").build(),
                List.of(),
                List.of(),
                "",
                null);
        assertThat(prompt).contains("## 人工补充上下文").contains("请使用 PostgreSQL").doesNotContain("必须提交节点终态");
    }

    @Test
    void resumeAndMissingGatePromptsAreShort() {
        assertThat(PromptCompiler.resume("补充：用 Java 21", "g.json", null))
                .startsWith(PromptCompiler.RESUME_PROMPT)
                .contains("用 Java 21")
                .contains("没有配置返工目标");
        assertThat(PromptCompiler.missingGateResult("g.json", "x"))
                .contains("不要重复执行任务")
                .contains("g.json");
    }
}
