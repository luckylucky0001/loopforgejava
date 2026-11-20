package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RetryStrategy;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.VerificationGate;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowEdge;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the task book an agent receives for one node (port of upstream {@code prompt.go} and {@code
 * gate_submit.go}). The role prompt is wrapped with deliverables, gate evidence and retry sections;
 * placeholders such as {@code {{input}}}, {@code {{feedback}}}, {@code {{upstream}}}, {@code
 * {{node.<id>.output}}} and {@code {{artifact.<node>.<name>}}} are expanded from the run state.
 */
public final class PromptCompiler {

    static final String DEFAULT_LOOP_FEEDBACK = "上一轮未通过 Agent 门禁。请根据以下反馈在同一工作区内修正，只重做失败部分：\n{{feedback}}";
    static final String RESUME_PROMPT = "请从刚才中断的位置继续执行。";

    private PromptCompiler() {}

    /** Full prompt for a fresh invocation. */
    public static String compile(
            Run run,
            WorkflowNode node,
            AgentDefinition agent,
            List<ArtifactContract> contracts,
            List<Artifact> artifacts,
            String inputManifest,
            String gateResultPath) {
        String prompt = taskBook(node, agent, contracts);
        prompt = expandPlaceholders(prompt, run, node, artifacts);
        String manifest = inputManifest == null ? "" : inputManifest.strip();
        boolean hadCatalog = prompt.contains("{{artifacts}}");
        prompt = prompt.replace("{{artifacts}}", manifest);
        if (!hadCatalog && !manifest.isEmpty()) {
            prompt += "\n\n# Upstream artifacts\n\n" + manifest;
        }
        prompt = appendWorkspaceLayout(prompt, run.repositories());
        prompt = appendArtifactContract(prompt, contracts);
        return appendGateInstruction(prompt, gateResultPath, node.rejectTo(), false);
    }

    /** Prompt for an in-loop self-correction round. */
    public static String withLoopFeedback(String prompt, AgentDefinition agent, String feedback) {
        String template = agent.loop().feedbackPrompt();
        if (template == null || template.isBlank()) {
            template = DEFAULT_LOOP_FEEDBACK;
        }
        return prompt + "\n\n" + template.replace("{{feedback}}", feedback);
    }

    /** Short prompt that continues an interrupted native CLI session. */
    public static String resume(String operatorFeedback, String gateResultPath, String rejectTo) {
        String prompt = RESUME_PROMPT;
        if (operatorFeedback != null && !operatorFeedback.isBlank()) {
            prompt += "\n\n操作员补充了以下信息，请结合它继续：\n\n" + operatorFeedback.trim();
        }
        return appendGateInstruction(prompt, gateResultPath, rejectTo, false);
    }

    /** Asks a resumed session to submit the missing terminal decision without redoing the task. */
    public static String missingGateResult(String gateResultPath, String rejectTo) {
        return appendGateInstruction("", gateResultPath, rejectTo, true).strip();
    }

    static String taskBook(WorkflowNode node, AgentDefinition agent, List<ArtifactContract> contracts) {
        StringBuilder b = new StringBuilder("# 节点任务书\n\n");
        section(b, "执行角色", agent.name());
        section(b, "任务说明", agent.prompt() == null ? "" : agent.prompt().strip());
        List<String> deliverables = new ArrayList<>();
        for (ArtifactContract contract : contracts) {
            deliverables.add(contract.name() + "：在工作区创建 `" + contract.path() + "`");
        }
        List<String> criteria = new ArrayList<>();
        for (VerificationGate gate : agent.verification()) {
            criteria.add(gate.name() + "必须通过，并保留真实命令证据");
        }
        list(b, "交付物", deliverables);
        list(b, "完成条件与门禁证据", criteria);
        list(b, "规则", agent.rules());
        b.append("## 失败反馈与重试\n\n");
        String strategy = node.retryStrategy() == RetryStrategy.FULL_REDO ? "完整重做" : "仅修复失败项";
        b.append("- 策略：").append(strategy).append('\n');
        if (!node.carryOver().isEmpty()) {
            b.append("- 继承上下文：").append(String.join("、", node.carryOver())).append('\n');
        }
        b.append("- 上一轮反馈：{{feedback}}\n");
        return b.toString();
    }

    static String expandPlaceholders(String prompt, Run run, WorkflowNode node, List<Artifact> artifacts) {
        String input = run.input();
        if (!input.isBlank() && !prompt.contains("{{input}}")) {
            prompt += "\n\n## 本次运行输入\n\n{{input}}";
        }
        prompt = prompt.replace("{{input}}", input);
        prompt = prompt.replace("{{rejectTo}}", node.rejectTo() == null ? "上游实现节点" : node.rejectTo());
        prompt = prompt.replace("{{repository}}", repositoryLine(run));
        if (!run.repositories().isEmpty() && !prompt.contains("{{existingMR}}")) {
            prompt += "\n\n## Git 仓库交付\n\n{{existingMR}}";
        }
        prompt = prompt.replace("{{existingMR}}", deliveryBlurb(run));
        prompt = prompt.replace("{{subject}}", "本次任务");
        String feedback = run.node(node.id()).map(NodeRun::feedback).orElse(null);
        prompt = prompt.replace("{{feedback}}", feedback == null ? "" : feedback);
        String human = run.humanContext() == null ? "" : run.humanContext().strip();
        prompt = prompt.replace("{{humanContext}}", human);
        if (!human.isEmpty()) {
            prompt += "\n\n## 人工补充上下文\n\n操作员在审核或重试时补充了以下信息，请优先采信并据此行动：\n\n" + human + "\n";
        }
        List<String> upstream = upstreamOutputs(run.workflow(), node, run);
        if (!upstream.isEmpty() && !prompt.contains("{{upstream}}")) {
            prompt += "\n\n## 上游节点输出\n\n{{upstream}}";
        }
        prompt = prompt.replace("{{upstream}}", String.join("\n\n", upstream));
        for (NodeRun nodeRun : run.nodeRuns()) {
            prompt = prompt.replace(
                    "{{node." + nodeRun.nodeId() + ".output}}", nodeRun.output() == null ? "" : nodeRun.output());
        }
        for (Artifact artifact : artifacts) {
            String content = artifact.content() == null ? "" : artifact.content();
            prompt = prompt.replace("{{artifact." + artifact.nodeId() + "." + artifact.name() + "}}", content);
        }
        return prompt;
    }

    /** Upstream {@code promptRepositoryLine}: the primary repository of the run. */
    static String repositoryLine(Run run) {
        if (run.repositories().isEmpty()) {
            return "未绑定代码仓库";
        }
        RepositoryContext rc = run.repositories().getFirst();
        String work = rc.writableWorkBranch() == null ? "只读，不提交" : rc.writableWorkBranch();
        return rc.name() + " (" + rc.provider().wire() + "), 最新远端基线 " + rc.baseBranch() + ", 本次工作分支 " + work;
    }

    /** Delivery instructions per writable repository (upstream {@code existingMRBlurb}). */
    static String deliveryBlurb(Run run) {
        StringBuilder b = new StringBuilder();
        for (RepositoryContext rc : run.repositories()) {
            String work = rc.writableWorkBranch();
            if (work == null) {
                b.append("- ")
                        .append(rc.name())
                        .append("：只读检出（")
                        .append(rc.baseBranch())
                        .append("），可以构建和测试，不要提交或推送。\n");
                continue;
            }
            PullRequest existing = run.deliveries().stream()
                    .filter(pr -> rc.repositoryId().equals(pr.repositoryId()) && pr.open())
                    .findFirst()
                    .orElse(null);
            b.append("- ").append(rc.name()).append("：只在工作分支 `").append(work).append("` 上提交并 push 到 origin");
            if (rc.requireExistingWorkBranch()) {
                b.append("（继续已有分支，不要新建或改名）");
            }
            if (rc.provider().hostsPullRequests()) {
                boolean gitlab = rc.provider() == io.github.loopforge4j.core.domain.RepositoryProvider.GITLAB;
                String kind = gitlab ? "MR" : "PR";
                if (existing != null) {
                    b.append("；已有本轮 ")
                            .append(kind)
                            .append(" ")
                            .append(existing.url())
                            .append("，在同一分支上更新，不要另开 ")
                            .append(kind);
                } else {
                    b.append("；然后创建从 `")
                            .append(work)
                            .append("` 到 `")
                            .append(rc.baseBranch())
                            .append("` 的 ")
                            .append(kind)
                            .append(
                                    gitlab
                                            ? "（`glab mr create` 或 GitLab API 可用，凭据已注入，不要打印或保存令牌）"
                                            : "（`gh pr create` 可用，凭据已注入，不要打印或保存令牌）");
                }
            } else {
                b.append("；报告分支名和提交 SHA（该托管平台不支持自动创建 PR）");
            }
            b.append("。禁止向 ").append(rc.baseBranch()).append(" 直接推送。\n");
        }
        return b.toString().stripTrailing();
    }

    /** Upstream {@code appendArtifactContract} workspace layout: one checkout per first-level directory. */
    static String appendWorkspaceLayout(String prompt, List<RepositoryContext> repositories) {
        if (repositories.isEmpty()) {
            return prompt;
        }
        StringBuilder b = new StringBuilder(prompt);
        b.append("\n\n# Workspace layout\n\nRepository checkouts live in first-level subdirectories. Write code changes"
                + " inside those directories. SOP deliverables such as `fix-plan.md` go at the run workspace root (the"
                + " current working directory), never inside a repository directory.\n");
        for (RepositoryContext rc : repositories) {
            b.append("- `./")
                    .append(rc.workspaceDir())
                    .append("`: ")
                    .append(rc.name())
                    .append('\n');
        }
        return b.toString();
    }

    static List<String> upstreamOutputs(WorkflowDefinition workflow, WorkflowNode node, Run run) {
        List<String> outputs = new ArrayList<>();
        for (WorkflowEdge edge : workflow.edges()) {
            if (!edge.target().equals(node.id())) {
                continue;
            }
            run.node(edge.source())
                    .map(NodeRun::output)
                    .filter(o -> !o.isBlank())
                    .ifPresent(o -> outputs.add("[" + edge.source() + "]\n" + o));
        }
        return outputs;
    }

    static String appendArtifactContract(String prompt, List<ArtifactContract> contracts) {
        if (contracts.isEmpty()) {
            return prompt;
        }
        StringBuilder b = new StringBuilder(prompt);
        b.append("\n\n# Required artifact contract\n\nBefore finishing, create these files as **relative paths at the"
                + " run workspace root** (the current working directory). Never write them to a host absolute path.\n");
        for (ArtifactContract contract : contracts) {
            b.append("- `")
                    .append(contract.path())
                    .append("`: ")
                    .append(contract.name())
                    .append(" (")
                    .append(contract.mediaType())
                    .append(", ")
                    .append(contract.required() ? "required" : "optional")
                    .append(")\n");
        }
        return b.toString();
    }

    static String appendGateInstruction(String prompt, String path, String rejectTo, boolean retry) {
        if (path == null) {
            return prompt;
        }
        String schema =
                "{\"decision\": \"pass|rework|fail|human\", \"reason\": \"事实依据\", \"actionRequired\": \"需要人或上游做什么\","
                        + " \"summary\": \"可选摘要\", \"findings\": [{\"severity\": \"high\", \"title\": \"问题\", \"evidence\": \"证据\"}]}";
        if (retry) {
            return prompt + "\n\n刚才没有收到有效的节点终态。不要重复执行任务，也不要只输出文字；请立刻调用 MCP 工具 `submit_gate_result` 提交终态，"
                    + "没有该工具时把终态写入 `" + path + "`，内容为 JSON：" + schema
                    + "。选择 human 时必须填写 actionRequired。只有该文件写入成功才算完成本节点。";
        }
        String rework = rejectTo == null
                ? "- rework：本节点没有配置返工目标，不可选择；需要直接终止时请选择 fail；"
                : "- rework：存在应由上游节点「" + rejectTo + "」返工的问题；平台会自动打回；";
        return prompt.stripTrailing()
                + "\n\n# 必须提交节点终态\n\n完成任务后必须提交唯一终态：优先调用 MCP 工具 `submit_gate_result`（loopforge-gate 服务，"
                + "调用一次即可，之后立即停止）；当前 CLI 没有该工具时，把终态写入 JSON 文件 `" + path
                + "`（相对当前工作目录，目录不存在时先创建）。内容格式：\n\n```json\n" + schema + "\n```\n\n"
                + "- pass：任务正常完成；平台会自动流转到下游节点；\n" + rework + "\n"
                + "- fail：本节点无法完成或出现不可恢复错误；平台会直接标记失败，不打回上游；\n"
                + "- human：无法可靠自动判断，或必须由人决策；平台会暂停并等待人工确认，此时 actionRequired 必填。\n\n"
                + "必须根据当前真实情况选择，不要把 fail 当成 rework，也不要用 human 掩盖明确失败。reason 必须解释作出该终态的事实依据。"
                + "不要用报告文本或最终回复代替终态。只有终态提交成功且内容合法，本节点才算完成。";
    }

    private static void section(StringBuilder b, String title, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        b.append("## ").append(title).append("\n\n").append(value).append("\n\n");
    }

    private static void list(StringBuilder b, String title, List<String> values) {
        List<String> nonBlank =
                values.stream().filter(v -> v != null && !v.isBlank()).toList();
        if (nonBlank.isEmpty()) {
            return;
        }
        b.append("## ").append(title).append("\n\n");
        nonBlank.forEach(v -> b.append("- ").append(v).append('\n'));
        b.append('\n');
    }
}
