# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/)。

## [Unreleased] — 0.1.0

### Added
- SOP 引擎核心（移植自 LoopForge `65f2077`）：并行调度、agent/script/approval/sop 节点、门禁终态与命令门禁、自修正循环、返工与尝试预算、人工控制（审批、打回、重试、跳过、暂停、继续、取消、拒绝重试）。
- 执行器：Claude Code、Codex、CodeBuddy（流协议归一化）、确定性 Mock。
- 本地沙箱：环境变量白名单、私有 HOME、超时、协作式取消与进程树终止。
- Spring Boot 4.1 服务：REST + SSE、RFC 9457 错误、Bearer Token、Flyway（H2 / PostgreSQL）、OpenAPI、Actuator。
- 停机挂起与重启恢复；演示目录（welcome、mock 交付、Claude 缺陷修复模板）。
- CI（含 PostgreSQL 契约测试）、Dependabot、分层容器镜像、Compose。

- 迭代 2 — 代码仓库与交付：仓库目录 API；运行冻结仓库快照并检出到工作区（多仓库、工作分支、`auto/continue/new/read_only` 模式、空远端初始化）；credential helper 注入 HTTPS 凭据、SSH 私钥、提交身份；`executionScope=repositories` 的脚本与门禁；`mode=mr` 门禁；GitHub PR 交付记录、合并检测（定时 + `POST /runs/{id}/deliveries/refresh`）；`{{repository}}`/`{{existingMR}}` 占位符；目录导出/导入包含仓库。

- 迭代 3 — 隔离与执行面：门禁 MCP 服务 `submit_gate_result`（仅依赖 JDK 的 jar，独立 JVM）；规则 / Skills / MCP 服务器目录与 API，`${secret:NAME}` 由 `loopforge.mcp.secrets` 解析；角色物化（系统指令、SKILL.md 与 Claude 插件、Hook 脚本与观测、严格 MCP 白名单）接入 Claude Code、CodeBuddy、Codex；Hooks 观测事件 `hook.started/finished`；OpenCode、Cursor、Antigravity、Kimi Code、Pi、DeepSeek Harness、ZCode 执行器；Docker 沙箱（`loopforge.sandbox.docker.*`，工作流 `sandboxProfileId: docker`）。

- 迭代 4 — 多实例与运维：运行租约（存储内原子声明、心跳续约、结算与停机释放）、实例注册表与接管循环、同机已退出进程立即接管、失去租约即停止本地执行；工作区主机绑定与"主机下线且有仓库检出"时失败；遥测批量写入；保留策略（`loopforge.retention.telemetry/runs`）；Micrometer 指标（`loopforge.runs.*`、`loopforge.nodes.*`、`loopforge.node.duration`、`loopforge.gate.*`、`loopforge.tokens`、`loopforge.cost.usd`）。

- 迭代 5 — 协作：账户（首个为管理员、bcrypt、登录锁定、会话/个人 Token）、项目与四级成员角色、全 API 授权与运行可见性过滤；需求（编号、`feature/requirement-N` 工作分支、状态随运行/PR 推进）；cron 定时任务（多实例单次触发）；Webhook 通知（签名、重试）；Web 控制台（`/`，严格 CSP、无第三方依赖）。

- 迭代 6 — 对齐补充：GitLab / 工蜂 MR 交付（`loopforge.gitlab[]`，仓库 `provider: gitlab`）；运行准入（`loopforge.engine.max-active-runs`、`max-active-runs-per-project`，超出排队）；成本估算（上游价格表，`loopforge.pricing[]` 覆盖）。

### Added (迭代 7)
- 平台 MCP：`/mcp`（无状态 Streamable HTTP）把公开 API 作为工具提供，按调用者本人的权限执行；`/api/v1/capabilities` 操作清单；JDK-only 的 stdio 桥 `PlatformMcpBridge`。
- 角色开关 `platformMcp` / `knowledgeBase`：每次调用签发只在运行期间有效、只读、限定本项目的运行令牌（`lfr1.`）；无法注入时只告警不失败。
- 团队知识库：接入配置（Token 只写不读，也可只放在配置里）、按项目授权来源的检索/阅读、Agent 只读代理 MCP 与使用规范、`knowledge.md` 沉淀候选与审核写回队列、调用记录。
- 分析：`/api/v1/analytics`（概览、连续时间轴趋势、按 SOP/项目/执行器/模型拆分、失败聚类、周×小时热力）与 `/api/v1/workflow-metrics`（节点级）。
- 控制台：分析页、知识库页（接入配置、检索与阅读、沉淀审核、调用记录）；角色模板含新开关。

### Fixed (迭代 2–5 独立审查)
- Agent 在工作区预置符号链接时，引擎清理过期 Skills/Hooks、写提示词与脚本、物化输入产物、读取/清理门禁结果、写凭据助手会跟随链接读写或删除宿主机文件；现在统一经过拒绝符号链接的 `ManagedFiles`。
- 运行可以使用其他项目的 SOP 和代码仓库（借服务端凭据推送别人的仓库）；现在只能使用全局或本项目的定义。
- 项目维护者可编写在本地沙箱运行的脚本读取服务 Token；默认非管理员只能编写和启动隔离沙箱中的 SOP。
- 无项目运行按用户名字符串归属（同名账户可接管服务令牌的运行）；现在只属于管理员，保留 `operator` 等名称。
- 遥测缓冲在刷新与写入并发时会丢事件；两个实例同时接管孤儿运行时，落败者会杀掉胜者的进程并把节点重置导致重复执行；定时任务在 claim 与保存之间崩溃后永远不再触发；Codex 的 MCP 参数中的密钥出现在命令行；需求在运行快速失败时状态不更新、并发创建返回 503。
- 首次初始化并发可能产生两个管理员；登录失败表可被无限增长；Webhook 可探测内网（默认拒绝内网地址）；SOP/仓库读取与目录导出不受项目范围限制。

### Fixed (并发审查)
- 暂停后继续父运行会重复启动子 SOP 并遗留旧子运行。
- 自动打回会清除待处理的取消/暂停；取消待处理时其他人工操作也会清除它。
- 强制取消可能与重新启动的节点竞争，导致运行已取消但进程仍在执行。
- 调度器因存储异常崩溃后运行永远停留在 running；现在按退避重启并续接遗留节点。
- 对一个暂停分支执行跳过/重试/打回后，其他暂停分支无法再继续。
- 脚本的后台子进程在节点结束后仍然存活（改为进程组终止；dash 的 `kill` 不接受 `--`）。
- JDK 21 虚拟线程在 `synchronized`/`Object.wait` 上钉住载体线程。
- 停机排空超时长于关闭等待，且中断标志会在存储写入前被重新设置。
- 父运行取消时，子运行仍在使用共享工作区就被删除。

### Fixed (深入回归)
- 引擎被 `kill -9` 后，节点的进程组继续运行，恢复后与重新执行的节点并行写同一工作区；现在记录进程组并在恢复前终止遗留进程（校验启动时间防止 PID 复用）。
- 正常退出后终止进程组时调用 `Process.destroyForcibly()` 会关闭输出管道，高负载下丢失命令输出。
- CLI 会话 ID 只在调用结束时保存，暂停/停机/崩溃中断的 Agent 无法 `--resume`；现在流中一出现会话 ID 就保存。
- 若干测试在脚本创建标记文件前就中断节点，导致 CI 偶发失败。
- 并行分支同时把同一上游节点的产物写入共享工作区时，后写者会因文件已被设为只读而失败（只在非 root 用户下出现，CI 发现）；现在先写临时文件再原子重命名。

### Fixed (服务端审查)
- 超长 ID/名称越过数据库列宽：超长节点 ID 会让调度器死循环重启并刷出大量日志。现在校验器限制 ID 字符集与长度（≤200）、名称（≤500）和产物字段长度，角色定义同样校验；节点线程反复崩溃时指数退避，5 次后标记失败；存储错误不再把 SQL 原文写进节点错误或响应。
- `limit`/`after` 等参数越界返回 500；现在返回 400。`?status=running` 等小写状态值可用。
- 并发 PUT 同一工作流会 500 或丢失修订号；现在行锁 + 重试。
- 目录导入半途失败会留下部分数据、接受无 ID 的角色；现在先全部校验再在一个事务内写入。
- 演示目录每次启动都会补回被删除的定义，且 Compose 在 prod 下强制开启；现在只在目录为空时导入一次，Compose 默认关闭。
- 打开的 SSE 连接拖住优雅停机，Docker 默认 10 秒后强杀；现在停机前主动关闭流，Compose `stop_grace_period: 60s`，并限制订阅数（每运行 32、总计 1000）。
- Token 文件非原子写入、内容未校验；现在原子发布、校验长度并提示权限过宽。
- 解析错误暴露内部 Java 类型；现在只返回 JSON 路径。
- 草稿工作流可以直接启动；现在只能启动已发布的工作流（子 SOP 同样）。
- 请求体没有大小限制；默认 8 MB，超出返回 413。
- `/actuator/info` 无需认证；补上 Prometheus 指标（`/actuator/prometheus`，需 Token）。

### Added
- CI 冒烟测试：以 Compose 启动镜像 + PostgreSQL（`prod` profile），通过 HTTP 跑完 welcome SOP（`scripts/smoke-test.sh`）。
