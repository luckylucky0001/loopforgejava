# 架构

## 模块

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `loopforge-core` | 领域模型、工作流校验与图算法、调度器、节点执行、提示词编译、执行器适配与流协议解析、本地沙箱、存储端口与内存实现 | Jackson 3、SLF4J |
| `loopforge-server` | Spring Boot 应用：REST/SSE、JDBC 存储与 Flyway 迁移、Token 认证、目录导入、配置绑定 | Spring Boot 4.1、`loopforge-core` |

核心模块是**端口-适配器**结构：引擎只依赖 `RunStore`、`CatalogStore`、`SandboxProvider`、`AgentExecutor` 四个接口。服务模块提供 JDBC 实现；测试使用内存实现。两个 `RunStore` 实现跑同一套契约测试（`RunStoreContract`），保证行为一致。

```
            ┌──────────── loopforge-server ────────────┐
 HTTP/SSE → │ Controllers → RunEngine ← RunListener(SSE) │
            │                  │                         │
            │   JdbcRunStore / JdbcCatalogStore (Flyway)  │
            └──────────────────┼─────────────────────────┘
                               │ ports
            ┌──────────── loopforge-core ──────────────┐
            │ RunEngine ─ RunScheduler ─ NodeRunner      │
            │   │             │            ├ PromptCompiler / GateResults / ArtifactSupport
            │   │             │            ├ AgentExecutor (claude-code | codex | codebuddy | mock)
            │   │             │            └ Sandbox (LocalSandbox → ProcessRunner)
            └───┴─────────────┴────────────────────────┘
```

## 状态模型

- **定义**（`WorkflowDefinition`、`AgentDefinition`、`ExecutorDefinition`）是不可变 record，JSON 形状与上游一致，上游导出的 SOP 可直接导入。
- **运行**（`Run` + `NodeRun`）是可变聚合，只在 `RunStore.update(runId, mutator)` 中修改：存储加载最新状态 → 执行 mutator → 以 `revision` 做乐观并发写入；状态、生命周期事件和产物在**同一事务**中提交。mutator 可能因冲突被重复执行，因此必须是纯函数（只改 `RunChange`）。
- **事件**（`RunEvent`）只追加，带存储分配的递增序号，供审计、SSE 续传（`Last-Event-ID`）和遥测使用。
- **产物**（`Artifact`）按节点尝试版本快照（SHA-256、≤1 MiB），旧版本保留用于审计，下游使用最新版本。

运行开始时把 SOP 快照冻结进 `Run`，之后编辑目录不影响执行中的运行。

## 调度与并发

- 每个运行一个 `RunScheduler`（虚拟线程）：依赖全部成功的 `pending` 节点立刻启动，不等同批的慢节点；每个节点一个虚拟线程。
- 没有在途节点也没有就绪节点时，`settleIdle` 在新鲜状态上原子地决定 `succeeded / waiting / failed`。
- **写栅栏**：节点结果只有在"同一调度代际（generation）+ 节点仍在 running + 同一 attempt"时才会提交，迟到的结果（被打回重置、被取代的调度器）会被丢弃（上游 `shouldCommitNodeOutcome`）。
- **唤醒协议**：控制操作先提交状态，再唤醒或启动调度器；调度器退出前在同一把锁下检查是否有新的唤醒，避免"状态已变但无人调度"的竞态。
- **协作式取消**：引擎从不中断工作线程（虚拟线程上的中断会关闭 JDBC socket 和 H2 文件通道）。取消通过 `Cancellation` 令牌：钩子杀掉进程组，阻塞等待感知标志。
- **进程组**：与上游一致（`Setpgid` + `kill(-pgid)`），每条命令经 `setsid` 运行在独立进程组，命令结束、超时或取消时整组终止，后台子进程不会比节点活得更久；没有 `setsid` 的系统（如 macOS）退化为终止可见进程树。
- **不钉住载体线程**：JDK 21 的虚拟线程在 `synchronized`/`Object.wait` 上会钉住载体线程，因此调度锁、取消等待和内存存储都使用 `ReentrantLock`/`Condition`。
- **控制与标志**：存在待处理的取消时，审批、打回、重试、跳过、继续等操作一律拒绝（409），不会悄悄清除取消；自动打回遇到取消直接放弃，遇到暂停则只重置节点、保持暂停。任何人工决策在结束暂停时会一并释放其他被暂停的分支；子 SOP 节点继续跟随原子运行，不会重复启动。
- **调度器自愈**：调度器因存储异常崩溃时按指数退避重启（最多 10 次连续失败）；新调度器接管时把遗留的 running 节点停回 pending 并续接同一次尝试。
- **停机与恢复**：优雅停机时在途节点回到 `pending`（Agent 节点记录 `resumeSessionId`，保留 attempt）；启动时 `recoverInterruptedRuns()` 重新接管，Agent 用原 CLI 会话"从中断处继续"。
- **部署约束**：当前版本要求**每个数据库只有一个活跃实例**。启动恢复会接管所有 running 的运行，尚未实现跨实例租约与心跳（见路线图迭代 4）。
- **共享工作区**：子 SOP 在根运行的工作区内执行；父运行取消或成功后，只有在所有子运行都结束（进程已排空）后才删除工作区。

## 节点执行

| 类型 | 行为 |
| --- | --- |
| `script` | 脚本写入 `.loopforge/scripts/`，以 `sh -l` 或 `python3` 在工作区执行；非零退出即失败；可配置超时 |
| `agent` | 编译任务书 → 调用执行器 → 读取终态文件 → 校验产物契约 → 运行命令门禁；按自修正策略循环 |
| `approval` | 进入 `waiting_approval`，等待人工批准或打回 |
| `sop` | 以子运行执行另一个 SOP，与父运行共享工作区；检测嵌套循环；成功后把子运行产物复制为本节点产物 |

失败路由（上游 `nodeFailedAt`）：在尝试预算内重试（配置错误、门禁失败、轮次耗尽不重试）→ 否则失败；允许打回时自动 `reject` 到 `rejectTo`；Agent 的 `rework` 终态在返工预算耗尽时转人工决策而不是直接失败。

## 代码仓库

运行启动时把选择的仓库解析为冻结的 `RepositoryContext`（目录名、基线、工作分支、分支模式），之后编辑或删除仓库不会影响进行中的交付。第一个需要仓库的节点在工作区中克隆（单分支），按模式检出工作分支：远端已存在则跟踪续用（`auto`/`continue`），否则从基线新建（`auto`/`new`）；`read_only` 只检出基线。同一工作区的检出以条带锁串行，子 SOP 共享父运行的检出。

`executionScope=repositories` 的脚本和命令门禁在每个仓库目录各执行一次并注入 `LOOPFORGE_REPOSITORY_*`、`LOOPFORGE_WORK_BRANCH` 等变量。`mode=mr` 门禁对每个推送了新提交的可写仓库调用 `DeliveryProvider`，要求存在从工作分支到基线、覆盖最新推送提交的 PR；平台不可查询时转人工确认。找到的 PR 记录在运行的 `deliveries` 中，成功的运行由定时任务刷新合并状态。

## 多实例

每个 RUNNING 运行最多有一个调度器：`ensureScheduler` 在一次存储更新里检查租约（`ownerInstanceId`、`leaseUntil`）、递增 generation 并写入自己的租约，乐观并发保证两个实例不会同时声明成功。`LeaseKeeper` 每 TTL/3 心跳实例注册表并续约本实例的所有运行；续约时发现 owner 或 generation 已变（被接管），本地调度器放弃运行并终止节点进程——generation 栅栏本来就拒绝它的迟到写入，这一步避免两个实例在同一工作区里各跑一个 CLI。

运行结算（等待人工、暂停、终态）时释放租约；之后任何实例上的人工操作都能重新声明。优雅停机挂起节点并立即释放租约。接管循环（默认 10 秒）查找没有存活 owner 的 RUNNING/QUEUED 运行：租约过期，或注册表显示 owner 与本机同 host 但其 pid（按启动时间校验）已不存在。运行记录 `workspaceHost`：主机存活时只由它继续；主机下线且工作区里有仓库检出时运行失败（未推送的改动随主机丢失，与上游一致），否则在本机重建工作区。

遥测（CLI 输出、工具调用等）按运行缓冲，每 200 ms 或满 200 条写一次；任何状态变更和事件读取之前先刷新该运行的缓冲，事件序号因此与发生顺序一致。

## 角色物化

每次 CLI 调用前，`AgentMaterializer` 把角色编译到工作区：

| 位置 | 内容 | 生命周期 |
| --- | --- | --- |
| `.loopforge/agents/<role>/agent-system.md` | 名称、目录规则（按优先级）、Skills 列表、MCP 用法 | 角色级，内容不变时不重写 |
| `.loopforge/agents/<role>/skills/<id>/` | SKILL.md（补齐 front matter）与附属文件；`.claude-plugin/plugin.json` | 角色级，解绑后删除 |
| `.loopforge/agents/<role>/hooks/` | Hook 脚本与 sh 包装器（转交 CLI 的 Hook 载荷、按仓库循环、写观测行） | 角色级 |
| `.loopforge/runtime/<invocation>/` | `mcp.json`（含解析后的 MCP 密钥）、Hook `settings.json`、`hooks.ndjson`、Codex 启动脚本 | 单次调用，结束即删除 |
| `.loopforge/bin/loopforge-gate-mcp.jar` | 门禁 MCP 服务 | 工作区共享 |

系统指令以 `<system_instructions>` 前置在任务书里；Claude Code / CodeBuddy 用 `--mcp-config … --strict-mcp-config --settings … --plugin-dir …`，Claude 还限定 `--setting-sources user`（私有 HOME，因此不加载仓库里的 `.claude/settings.json` Hook）；Codex 用 `-c` 覆盖项；其余 CLI 的 MCP 配置写进各自私有 HOME。控制目录写入拒绝跟随符号链接（Agent 可能在工作区里预置链接），文件以临时文件 + 原子重命名替换。

门禁 MCP 服务是 JSON-RPC over stdio 的最小实现（`initialize`、`tools/list`、`tools/call`、`ping`），校验规则与上游一致，以 `CREATE_NEW` 写结果文件保证只提交一次。它只依赖 JDK，引擎把它的两个类打包成 jar 写入工作区，由同一 JDK 在独立进程中启动；Docker 沙箱把 JDK 只读挂载到同一路径。

## Docker 沙箱

每个运行（含子 SOP 共享的工作区）一个长驻容器：`docker run -d --init --security-opt no-new-privileges -v <ws>:<ws> --user <uid:gid> … sleep infinity`。命令通过 `docker exec` 执行：变量值放在 docker 客户端自己的环境里并以 `-e NAME` 传入（命令行只有变量名）；每次 exec 带唯一的 `LOOPFORGE_EXEC_ID`，命令结束后由容器内包装器结束同标记的后台进程（否则它们会让 `docker exec` 一直不返回），取消和超时时再从外部按标记终止。引擎崩溃后恢复时删除旧容器、在同一工作区上重建。

## 账户与授权

请求先由 `ApiTokenFilter` 认证：`lfu_` 前缀的账户 Token（会话或个人）在 `user_tokens` 中按 SHA-256 查找（账户未停用、未过期），配置的 API Token 认证为服务身份（管理员）。授权集中在 `AccessPolicy`：管理员与服务身份可做一切；项目资源需要足够的成员角色（viewer 读、developer 启动与操作运行、maintainer 管理项目 SOP/仓库/定时任务/通知、owner 管理成员）；不属于项目的运行只对启动者可见；全局目录（角色、规则、Skills、MCP）由管理员维护。运行表冗余 `project_id`、`triggered_by` 列，列表查询在数据库中按可见性过滤。

项目边界：运行只能使用全局或本项目的 SOP 与代码仓库（其他项目的定义对它不存在）；非管理员只能看到全局与所在项目的 SOP、仓库；没有项目的运行只属于管理员。本地沙箱以服务进程的系统用户运行脚本和 Agent，能读到服务的 Token、数据库与配置，因此默认（`loopforge.security.members-require-isolation=true`）非管理员只能编写和启动隔离沙箱（`isolated-profiles`，默认 `docker`）中的 SOP；只有成员都完全可信的单团队部署才应关闭。Webhook 默认不能指向回环、私有、链路本地（云元数据）等内网地址（`loopforge.notifications.allow-private-targets`），保存时和每次投递前都会检查。

需求、定时任务和通知都监听已提交的运行变化（`RunListener`）：需求跟随最近一次运行与其 PR；通知只在本实例观察到的状态转换上发送一次；定时任务由所有实例轮询，`next_run_at` 上的比较并交换保证每个时间槽只触发一次。

## 安全

- API：无状态 Bearer Token（仅保存 SHA-256 摘要、常量时间比较），未配置时生成随机 Token 写入仅属主可读的文件。CSRF 关闭（无 Cookie/会话）。SSE 端点额外接受 `access_token` 查询参数（浏览器 EventSource 无法设置请求头），其他端点不接受。
- 进程：子进程**不继承**服务端环境，只透传白名单变量（`PATH`、`LANG` 等）；执行器凭据仅注入对应执行器进程；默认私有 HOME。提示词通过 stdin 以 UTF-8 传递（不出现在 `ps`，不受 argv 编码和长度限制）。
- 文件：产物路径必须是工作区内相对路径；读取前拒绝符号链接逃逸；上游产物以只读文件交付。
- 产物下载只以 `text/plain` 或 `application/json` 返回，并带 `nosniff` 与 `CSP: sandbox`，避免存储型 XSS。
- 输入边界：ID 限定 `[A-Za-z0-9][A-Za-z0-9._:-]{0,199}`（会进入 URL、文件名和 `VARCHAR(200)` 列），名称 ≤ 500，单个工作流 ≤ 200 个节点；请求体默认 ≤ 8 MB（`loopforge.security.max-request-size`），超出直接 413。
- 错误响应不包含 SQL、驱动或内部 Java 类型信息；存储故障统一为 503，数据冲突为 409。
- SSE 每个运行最多 32 个订阅、全局 1000 个；停机时先关闭所有流，再进入 Web 服务器的优雅停机，保证引擎有时间挂起运行。
- 控制台是静态页面，响应带严格 CSP（禁止内联脚本与样式、只连同源）；所有数据以 `textContent` 渲染，运行输出中的标记不会被解释。
- 只有 `/actuator/health/**` 公开；`/actuator/info`、`/actuator/prometheus` 需要 Token。

- 工作区：Agent 可以把工作区里的任何目录换成指向宿主机的符号链接（容器里也一样，工作区以相同路径挂载）。引擎侧的所有控制文件操作（角色物化、提示词、脚本、输入产物、门禁结果读取与清理、凭据助手、进程记录）都经过 `ManagedFiles`：拒绝符号链接目录、以临时文件 + 原子重命名替换目标、以 `NOFOLLOW_LINKS` 读取、删除时只删除链接本身。
- Git：凭据只来自服务端配置。HTTPS token 放在运行进程的环境变量里，由控制目录中的 credential helper 按主机读取（`GIT_CONFIG_COUNT` 注入 `credential.helper`，先清空用户/系统级 helper），因此不会出现在克隆 URL、`.git/config`、运行数据或日志中；SSH 私钥写入控制目录且仅属主可读，`known_hosts` 采用 `accept-new`。`GIT_TERMINAL_PROMPT=0` 防止 CLI 卡在交互式询问。克隆 URL 内嵌凭据、`file:`/本地路径和 `http://` 默认拒绝。

本地沙箱是**环境**边界而非文件系统边界，适用于可信团队；不可信负载需等待 Docker 沙箱（见路线图）。

## 持久化

`V1__baseline.sql` 同时兼容 H2 与 PostgreSQL。定义和运行以 JSON 文档存储（与上游 `payload` 列一致），常用过滤字段（状态、SOP、父运行、时间）冗余为带索引的列。事件与产物分表存储，支持分页与按运行级联删除。同一进程内对单个运行的写入由分段锁串行化，保证单运行事件序号按提交顺序递增；跨实例由乐观 revision 保护。
