# 移植对照

基于上游 [Tencent/LoopForge](https://github.com/Tencent/LoopForge) `65f2077`（MIT）。Java 代码为重写，不包含 Go 源文件。

## 包对应

| 上游 Go | LoopForge4J | 状态 |
| --- | --- | --- |
| `internal/domain` (workflow.go, run.go, agent.go) | `core.domain` | 已移植引擎所需子集，JSON 字段名一致 |
| `internal/engine/workflow_graph.go` | `core.engine.WorkflowGraph`、`WorkflowValidator` | 已移植 |
| `internal/engine/node_execute.go`、`node_outcome.go`、`gate_submit.go` | `core.engine.NodeRunner`、`GateResults` | 已移植 |
| `internal/engine/run_ops.go`、`run_state.go`、`lifecycle.go` | `core.engine.RunEngine`、`RunScheduler` | 已移植 |
| `internal/engine/prompt.go`、`human_context.go` | `core.engine.PromptCompiler`、`RunEngine.recordHumanContext` | 已移植（含 `{{repository}}`、`{{existingMR}}`） |
| `internal/engine/artifacts.go` | `core.engine.ArtifactSupport` | 已移植（repository-patch 除外） |
| `internal/engine/sop_node.go` | `NodeRunner.runChild` | 基础版本 |
| `internal/runtime/provider_*.go`、`protocol.go` | `core.runtime.executor` | Claude Code、Codex、CodeBuddy、Mock |
| `internal/runtime/sandbox_local.go`、`process.go` | `core.runtime.sandbox` | 本地沙箱 |
| `internal/repo` | `server.persistence` | runs/workflows/agents/events/artifacts |
| `internal/httpapi` | `server.web` | runs、workflows、agents、executors、catalog、stream |
| `migrations/seed/001_public_catalog.sql`（welcome-workflow） | `catalog/welcome.json` | 已移植 |
| `internal/engine/repository_workspace.go`、`git_*.go` | `core.runtime.git.GitWorkspace`、`GitEnvironment` | 已移植（克隆、工作分支、分支模式、空远端初始化） |
| `internal/engine/mr_gate.go`（`verifyRunMRs`、`mrCoverageProblem`） | `NodeRunner.runPullRequestGate`、`core.delivery.Deliveries` | 已移植 |
| `internal/tracker/github.go` | `server.delivery.GitHubDeliveryProvider` | PR 查询与合并检测（GitLab/工蜂待补） |
| `internal/httpapi/repositories.go` | `server.web.RepositoryController` | 已移植 |

## 有意的差异

| 主题 | 上游 | 本移植 | 原因 |
| --- | --- | --- | --- |
| 节点终态提交 | MCP 工具 `submit_gate_result` 写 JSON 文件 | Agent 直接写同格式 JSON 文件（`.loopforge/gate-results/<node>/attempt-N.json`） | 所有 CLI 都能写文件，免去 MCP 配置；MCP 通道列入路线图 |
| 错误分类 | 匹配错误字符串（`"verification "`、`"max turns"`） | `FailureKind` 枚举 | 可读、可测、不怕改文案 |
| 暂停/中断标记 | 匹配 activity 文案（"用户暂停"、"等待恢复"） | `NodeRun.paused` / `interrupted` 字段 | 同上 |
| 人工追加尝试 | 修改冻结快照的 `maxAttempts` | `NodeRun.maxAttemptsOverride` | 快照保持不可变 |
| 取消 | `context.Context` 取消 | `Cancellation` 令牌，不中断线程 | 避免中断破坏 JDBC/H2 通道 |
| 提示词传递 | argv（超 96 KiB 改 stdin） | 始终 stdin（UTF-8） | 非 UTF-8 locale 下 JVM 会损坏中文 argv；不暴露在 `ps` |
| 校验报错 | 返回第一个错误 | 返回全部问题 | 便于设计器一次性展示 |
| 控制目录 | `.agentflow/` | `.loopforge/` | 与上游运行时区分 |
| 数据种子 | 运维手动执行 SQL | 启动时可选导入 JSON 目录（只插入不覆盖，`prod` 关闭） | 等价于 `INSERT IGNORE`，开箱可体验 |
| 节点超时 | 无 | 可选 `timeoutSeconds` + 全局默认 | 防止卡死的 CLI 占用调度 |
| 进程组 | `Setpgid` + `kill(-pgid)` | `setsid` + `kill -KILL -pgid` | 等价；JVM 无法直接 setpgid |
| 取消期间的人工操作 | 部分操作会清除取消/暂停标志 | 取消待处理时拒绝操作；结束暂停时释放所有暂停分支 | 修复并发审查发现的竞态 |
| 多实例 | 运行租约 + 心跳 + 接管 | 单实例（尚未移植租约） | 列入路线图迭代 4 |
| Git 凭据 | 按负责人账户解析 | 部署级配置（`loopforge.git.https` 列表、SSH 私钥文件） | 账户体系在迭代 5；`GitCredentialProvider` 端口已按运行解析 |
| 本地仓库路径 | 允许 | 默认拒绝 `file:`/本地路径与 `http://`，需 `loopforge.git.allow-local-remotes` | 防止运行读取服务器任意目录、凭据明文传输 |
| 克隆 URL 内嵌凭据 | 允许 | 拒绝 | 凭据只能来自服务端配置 |

## 尚未移植

Web UI 与 Chat/Main Bot、会话工作区、GitLab/工蜂 MR 交付、Docker 沙箱、执行机器（runner）与云端池、MCP（平台 MCP、门禁 MCP、角色 MCP）、Skills 与 Hooks 物化、账户/项目/需求/Issue、定时任务与市场、通知与 Bot、分析与成本定价、预览构建、知识库、多实例租约、其余 7 种 CLI（OpenCode、Cursor、Antigravity、Kimi、Pi、DeepSeek Harness、ZCode）。排期见 [roadmap.md](roadmap.md)。
