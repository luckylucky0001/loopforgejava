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

## 迭代 4 — 多实例与运维

- 运行租约（owner + lease_until + 心跳）与跨实例接管
- 遥测批量写入、事件保留策略、运行归档
- Micrometer 指标（节点耗时、门禁通过率、返工率、token 成本）与 OpenTelemetry 追踪

## 迭代 5 — 协作

- 账户与项目、成员与权限、需求跟踪
- Web 控制台（SOP 设计器、运行视图、待审批收件箱）
- 定时任务与通知
