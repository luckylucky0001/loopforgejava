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

## 安全

- API：无状态 Bearer Token（仅保存 SHA-256 摘要、常量时间比较），未配置时生成随机 Token 写入仅属主可读的文件。CSRF 关闭（无 Cookie/会话）。SSE 端点额外接受 `access_token` 查询参数（浏览器 EventSource 无法设置请求头），其他端点不接受。
- 进程：子进程**不继承**服务端环境，只透传白名单变量（`PATH`、`LANG` 等）；执行器凭据仅注入对应执行器进程；默认私有 HOME。提示词通过 stdin 以 UTF-8 传递（不出现在 `ps`，不受 argv 编码和长度限制）。
- 文件：产物路径必须是工作区内相对路径；读取前拒绝符号链接逃逸；上游产物以只读文件交付。
- 产物下载只以 `text/plain` 或 `application/json` 返回，并带 `nosniff` 与 `CSP: sandbox`，避免存储型 XSS。
- 输入边界：ID 限定 `[A-Za-z0-9][A-Za-z0-9._:-]{0,199}`（会进入 URL、文件名和 `VARCHAR(200)` 列），名称 ≤ 500，单个工作流 ≤ 200 个节点；请求体默认 ≤ 8 MB（`loopforge.security.max-request-size`），超出直接 413。
- 错误响应不包含 SQL、驱动或内部 Java 类型信息；存储故障统一为 503，数据冲突为 409。
- SSE 每个运行最多 32 个订阅、全局 1000 个；停机时先关闭所有流，再进入 Web 服务器的优雅停机，保证引擎有时间挂起运行。
- 只有 `/actuator/health/**` 公开；`/actuator/info`、`/actuator/prometheus` 需要 Token。

本地沙箱是**环境**边界而非文件系统边界，适用于可信团队；不可信负载需等待 Docker 沙箱（见路线图）。

## 持久化

`V1__baseline.sql` 同时兼容 H2 与 PostgreSQL。定义和运行以 JSON 文档存储（与上游 `payload` 列一致），常用过滤字段（状态、SOP、父运行、时间）冗余为带索引的列。事件与产物分表存储，支持分页与按运行级联删除。同一进程内对单个运行的写入由分段锁串行化，保证单运行事件序号按提交顺序递增；跨实例由乐观 revision 保护。
