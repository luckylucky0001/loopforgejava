# 路线图

每个迭代都保持：`./mvnw verify` 通过（测试、格式、覆盖率门槛）、文档同步更新、移植对照表（[porting.md](porting.md)）记录新增的上游对应关系。

## 迭代 1 — 引擎核心（已完成）

- [x] 领域模型与上游 JSON 兼容；工作流校验（返回全部问题）与图算法
- [x] 并行调度、四类节点、门禁终态、命令门禁、自修正循环、返工与尝试预算
- [x] 人工控制：审批 / 打回 / 重试 / 跳过 / 暂停 / 继续 / 取消 / 拒绝重试
- [x] Claude Code、Codex、CodeBuddy、Mock 执行器；本地沙箱（环境隔离、超时、进程树终止）
- [x] JDBC 存储（H2 / PostgreSQL）、Flyway、REST + SSE、Token 认证、OpenAPI、Actuator
- [x] 停机挂起与重启恢复；演示目录；CI、容器镜像、Compose

## 迭代 2 — 代码仓库与交付（已完成）

- [x] 仓库目录（`/api/v1/repositories`），运行启动时冻结仓库快照
- [x] Git 仓库检出到运行工作区：多仓库（目录名去重）、工作分支命名 `feature/fix-<时间>-<运行>`、`auto/continue/new/read_only` 分支模式、空远端自动初始化、子 SOP 共享检出
- [x] HTTPS 凭据通过 credential helper 从进程环境读取（不进 URL、不落盘）；SSH 私钥仅属主可读；提交身份
- [x] `{{repository}}`、`{{existingMR}}` 占位符与工作区布局说明
- [x] `executionScope=repositories` 的脚本与门禁（每个仓库目录各执行一次）
- [x] `mode=mr` 门禁：推送了提交的工作分支必须有覆盖最新提交的 PR；平台不可查询时转人工
- [x] GitHub（含 Enterprise）PR 查询、运行交付记录、定时合并检测与手动刷新

## 迭代 3 — 隔离与执行面（已完成）

- [x] 门禁 MCP 服务：`submit_gate_result` 工具（JSON-RPC over stdio，只依赖 JDK，打包成小 jar 写入工作区，独立 JVM 运行），与文件通道写同一结果文件；严格的参数校验与"只能提交一次"
- [x] 规则 / Skills / MCP 服务器目录（`/api/v1/rules`、`/skills`、`/mcp-servers`），角色按 id 绑定；`${secret:NAME}` 占位符由服务端配置解析
- [x] 角色物化：系统指令（规则按优先级、Skills 列表、MCP 用法）、SKILL.md 树与 Claude 插件清单、Hook 脚本与观测包装器、严格 MCP 白名单；Claude Code / CodeBuddy（`--mcp-config --strict-mcp-config --settings --plugin-dir`）、Codex（`-c mcp_servers.* / hooks.* / skills.config`，密钥不上命令行）
- [x] Hooks：`SessionStart/UserPromptSubmit/PreToolUse/PostToolUse/Stop`，`run` 或 `repositories` 范围，执行观测转为运行事件
- [x] 其余 7 种 Agent CLI：OpenCode、Cursor、Antigravity、Kimi Code、Pi、DeepSeek Harness、ZCode（原生流翻译为 Claude stream-json，MCP 配置写入私有 HOME）
- [x] Docker 沙箱：每个运行一个容器、工作区同路径挂载、资源限制、只读根文件系统、`no-new-privileges`、变量按名传递不上命令行、标记进程终止

## 迭代 4 — 多实例与运维（已完成）

- [x] 运行租约：`ownerInstanceId + leaseUntil`，在存储里原子声明，心跳每 TTL/3 续约；结算（等待/暂停/终态）与优雅停机立即释放
- [x] 实例注册表（`engine_instances`）与接管循环：租约过期或同机进程已退出即接管；失去租约的实例停止本地节点
- [x] 工作区主机绑定：工作区所在主机存活时不迁移；主机下线且含仓库检出的运行失败并说明原因，否则在新主机重建；`shared-workspaces` 支持共享存储
- [x] 遥测批量写入（每运行缓冲，状态变更与读取前先刷新，保持事件顺序）
- [x] 保留策略：按天清理执行器遥测（保留审计事件），按期删除终态运行及其工作区
- [x] Micrometer 指标：运行/节点结果、节点耗时直方图、返工、门禁决策与检查、token 与成本、活动运行数

## 迭代 5 — 协作（已完成）

- [x] 账户：首个账户为管理员；密码（bcrypt）、登录锁定、会话与个人访问 Token（只显示一次、SHA-256 存储）；配置的 API Token 作为服务身份
- [x] 项目与成员（owner / maintainer / developer / viewer），运行、SOP、仓库、需求、定时任务、通知按项目授权；运行列表与 SSE 按可见性过滤
- [x] 需求：项目内编号；启动即以需求为任务书、`feature/requirement-N-<时间>` 为工作分支运行 SOP；状态随运行与 PR 合并自动推进
- [x] 定时任务：五/六段 cron + 时区、运行中跳过、立即运行；多实例下每个时间槽只触发一次
- [x] 通知：项目或全局 Webhook，事件过滤、HMAC-SHA256 签名、重试与最近投递状态
- [x] Web 控制台：登录/初始化、运行列表与运行视图（DAG、实时事件、节点操作、产物、交付）、待处理收件箱、SOP 设计器（JSON + DAG 预览 + 校验）、角色与资产、仓库、项目（需求/定时任务/通知/成员）、个人设置与账户管理；无第三方依赖、严格 CSP

## 后续

- GitLab / 工蜂 MR 交付；Chat / 会话工作区与 Main Bot；平台 MCP 与知识库；执行机器（runner）与云端池
- 可视化拖拽式 SOP 设计器、分析看板与成本定价表
