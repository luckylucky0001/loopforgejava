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
| `internal/tracker/github.go` | `server.delivery.GitHubDeliveryProvider` | PR 查询与合并检测 |
| `internal/tracker/external_mr.go`（GitLab 兼容 API） | `server.delivery.GitLabDeliveryProvider`、`core.runtime.git.RepositoryRemote` | MR 查询与合并检测（GitLab / 工蜂） |
| `internal/repo/run_admission.go`（`PromoteQueuedRuns`） | `core.engine.AdmissionPolicy`、`RunEngine.promoteQueuedRuns` | 全局与每项目上限（未移植按执行器分桶） |
| `internal/domain/token_pricing.go` | `core.pricing.TokenPricing` | 价格表版本 public-standard-2026-11-05，可配置覆盖 |
| `internal/httpapi/repositories.go` | `server.web.RepositoryController` | 已移植 |
| `internal/gatemcp`、`cmd/agentflow-gate-mcp`、`runtime/gate_mcp.go` | `core.mcp.GateMcpServer`、`GateMcpPackage` | 已移植（Go 二进制 → 仅依赖 JDK 的 jar） |
| `internal/runtime/materialize.go`、`skill_md.go`、`hooks.go` | `core.runtime.materialize` | 已移植（Python Hook 包装器 → POSIX sh） |
| `internal/runtime/provider_claude.go`、`provider_codebuddy.go`、`provider_codex.go`（物化部分） | `CliAgentExecutor.addCompiledArgs`、`CodexMcpArgs` | 已移植 |
| `internal/runtime/agent_cli.go`、`agent_cli_stream.go`、`agent_cli_zcode.go`、`provider_agentcli.go` | `AgentCliExecutor`、`AgentCliTranslators`、`ClaudeStream` | 已移植（模型网关与个人凭据渲染除外） |
| `internal/runtime/sandbox_docker.go` | `core.runtime.sandbox.DockerSandbox*` | 每运行一个容器；镜像构建与环境定义未移植 |
| `internal/engine/run_recovery.go`（租约、接管、工作区主机绑定）、`internal/repo/run_admission.go`（租约 SQL） | `core.engine.LeaseKeeper`、`RunEngine.claimOrphanedRuns`、`server.persistence.JdbcInstanceRegistry` | 已移植（排队准入/桶限流未移植） |
| `internal/observability`（遥测批量）、分析统计 | `core.engine.TelemetryBuffer`、`server.ops.RunMetrics` | 指标以 Micrometer 暴露 |
| `internal/domain/user.go`、`project.go`、`internal/httpapi` 账户/项目部分 | `server.account` | 密码账户 + Token；未移植 OAuth/企业身份、个人凭据库 |
| `internal/domain/issue.go`、`requirement.go`、`work_branch.go` | `server.work.Requirement*`、`ExternalRef` | 平台需求与外部来源 |
| `internal/domain/issue_sop.go`、`sop_lane.go`、`internal/repo/requirement.go`（快照）、`internal/httpapi/dispatch.go`（泳道派发与交接） | `server.work.SopBinding`、`SopPlaybook`、`RequirementSnapshot`、`RequirementService`、`PlaybookController`、`core.domain.SopLanes` | 需求 SOP 链与项目套餐、按泳道派发（重跑需显式指定、交接说明）、泳道快照（节点阶段、子 SOP、用量、整体状态与生命周期）、泳道标签与推断 |
| `internal/repo/revisions*`、`internal/httpapi`（revisions / publish / clone / logs / schedule-job-executions） | `server.persistence.DefinitionRevision*`、`RevisionSummary`、`web.WorkflowController`、`RunController`、`work.ScheduleExecution*` | SOP 与角色历史版本（保留 100 个、作者、变化摘要）与恢复、发布与复制、运行日志分页与下载、删除已结束运行、定时任务执行记录（保留 200 条） |
| `internal/repo` 个人配置（执行器凭据、Git 连接）、`internal/runtime/agent_cli.go`（凭据映射） | `server.account.PersonalCredentials*`、`CredentialCipher`、`core.runtime.executor.PersonalCredentialVariables` | 个人模型凭据与 Git Token：加密保存、按负责人（运行发起人、定时任务创建人、发言人、澄清发起人）解析，执行器凭据整体替代平台凭据 |
| `internal/httpapi/assistant_schedule.go`、`assistant_tasks.go`、调度触发配置 | `server.work.ScheduleService`（`kind` = `assistant` / `dispatch`）、`AssistantService.runScheduled` | AI 定时任务（以最后保存人的身份与凭据、完全权限运行一轮助手对话）与按状态自动派发需求的 SOP |
| `internal/domain` Dockerfile / Environment / SandboxProfile 与 `/api/dockerfiles`、`/api/environments`、`/api/sandboxes` | `core.domain.*Definition`、`core.runtime.sandbox.ProfileSandboxProvider`、`EnvironmentSandbox`、`server.sandbox.*` | 目录中的沙箱配置（local / native / docker）、环境（变量、PATH、每工作区一次的安装脚本）、按内容哈希构建并复用的 Dockerfile 镜像 |
| `internal/httpapi/skillhub_client.go`、`skillhub_market.go` | `server.skillhub.*` | SkillHub 市场：浏览、安装与更新为目录技能（来源记录、匿名用户标识、跨主机跳转不带 API Key） |
| `internal/domain/preview*.go`、`internal/preview/*`（归档、网关、会话、下载）、`internal/httpapi/run_preview.go`、runner 预览上传 | `core.domain.PreviewContract`、`core.preview.*`（`PreviewBuild`、`PreviewArchive`、`FrontendDetector`）、`core.engine.PreviewSteps`、`server.preview.*`、`runner.RemotePreviewStore` | 前端预览约定与自动追加的构建步骤、非前端跳过、构建后立即归档、会话链接（哈希令牌、滑动续期、撤销）、后端地址拦截、HTML / CSS / JS 路径改写与 History 适配、保留期过期、执行机器上传；另有可写构建命令的 `preview` 节点。站点存数据库（未移植 tar.zst 内容寻址存储与截图） |
| `internal/runeval/*`、`internal/domain/run_eval.go` | `core.eval.*`、`server.eval.*` | 运行评测：事实、加权维度、评测提示与结果约定、建议应用到当前 SOP / 角色 |
| `internal/httpapi/clarify_jobs.go`、`clarify_session.go`、`issue_routes.go`（澄清） | `server.work.Clarification*`、`core.engine.Conversations.checkout` | AI 澄清：后台轮次、只读检出、续接会话、范围规则与轮数收敛、结论写入描述与回写原单 |
| `internal/tracker/service.go`、`tapd_url.go` | `server.tracker`（`TrackerService`、`GitHubIssues`、`GitLabIssues`、`TapdClient`、`TapdLinks`） | 拉取、TAPD 链接导入、推送、交付回写、定时同步；直接调用 GitHub / GitLab / TAPD 开放 API |
| `internal/domain/shared_schedule.go`、`schedule_execution.go` | `server.work.Schedule*` | cron 定时运行 |
| `internal/domain/notification*.go`、`internal/notification` | `server.work.Notifier`、`NotificationChannel`、`BotMessages` | Webhook 与企业微信 / 飞书 / 钉钉 / Slack 群机器人；未移植企业微信智能机器人长连接（卡片内审批）与 AI 运行报告 |
| `internal/runtime/sandbox_process.go`（bwrap） | `core.runtime.sandbox.BwrapSandboxProvider` | 已移植（Linux bwrap；macOS seatbelt 未移植） |
| `internal/runner`、`cmd/agentflow-runner`、`internal/repo/run_admission.go`（runner 桶） | `loopforge-runner`（`RunnerAgent`、`RemoteRunStore`、`RemoteCatalogStore`）、`server.runner`、`RunEngine.runnerId` | 个人机器与共享执行池；机器本身运行完整引擎 |
| `chatbot/`、`internal/httpapi/assistant*.go`、`internal/runtime/conversation*.go`（Main Bot 与对话工作区） | `core.engine.Conversations`、`server.assistant` | 个人 / 项目对话、持久工作区、CLI 会话续接、平台 MCP 代表发言人、实时活动、停止；未移植群协作、浏览器工具、对话内自动登记 PR |
| `internal/auth`（企业身份） | `server.account.OidcService`、`OidcClient`、`OidcController` | 标准 OIDC 授权码 + PKCE（JDK 实现，RS256/ES256） |
| `web/`（React 控制台） | `static/console`（原生 ES 模块） | 重写，不含上游前端代码与素材 |
| `internal/mcpserver`、`internal/application/operations.go`、`cmd/agentflow-mcp` | `server.mcp.PlatformMcpController`、`PlatformOperations`、`core.mcp.PlatformMcpBridge` | 已移植（操作清单对应本移植的 API） |
| `internal/runtime/platform_mcp.go`、`internal/engine/assets.go`（平台/知识库注入） | `core.mcp.PlatformMcp`、`NodeRunner.bindPlatformMcp`、`server.mcp.ServerPlatformMcp` | 已移植 |
| `internal/knowledge`、`internal/httpapi/knowledge*.go`、`internal/repo/run_knowledge.go` | `server.knowledge` | 已移植（上游 MCP 客户端、来源授权、代理、审核写回、调用记录） |
| `internal/repo/analytics*.go`、`insights.go` | `server.analytics` | 概览、趋势、拆分、失败聚类、热力、SOP 指标；未移植业务域报表、会话统计、质量评估 |
| `internal/domain/assets.go`（rules/skills/mcp） | `core.domain.RuleDefinition`、`SkillDefinition`、`McpServerDefinition` | 文本文件（二进制 skill 文件、市场来源未移植） |

## 有意的差异

| 主题 | 上游 | 本移植 | 原因 |
| --- | --- | --- | --- |
| 节点终态提交 | MCP 工具 `submit_gate_result` 写 JSON 文件 | 同样提供 `submit_gate_result`（Java 实现）；提示词同时给出文件通道作为回退 | 没有 MCP 的执行器（Mock、未开私有 HOME 的 CLI）仍可提交 |
| 系统指令传递 | Claude `--system-prompt-file`、Codex `developer_instructions`、部分 CLI 前置 | 统一以 `<system_instructions>` 前置在任务书里（续接会话时不重复） | 与 CLI 版本无关；内容不出现在命令行 |
| Agent CLI 提示词 | argv（超长改为引用文件） | argv 只传一行 ASCII 指针，任务书在 `.loopforge/prompts/` | 不受 locale 和 argv 长度限制、不出现在 `ps` |
| Docker 内的门禁 MCP | 交叉编译 Linux 二进制写入工作区 | 只读挂载服务端 JDK 到同一路径 | 无需额外构建产物；需要 glibc 镜像 |
| MCP 密钥 | 解析后写入配置/argv | 只写入每次调用的运行时目录（0600，调用结束删除）；Codex 用启动脚本与 `env_http_headers`，Docker 用 `-e NAME` 按名传递 | 密钥不出现在命令行、运行数据和日志 |
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
| 多实例 | 运行租约 + 心跳 + 接管 | 同样的租约模型；另外用实例注册表里的 pid 检测同机已退出的进程，单实例重启无需等待 TTL | 崩溃后立即恢复 |
| Git 凭据 | 按负责人账户解析 | 部署级配置（`loopforge.git.https` 列表、SSH 私钥文件） | 账户体系在迭代 5；`GitCredentialProvider` 端口已按运行解析 |
| 本地仓库路径 | 允许 | 默认拒绝 `file:`/本地路径与 `http://`，需 `loopforge.git.allow-local-remotes` | 防止运行读取服务器任意目录、凭据明文传输 |
| 克隆 URL 内嵌凭据 | 允许 | 拒绝 | 凭据只能来自服务端配置 |
| Agent 的平台 MCP 凭据 | 部署级 Token（全部操作） | 每次调用签发的运行令牌：只读、只能访问运行所在项目、运行结束即失效 | Agent 受提示词注入影响，不应拥有写权限 |
| 知识库代理令牌 | 由上游 Token 派生的密钥签名，48 小时有效 | 数据库共享密钥签名；运行结束即失效 | 多实例共享，泄露窗口更短 |
| `knowledge.md` | 需 SOP 声明为产物 | 开启知识库的角色自动收集（可选产物） | 使用规范要求写这个文件，不应依赖每个 SOP 记得声明 |
| 需求同步的通道 | 内部 MCP 网关（工蜂 `search_project_issues`、TAPD `proxy_execute_tool`） | GitHub / GitLab REST 与 TAPD 开放 API | 其他部署没有这些内部网关；开放 API 可直接用部署级凭据 |
| 同步时已关闭的外部项 | 也导入（状态为已关闭） | 首次看到时已关闭则不导入 | 避免历史 Issue 涌入待办 |
| TAPD 迭代对账 | 删除不在迭代里的未保留条目 | 只删除未开始（待办/待规划、无运行）的条目 | 已在执行的需求不应因外部调整而消失 |
| bwrap 沙箱可见范围 | 只读挂载整个根文件系统 | 只挂载系统目录、JDK 与本运行工作区 | 只读挂载挡不住连接主机套接字（Docker、数据库），也不应读到其他运行或服务端的登录凭据 |
| 执行机器协议 | 机器认领任务（节点）并回传结果 | 机器认领整个运行，运行完整引擎，通过版本号比较提交运行状态 | 复用引擎的所有语义（返工、门禁、恢复、遥测），无需第二套节点执行实现 |
| 单点登录账户绑定 | 企业身份 | issuer + subject 绑定；重名时改用新用户名，邮箱关联需显式开启 | 防止身份提供方里的同名用户接管本地账户（含管理员） |
| 个人 GitHub 连接 | 内置 OAuth App 的设备码授权 | 个人设置里粘贴 Token（按主机） | 不内置第三方 OAuth App 凭据；Token 同样只加密保存 |
| 自动派发的基线门禁 | 派发前执行集成分支基线检查 | 不做基线检查，按优先级派发、跳过有活动运行的需求 | 本项目的需求工作分支直接从基础分支创建，没有集成分支概念 |
| 预览的源 | 与控制台同源并 `allow-same-origin` | 默认不透明源（存储内存模拟）；配置单独的预览源后才 `allow-same-origin` | 控制台令牌存在 localStorage，同源脚本可读 |
| SkillHub 安装范围 | 安装到项目 | 安装为全局技能（管理员） | 本项目的技能目录是全局的，由管理员维护 |
| 运行日志 | Worker 的 stdout / stderr 日志表 | 运行事件即日志；脚本 stdout 与 stderr 合并后逐行写入 `output` 事件（每次最多 2,000 行） | 事件已有游标、保留策略与实时流，不需要第二张日志表 |
| 澄清结果提交 | Agent 调用 `submit_clarification` MCP 工具 | 回复末尾的 JSON 代码块，无效时在同一会话里重试一次 | 不需要为每轮签发 MCP 凭据；校验规则（问题非空、选择题至少两个不同选项、结论必须有 summary）与上游一致 |
| 泳道负责人 | 派发时以泳道负责人作为运行责任人（用其个人凭据） | 负责人只做展示，运行始终以发起人（或定时任务的执行身份）运行 | 避免开发者把别人设为负责人，借用其个人模型与 Git 凭据 |
| 澄清读取外部原单 | 通过 TAPD / 工蜂 MCP 读原单与评论 | 提示词附原单链接，由 Agent 用自己的工具读取 | 本项目的外部同步不提供读原单的 MCP 服务 |

## 尚未移植

对话的群协作与浏览器工具、对话内自动登记 PR、云端执行池的自动扩缩容、浏览器 MCP、macOS seatbelt 沙箱、企业微信智能机器人长连接与 AI 运行报告、业务域报表、预览截图。排期见 [roadmap.md](roadmap.md)。
