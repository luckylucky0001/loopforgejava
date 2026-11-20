# LoopForge4J

**LoopForge4J** 是 [Tencent/LoopForge](https://github.com/Tencent/LoopForge) 的社区 Java 移植版：一个面向编码 Agent 的 **SOP（标准作业流程）引擎**。它把 Claude Code、Codex、CodeBuddy 等编码 CLI 编排进由 Agent、脚本、人工门禁和子 SOP 组成的有向无环图里，让每一次交付都按同样的阶段、同样的检查、同样的签核来完成。

> 本项目为独立的社区移植，**与腾讯无隶属、赞助或背书关系**。上游以 MIT 许可发布，本仓库同样采用 MIT，并完整保留上游版权与许可声明，详见 [LICENSE](LICENSE) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

当前版本 0.1.0-SNAPSHOT：已完成引擎核心与 REST/SSE 服务（迭代 1）、Git 仓库交付（迭代 2）、MCP / Skills / Hooks / 全部 10 种 CLI / Docker 沙箱（迭代 3）、多实例租约与运维（迭代 4）、账户/项目/需求/定时任务/通知/Web 控制台（迭代 5）；尚未移植的部分见 [移植对照](docs/porting.md)。

## 已实现

| 能力 | 说明 |
| --- | --- |
| SOP 引擎 | 依赖满足即启动（无"波次"等待）的并行调度；Agent / Script / Approval / Sub-SOP 四类节点；运行时冻结 SOP 快照 |
| 门禁与路由 | Agent 必须提交 `pass / rework / fail / human` 终态；命令门禁（`onFailure=loop/reject/fail/human`）；Agent 自修正循环；`rejectTo` 受控返工与返工预算；尝试次数预算 |
| 人工控制 | 审批、打回、从任意节点重试/重放、跳过失败脚本（pass）、暂停/继续、取消、拒绝重试；补充的人工上下文会进入后续提示词并留档 |
| 产物契约 | 节点声明的产物按尝试版本快照（SHA-256），下游以只读文件接收；缺失即契约失败 |
| 执行器 | Claude Code、Codex、CodeBuddy、OpenCode、Cursor、Antigravity、Kimi Code、Pi、DeepSeek Harness、ZCode（原生流协议归一化为统一遥测）；确定性 Mock 执行器（无需密钥的演示与测试） |
| MCP / Skills / Hooks | 门禁 MCP 工具 `submit_gate_result`；规则、Skills（SKILL.md 树）、MCP 服务器目录，按角色白名单物化为各 CLI 的原生配置；Hooks 编译为原生 Hook 配置并上报执行事件；MCP 密钥用 `${secret:NAME}` 引用 |
| Git 交付 | 仓库目录；运行检出到独立工作分支（多仓库、继续/新建/只读模式）；凭据经 credential helper 注入；`executionScope=repositories` 的脚本与门禁；`mode=mr` 门禁核验 PR 覆盖最新提交；GitHub PR 记录与合并检测 |
| 沙箱 | 本地沙箱：每个运行独立工作区、**不继承服务端环境变量**、私有 HOME、超时与进程树终止；bubblewrap 轻量沙箱（Linux）：主机只读、只能写本运行工作区、私有 /tmp、独立用户/PID 命名空间、看不到其他运行与服务端 HOME；Docker 沙箱：每个运行一个容器、资源限制、只读根文件系统、变量不上命令行 |
| 可靠性 | 乐观并发（revision）+ 原子提交（状态/事件/产物同事务）；代际（generation）写栅栏；进程组终止；调度器崩溃自愈；优雅停机挂起运行、重启后自动恢复并续接 CLI 会话；多实例部署：运行租约、心跳与过期接管、工作区主机绑定 |
| 运维 | 遥测批量写入；保留策略（执行器遥测、终态运行）；Prometheus 指标：运行与节点结果、节点耗时、返工、门禁决策、token 与成本 |
| 协作 | 账户与 Token、OIDC 单点登录、项目与成员角色、需求（自动工作分支与状态推进）、定时任务、Webhook 与企业微信 / 飞书 / 钉钉 / Slack 群机器人通知；Web 控制台（运行视图与实时事件、待处理收件箱、SOP 设计器、项目管理、分析、知识库） |
| 平台 MCP | `/mcp` 把公开 API 作为 MCP 工具（按调用者本人的权限执行）；角色开启 `platformMcp` 后，Agent 拿到只读、限本项目、随运行结束失效的运行令牌；JDK-only stdio 桥 |
| 团队知识库 | 接入上游知识库 MCP，按项目授权来源检索与阅读；角色开启 `knowledgeBase` 后获得只读检索工具与使用规范；Agent 写下的 `knowledge.md` 经人工审核后写回 |
| 分析 | 运行概览（成功率、一次通过率、耗时分位、Token 与费用、PR）、趋势、按 SOP / 项目 / 执行器 / 模型拆分、失败聚类、运行时段热力；SOP 运营指标（节点级） |
| 服务 | Spring Boot 4.1 / Java 21 虚拟线程；REST + SSE（支持 `Last-Event-ID` 续传）；RFC 9457 错误；Bearer Token；Flyway；H2（开发）/ PostgreSQL（生产）；OpenAPI；Actuator 健康探针与 Prometheus 指标 |

## 快速开始

需要 JDK 21+。

```bash
./mvnw -B verify                      # 构建、测试、格式与覆盖率检查
java -jar loopforge-server/target/loopforge-server-0.1.0-SNAPSHOT.jar
```

服务默认监听 `8090`，数据写在 `./data`（H2 数据库、运行工作区、API Token）。浏览器打开 <http://localhost:8090/>：首次访问创建管理员账户，之后用账户登录控制台。

脚本与自动化可以使用个人 Token（控制台「设置」中创建），或服务 Token：未配置时会生成一个随机 Token 写入 `./data/api-token`（仅属主可读，不会打印到日志），它拥有管理员权限：

```bash
export TOKEN=$(cat data/api-token)
```

启动时会导入内置演示目录（只插入不存在的定义，不覆盖修改）。无需任何模型密钥即可跑通「脚本 → 人工审批 → 脚本」：

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"workflowId":"welcome-workflow","input":"验收一下"}' localhost:8090/api/v1/runs
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" -X POST \
  localhost:8090/api/v1/runs/<runId>/nodes/approve/approve
```

`demo-delivery` 用 Mock 执行器演示「需求定型 → 开发实现 → 独立评审（第一次要求返工）→ 人工验收」的完整打回闭环。`claude-bugfix` 是使用真实 Claude Code 的缺陷修复模板。

API 文档：<http://localhost:8090/swagger-ui.html>（`prod` profile 下关闭）。

### 使用真实编码 CLI

1. 在服务所在机器安装对应 CLI（`claude`、`codex` 或 `codebuddy`），`GET /api/v1/executors` 会显示是否可用。
2. 通过配置注入凭据——凭据只注入到对应执行器的进程环境，不会写入运行数据或日志：

```yaml
loopforge:
  credentials:
    claude-code:
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY}
    codex:
      OPENAI_API_KEY: ${OPENAI_API_KEY}
  executors:            # 可选：覆盖二进制路径或默认模型
    claude:
      model: sonnet
```

### 让运行改代码并提交 PR

```yaml
loopforge:
  git:
    https:                       # 列表形式，可用 LOOPFORGE_GIT_HTTPS_0_HOST / _TOKEN 环境变量配置
      - host: github.com
        token: ${GITHUB_TOKEN}
    # ssh-private-key-file: /run/secrets/deploy_key
  github:
    api-url: https://api.github.com   # GitHub Enterprise: https://<host>/api/v3
  gitlab:                        # GitLab / 工蜂：仓库设置 "provider": "gitlab"，凭据同样放在 git.https
    - host: gitlab.example.com   # api-url 默认 https://<host>/api/v4
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X PUT \
  -d '{"name":"App","cloneUrl":"https://github.com/acme/app.git"}' localhost:8090/api/v1/repositories/app
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"workflowId":"claude-bugfix","input":"修复登录超时","repositories":[{"repositoryId":"app"}]}' \
  localhost:8090/api/v1/runs
```

运行会把仓库克隆到 `./app`，检出 `feature/fix-<时间>-<运行>` 工作分支（`mode` 可选 `continue` 续用已有分支、`new` 必须新建、`read_only` 只读），Agent 提示词里会出现仓库、基线与交付要求。凭据只存在于运行进程的环境变量里，不会出现在克隆 URL、运行数据或日志中；克隆 URL 内嵌凭据会被拒绝。

### MCP、Skills 与 Docker 沙箱

```yaml
loopforge:
  mcp:
    secrets:                      # MCP 定义里写 ${secret:TRACKER}，值只在这里
      TRACKER: ${TRACKER_TOKEN}
  sandbox:
    docker:
      enabled: true               # 工作流设置 "sandboxProfileId": "docker" 即可使用
      image: ghcr.io/acme/agent-image:1   # 需包含 sh、sleep 与 SOP 用到的 CLI；glibc 镜像
      memory: 4g
      cpus: "2"
      read-only-root: true
```

角色通过 `rules`、`skills`、`mcpServers`、`hooks` 绑定资产；每次调用都会把它们编译成对应 CLI 的原生配置（只包含白名单内容，不读取仓库或用户级 CLI 配置）。Agent 用 MCP 工具 `submit_gate_result` 提交节点终态。

### 平台 MCP 与团队知识库

任何支持 Streamable HTTP 的 MCP 客户端都可以直接连接平台 MCP（用个人 Token 或服务 Token）：

```bash
claude mcp add --transport http loopforge http://localhost:8080/mcp --header "Authorization: Bearer lfu_..."
```

只能启动本地进程的客户端用 stdio 桥（只依赖 JDK）：

```bash
LOOPFORGE_API_URL=http://localhost:8080 LOOPFORGE_API_TOKEN=lfu_... java -cp loopforge-core.jar io.github.loopforge4j.core.mcp.PlatformMcpBridge
```

角色定义里设置 `"platformMcp": true` / `"knowledgeBase": true`，每次调用都会注入 `loopforge-platform` / `loopforge-knowledge` 两个 HTTP MCP 服务器，凭据是一次性的运行令牌：只读、只能访问运行所在项目、运行结束即失效。Docker 等隔离沙箱需要告诉服务端沙箱内能访问到的地址：

```yaml
loopforge:
  mcp:
    sandbox-url: http://host.docker.internal:8080   # 不设置时只有本地沙箱的 Agent 能注入（回环地址）
    agent-token-ttl: 12h
  knowledge:
    token: ${KB_TOKEN}      # 可选：上游知识库 Token 放在配置里而不是数据库
```

知识库的地址、启用开关、写回开关和按项目授权的来源在控制台「知识库 → 接入配置」里设置（管理员）。

### 轻量沙箱、单点登录与群机器人

```yaml
loopforge:
  public-url: https://loopforge.example.com     # 通知里的运行链接、OIDC 回调地址
  sandbox:
    bwrap:
      enabled: true                 # 需要 bubblewrap 且允许非特权用户命名空间；SOP 设置 "sandboxProfileId": "bwrap"
      hidden-paths: [/srv/loopforge/data]   # 额外隐藏的主机目录；工作区根目录与服务端 HOME 默认隐藏
      share-network: true           # false = 进程只有回环网络
  security:
    isolated-profiles: [docker, bwrap]      # 允许成员使用的隔离沙箱
    oidc:
      enabled: true
      display-name: 企业 SSO
      issuer: https://sso.example.com/realms/dev
      client-id: loopforge
      client-secret: ${OIDC_CLIENT_SECRET}
      allowed-email-domains: [example.com]  # 可选：只允许这些已验证邮箱域
```

单点登录按身份提供方的 issuer + subject 绑定账户：首次登录自动创建成员账户（`auto-provision`），提供方给出的用户名与本地已有账户重名时会改用带后缀的新用户名，永远不会登录进别人的账户（只有显式打开 `link-by-email` 才会按已验证邮箱关联已有账户）。

通知渠道的 `kind` 可选 `webhook`（通用 JSON + HMAC 签名）、`wecom`（企业微信群机器人 Markdown）、`feishu`（飞书 / Lark 自定义机器人，可加签）、`dingtalk`（钉钉自定义机器人 Markdown，可加签）、`slack`（Incoming Webhook）；平台返回的错误码（如 `errcode`）计为投递失败。

### 多实例部署

多个服务实例连接同一个 PostgreSQL 即可：每个运行由持有租约的实例执行，实例每 `lease-ttl/3` 续约并在 `engine_instances` 心跳；实例宕机后其他实例在 `lease-ttl`（默认 60 秒）内接管。工作区默认在本机磁盘：工作区主机在线时运行不会迁移；主机下线时，含仓库检出的运行会失败并提示重跑，其余运行在新主机上重建。若所有实例以相同路径挂载共享存储，设置 `loopforge.engine.shared-workspaces=true` 即可跨主机接管。

```yaml
loopforge:
  engine:
    lease-ttl: 60s
    claim-interval: 10s
    max-active-runs: 20          # 超出的新运行排队，按先后自动启动；0 = 不限
    max-active-runs-per-project: 5
  retention:
    telemetry: 30d      # 执行器输出/工具/进程/Hook 事件；生命周期与门禁审计事件保留
    runs: 180d          # 删除终态运行（含事件、产物与工作区）；默认 0 = 永久保留
```

### 生产部署（PostgreSQL）

```bash
LOOPFORGE_API_TOKEN=$(openssl rand -hex 32) POSTGRES_PASSWORD=$(openssl rand -hex 16) docker compose up -d
```

生产栈启动时目录为空（需要演示 SOP 时加 `LOOPFORGE_DEMOCATALOG=true`），只有 `published` 状态的工作流可以启动。Prometheus 指标在 `/actuator/prometheus`（需 Token）。镜像以非 root 用户运行，`LANG=C.UTF-8`，带就绪探针。有非管理员账户时默认要求隔离沙箱：成员只能编写和启动 `sandboxProfileId: docker` 的 SOP（`loopforge.security.members-require-isolation`）。本地沙箱面向**可信团队**：它隔离环境变量但不隔离文件系统，请不要用于不可信的多租户场景（与上游 `local` 沙箱定位一致）。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET/PUT/DELETE` | `/api/v1/workflows/{id}` | SOP 定义（写入前完整校验，返回所有问题） |
| `POST` | `/api/v1/workflows/validate` | 只校验不保存 |
| `GET/PUT/DELETE` | `/api/v1/agents/{id}` | 角色定义 |
| `GET` / `POST` | `/api/v1/catalog/export` / `/import` | 目录导出 / 导入（默认不覆盖） |
| `GET` | `/api/v1/executors` | 执行器及本机是否已安装 |
| `GET/PUT/DELETE` | `/api/v1/repositories/{id}` | 代码仓库 |
| `GET/PUT/DELETE` | `/api/v1/rules/{id}`、`/skills/{id}`、`/mcp-servers/{id}` | 角色可绑定的规则、Skills、MCP 服务器 |
| `GET` / `POST` | `/api/v1/auth/status`、`/auth/setup`、`/auth/login`、`/auth/logout` | 初始化与登录（公开） |
| `GET/POST/DELETE` | `/api/v1/me`、`/me/tokens`、`/me/password`；`/api/v1/users` | 当前账户与个人 Token；账户管理（管理员） |
| `GET/PUT/DELETE` | `/api/v1/projects/{id}`、`/members/{user}` | 项目与成员 |
| `GET/POST/PUT/DELETE` | `/api/v1/projects/{id}/requirements[/{n}[/runs]]` | 需求与启动需求运行 |
| `GET/POST/PUT/DELETE` | `/api/v1/projects/{id}/schedules[/{sid}[/run]]` | 定时任务 |
| `GET/POST/PUT/DELETE` | `/api/v1/notifications[/{id}]` | Webhook 通知 |
| `GET` / `POST` | `/api/v1/runs/{id}/deliveries` / `/deliveries/refresh` | 运行交付的 PR 与合并状态 |
| `POST` / `GET` | `/api/v1/runs` | 启动运行 / 列表（按状态、SOP、父运行过滤） |
| `GET` | `/api/v1/runs/{id}`、`/events`、`/artifacts`、`/artifacts/{aid}` | 运行详情、事件、产物 |
| `GET` | `/api/v1/runs/{id}/stream` | SSE 实时流（浏览器可用 `?access_token=`） |
| `POST` | `/api/v1/runs/{id}/cancel`、`/pause`、`/resume`、`/retry` | 运行控制 |
| `POST` | `/api/v1/runs/{id}/nodes/{node}/approve`、`/reject`、`/pass`、`/decline-retry` | 节点决策 |
| `POST` | `/mcp` | 平台 MCP（无状态 Streamable HTTP）；`GET /api/v1/capabilities` 列出全部操作 |
| `GET` | `/api/v1/analytics`、`/api/v1/workflow-metrics` | 分析报表与 SOP 运营指标（`projectId`、`workflowId`、`from`、`to`、`grain`、`zone`） |
| `GET/PUT/POST` | `/api/v1/knowledge/settings`、`/check`、`/sources`、`/search`、`/document`、`/activity`、`/usage` | 知识库接入与检索 |
| `GET/POST/PUT/DELETE` | `/api/v1/knowledge/candidates`、`/submissions[/{id}[/reject\|/publish]]` | 沉淀候选与审核队列 |
| `POST` | `/api/v1/knowledge/mcp` | Agent 使用的只读知识库 MCP（运行令牌） |

## 项目结构

```
loopforge-core     纯 Java 引擎：领域模型、工作流校验与图、调度器、节点执行、执行器适配、本地沙箱、存储端口
loopforge-server   Spring Boot：REST/SSE、JDBC 存储（Flyway）、Token 安全、目录导入
```

核心模块不依赖 Spring，可嵌入任何 JVM 应用。

| 文档 | 内容 |
| --- | --- |
| [docs/api-walkthrough.md](docs/api-walkthrough.md) | 从定义 SOP 到审批、打回、产物与导入导出的完整 curl 演练 |
| [docs/architecture.md](docs/architecture.md) | 模块、状态模型、调度与并发、安全与持久化设计 |
| [docs/porting.md](docs/porting.md) | 与上游 Go 代码的包对应、有意的差异、尚未移植的部分 |
| [docs/roadmap.md](docs/roadmap.md) | 后续迭代计划 |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 开发约定与质量门槛 |

## 许可

[MIT](LICENSE)。本项目派生自 LoopForge（Copyright (C) 2026 Tencent，MIT），上游版权与许可声明完整收录于 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；未包含上游的 Web UI、第三方 skills、Monaco/Code-OSS、图标与品牌素材。编码 CLI 不随本项目分发，使用时遵守其各自的许可与服务条款。
