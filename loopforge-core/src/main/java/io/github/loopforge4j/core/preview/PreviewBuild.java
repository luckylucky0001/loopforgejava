package io.github.loopforge4j.core.preview;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AgentLoopPolicy;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.ExecutionScope;
import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.LoopExhaustedPolicy;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Position;
import io.github.loopforge4j.core.domain.PreviewContract;
import io.github.loopforge4j.core.domain.RetryStrategy;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowEdge;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The agent step a frontend preview contract adds to an SOP (upstream {@code AttachPreviewBuild}).
 * The ids match upstream so SOPs exported there import here unchanged.
 */
public final class PreviewBuild {

    public static final String NODE_ID = "af-preview-build";
    public static final String AGENT_ID = "af-preview-builder";
    public static final String EDGE_ID = "af-preview-build-edge";
    public static final String ARTIFACT = "frontend-preview";

    private PreviewBuild() {}

    public static boolean isBuildNode(String nodeId) {
        return nodeId != null && nodeId.strip().equals(NODE_ID);
    }

    public static boolean isBuildAgent(String agentId) {
        return agentId != null
                && (agentId.strip().equals(AGENT_ID) || agentId.strip().equals(NODE_ID));
    }

    /** Removes the build step and its edges (the contract was switched off). */
    public static WorkflowDefinition strip(WorkflowDefinition workflow) {
        if (workflow.node(NODE_ID).isEmpty()) {
            return workflow;
        }
        return workflow.withGraph(
                workflow.nodes().stream().filter(n -> !isBuildNode(n.id())).toList(),
                workflow.edges().stream()
                        .filter(e -> !isBuildNode(e.source()) && !isBuildNode(e.target()))
                        .toList());
    }

    /**
     * Makes sure the build step exists while the contract is on. A step the author placed keeps its
     * position and edges; a missing one is appended after every node without a success successor.
     */
    public static WorkflowDefinition attach(WorkflowDefinition workflow) {
        PreviewContract contract = workflow.preview();
        if (contract == null || !contract.isStatic()) {
            return strip(workflow);
        }
        if (workflow.node(NODE_ID).isPresent()) {
            return workflow;
        }
        List<String> sinks = successSinks(workflow);
        List<WorkflowNode> nodes = new ArrayList<>(workflow.nodes());
        nodes.add(node(workflow));
        List<WorkflowEdge> edges = new ArrayList<>(workflow.edges());
        for (int i = 0; i < sinks.size(); i++) {
            edges.add(new WorkflowEdge(EDGE_ID + "-" + (i + 1), sinks.get(i), NODE_ID, WorkflowEdge.WHEN_SUCCESS));
        }
        return workflow.withGraph(nodes, edges);
    }

    static WorkflowNode node(WorkflowDefinition workflow) {
        PreviewContract contract = workflow.preview();
        double x = 110;
        double y = 120;
        if (!workflow.nodes().isEmpty()) {
            x = workflow.nodes().stream()
                            .mapToDouble(n -> n.position().x())
                            .max()
                            .orElse(0)
                    + 280;
            y = workflow.nodes().stream()
                    .mapToDouble(n -> n.position().y())
                    .max()
                    .orElse(0);
        }
        return WorkflowNode.builder(NODE_ID, NodeType.AGENT)
                .name("构建静态站点")
                .agent(AGENT_ID)
                .executor(contract.executorId())
                .scope(ExecutionScope.RUN)
                .artifacts(artifact(contract))
                .onFailure(FailurePolicy.FAIL)
                .retryStrategy(RetryStrategy.TARGETED_FIX)
                .carryOver(List.of("last_error"))
                .maxAttempts(2)
                .maxReworks(1)
                .position(new Position(x, y))
                .build();
    }

    /** The synthetic role of the build step: it is never stored in the catalog. */
    public static AgentDefinition agent(PreviewContract contract) {
        return AgentDefinition.builder(AGENT_ID)
                .name("构建静态站点")
                .description("系统步骤：产出可托管的静态站点，再由预览收尾归档。")
                .executor(contract.executorId())
                .model(contract.model())
                .prompt(prompt(contract))
                .artifacts(List.of(artifact(contract)))
                .loop(new AgentLoopPolicy(true, 2, LoopExhaustedPolicy.FAIL, null))
                .build();
    }

    public static ArtifactContract artifact(PreviewContract contract) {
        return new ArtifactContract(ARTIFACT, contract.entry(), "text/html", true);
    }

    public static String prompt(PreviewContract contract) {
        String source = contract.sourcePath();
        String index = contract.indexFile();
        String entry = contract.entry();
        return "把当前工作区里的前端应用打成可独立托管的静态站点，不要让用户再手写构建脚本。\n\n"
                + "这是 LoopForge 在 SOP 勾选「前端预览」后自动追加的构建步骤。工作区可能包含一个或多个代码仓库子目录，上游节点已经完成实现与测试。"
                + "构建完成后平台会打包静态目录并删除工作区。\n\n"
                + "### 步骤\n\n"
                + "1. 识别前端工程（package.json、vite、webpack、rspack、next 静态导出等）。\n"
                + "2. 若工作区没有前端工程（无 Vite/webpack/Next 等配置、无前端依赖的 package.json、无 index.html），不要另起项目或手写预览页，不要创建 `"
                + source + "/" + index + "`，直接说明原因并结束。\n"
                + "3. 安装依赖并执行生产构建，不要启动开发服务器。\n"
                + "4. 生产构建必须使用相对资源根：Vite 设 `base: './'`，webpack/rspack 设 `publicPath: 'auto'` 或 `'./'`，禁止 `base: '/'` 或把资源写死成网站根路径。\n"
                + "5. 不要依赖开发服务器；预览打开时用户可配置后端地址，网关会拦截 fetch/XHR 转到该地址，不要为了预览另写一套绝对 API。\n"
                + "6. 把最终可浏览的静态文件放到工作区根目录 `" + source + "/`，入口文件必须是 `" + index
                + "`；若产物落在仓库子目录内，复制或同步到工作区根的 `" + source + "/`。\n"
                + "7. 不要修改 git remote，不要提交无关文件；构建失败时根据日志只修复失败项后重试。\n\n"
                + "### 完成标准\n\n"
                + "- 静态站点入口 `" + entry + "` 必须存在、可被直接打开，且同目录包含页面引用的 JS/CSS/资源。\n"
                + "- 入口和 chunk 的资源地址必须是相对路径（`./assets/...`），不能依赖网站根 `/assets/`。\n"
                + "- 不要留下需要 `npm run dev` 才能访问的开发服务器。\n\n"
                + "### 禁止\n\n"
                + "- 只使用现有仓库，不要另起无关项目。\n"
                + "- 禁止在非前端仓库里编造静态站点来满足产物契约。\n"
                + "- 不要把源码、node_modules 或 .git 拷进 " + source + "。\n";
    }

    private static List<String> successSinks(WorkflowDefinition workflow) {
        Set<String> withSuccessor = new HashSet<>();
        for (WorkflowEdge edge : workflow.edges()) {
            if (edge.when() == null
                    || edge.when().isBlank()
                    || edge.when().equalsIgnoreCase(WorkflowEdge.WHEN_SUCCESS)) {
                withSuccessor.add(edge.source());
            }
        }
        return workflow.nodes().stream()
                .map(WorkflowNode::id)
                .filter(id -> !withSuccessor.contains(id))
                .toList();
    }
}
