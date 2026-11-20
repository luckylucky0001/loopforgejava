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
| 沙箱 | 本地沙箱：每个运行独立工作区、**不继承服务端环境变量**、私有 HOME、超时与进程树终止；bubblewrap 轻量沙箱（Linux）：只挂载系统目录（只读）、JDK 与本运行工作区，私有 /tmp、独立用户/PID 命名空间；看不到 /run、/var（主机上的 Docker、数据库等套接字）、各用户主目录与其他运行；Docker 沙箱：每个运行一个容器、资源限制、只读根文件系统、变量不上命令行 |
| 可靠性 | 乐观并发（revision）+ 原子提交（状态/事件/产物同事务）；代际（generation）写栅栏；进程组终止；调度器崩溃自愈；优雅停机挂起运行、重启后自动恢复并续接 CLI 会话；多实例部署：运行租约、心跳与过期接管、工作区主机绑定 |
| 运维 | 遥测批量写入；保留策略（执行器遥测、终态运行）；Prometheus 指标：运行与节点结果、节点耗时、返工、门禁决策、token 与成本 |
| 协作 | 账户与 Token、OIDC 单点登录、项目与成员角色、需求（自动工作分支与状态推进；从 GitHub / GitLab（工蜂）Issue 与 TAPD 迭代同步、按链接导入 TAPD、推送到外部、交付后回写 PR 链接）、定时任务、Webhook 与企业微信 / 飞书 / 钉钉 / Slack 群机器人通知；Web 控制台（运行视图与实时事件、待处理收件箱、拖拽式 SOP 设计器、项目管理、分析、知识库） |
| 需求 AI 澄清 | 分析师 Agent 只读检出需求绑定的仓库、对照代码只追问阻塞开工的 1–3 个问题（选择题 / 多选 / 填写）；多轮续接同一 CLI 会话，几轮后主动收敛；结论按「本次范围 / 不在本次范围 / 验收标准 / 假设」写入需求描述并回写外部原单；后台运行、可停止、可手动修改结论 |
| 平台助手（Main Bot） | 个人或项目对话：助手是一个 CLI Agent，每个对话有一个持久的沙箱工作区并续接 CLI 会话；以本轮发言人的身份（无管理员权限）通过平台 MCP 查需求、建需求、启动 SOP、审批节点，接入知识库时先检索；实时显示工具调用，可随时停止 |
| 执行机器 | 个人机器与共享执行池（upstream runner）：机器只向服务端发起 HTTPS 连接，认领分配给它的运行，用本机已登录的 CLI 与凭据执行；人工审批、取消等在控制台操作，机器在下一个调度周期内响应；PR 核验由服务端用自己的 API 凭据完成 |
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

### 沙箱环境

除了部署配置的 `local` / `docker` / `bwrap`，管理员可以在「沙箱环境」里定义更多沙箱配置供 SOP 选择（`sandboxProfileId`）：

- **沙箱配置**：驱动 `local`（服务器进程）、`native`（bubblewrap 隔离）或 `docker`（需要启用 `loopforge.sandbox.docker`），可选 Dockerfile、环境、只读根文件系统与额外变量；`native` 与 `docker` 视为隔离环境，成员可用。
- **环境**：每条命令都带上的变量、放到 `PATH` 最前面的工作区相对目录，以及每个工作区第一次执行命令前运行一次的安装脚本。
- **Dockerfile**：Dockerfile 与构建上下文文件；第一次使用时构建镜像 `loopforge-env/<id>:<内容哈希>`，内容不变就复用。

仍被引用的环境、Dockerfile 和沙箱配置不能删除。执行机器只支持它本机配置的沙箱。

### 前端预览

在 SOP 面板勾选「前端预览」（`preview` 约定：执行器默认 `codebuddy`、模型、站点目录默认 `dist`、入口默认 `index.html`、单页应用回退、保留天数最多 30 天）后，保存时会在流程末尾追加系统步骤「构建静态站点」（`af-preview-build`，可拖到流程中任意位置，关掉约定即移除）：

- 工作区没有前端工程（Vite / webpack / Next 等配置、前端依赖的 `package.json`、`index.html`）时直接跳过，业务交付不受影响；
- 否则由 AI 用相对资源根做生产构建，产物契约要求入口文件存在；步骤成功后立即归档，后面的人工审批就能打开预览；归档失败时在运行成功后用最终工作区再试一次；
- 执行机器上构建的站点打包上传给服务器，服务器解包时再校验一遍。

也可以直接放「预览」节点：用自己的构建命令（例如 `npm ci && npm run build -- --base=./`）构建后归档。归档只收普通文件（链接、设备文件、硬链接都会让归档失败，`.git` 跳过），默认上限 2 万个文件、单文件 100 MB、合计 500 MB。

运行页的「预览」面板显示状态（可打开 / 已跳过 / 失败 / 已过期），「打开预览」生成会话链接 `/preview/<令牌>/`（只存令牌哈希，闲置 30 分钟失效，可撤销）。可以填后端地址：页面里的 fetch / XHR / WebSocket 请求会转到该地址（路径与参数不变）；维护者可以把它存为默认。网关会给页面加 `<base>`、把根路径资源改到预览路径下、注入 History 适配，所以按 `/` 构建的单页应用也能打开。

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `loopforge.preview.public-url` | 空 | 单独的预览源（例如 `https://preview.example.com` 指向本服务）；配置后预览只在该源上提供，页面拥有自己的源（`allow-same-origin`） |
| `loopforge.preview.session-ttl` | `30m` | 链接闲置多久失效 |
| `loopforge.preview.retention` | `30d` | 预览节点归档的保留期 |
| `loopforge.preview.max-files` / `max-file-size` / `max-size` | `20000` / `100MB` / `500MB` | 归档上限 |
| `loopforge.preview.frame-ancestors` | 空 | 允许嵌入预览的其他来源 |

没有单独的预览源时，页面运行在不透明源里：读不到控制台的登录令牌，也不能借控制台身份调用 API；浏览器存储与 Cookie 用内存模拟。

### MCP 市场与项目 / 个人 MCP

「规则 / Skills / MCP → MCP 服务器」的「MCP 市场」列出可添加的远程 MCP（目前是 GitHub 官方 MCP）。项目维护者把条目添加到项目后，角色的 MCP 列表写市场 id（如 `github`）即可：每个运行用自己项目的那一份，没有安装的项目里自动略过。GitHub MCP 使用运行负责人在「设置」里配置的 GitHub Token（没有时用平台的），只发送给官方 HTTPS 端点；对话只使用发言人自己的 Token。

项目维护者也可以给项目添加自定义 HTTP MCP，每个人可以在「设置 → 我的 MCP」添加自己的 HTTP MCP：项目的只在该项目的运行里可用，个人的只在本人负责的运行里可用，其他人看不到。自定义 MCP 只能是 HTTP（不能定义服务器要启动的程序），请求头里也不能写密钥。

### 项目角色与通用模板

角色、规则和 Skills 可以属于一个项目（`projectId`）：共享的由管理员维护、所有项目可用；项目自己的由该项目的维护者维护，只在该项目可见、只能用于该项目的 SOP 和运行，创建后不能改到别的项目。SOP 只能用共享角色或本项目的角色，角色只能绑定共享或本项目的规则与 Skills；运行时引擎同样只解析运行所在项目可用的角色、规则和 Skills。

管理员可以把全局 SOP 标记为通用模板（`generic`）：模板不能直接运行，只能用共享角色。项目维护者在「SOP → 通用模板」把模板复制到项目，得到一份草稿 SOP（标签 `generic-source:<模板 id>`），模板用到的角色复制为项目自己的副本（`<角色 id>-<项目 id>`），之后可以按项目调整；再次复制会复用项目里已有的副本，不覆盖本地修改。

### SkillHub 技能市场

配置 `loopforge.skillhub.base-url`（与可选的 `api-key`，只发送给市场自己的主机）后，「规则 / Skills / MCP → Skills」可以搜索市场并安装技能（管理员）：指定版本或最新版本的 `SKILL.md` 与文本文件写入技能目录，二进制文件、超限文件与越界路径会被跳过并列出；已安装的技能可以更新到其他版本。市场只看到调用者的匿名标识。

### 运行质量评测

已结束的运行页面有「质量评测」：AI 根据这次运行的事实（每个节点的尝试、返工、耗时、Token、重复失败的工具、反复出现的错误、A→B→A 打回）按控制流、耗时、工具、Token、错误恢复、交付六个加权维度打分（准入线 70），列出发现，并给出受限的改进建议：调整节点的尝试 / 返工 / 失败策略 / 超时，给角色加或删门禁，给角色提示词追加一段话。建议只应用到当前的 SOP 或角色（不改这次运行），改 SOP 需要项目维护者、改角色需要管理员，每次修改都留有历史版本。评测使用发起人的凭据。

### 业务域

「业务域」把项目按业务归组，用来看交付情况（上游 business domains）。管理员创建业务域、指定管理员与查看者（填用户名或 id）并可停用；业务域管理员可以改名称、说明和查看者。项目 Owner 可以给自己的项目设置首次归属，迁移或清除归属需要管理员，每次调整都留有原因和历史。

业务域报表按项目当前的归属统计：运行汇总与趋势（与「分析」同口径），按业务域和项目的运行、Token、费用、涉及需求与完成数，以及交付指标——完成的需求数（以最后一次成功运行为完成时间，没有运行的按需求更新时间估算）、合并的 PR、完成到合并的时长、交付周期 P50 / P95、执行时长、每个需求的 Token，并按天 / 周 / 月给出交付趋势。管理员看全部；业务域的管理员与查看者看该业务域；项目 Owner 看自己尚未归属的项目。

### 运行工作区

运行页的「工作区」打开这次运行的工作区（子 SOP 显示父运行的工作区）：未提交的变更、文件树、文件内容（可看 HEAD 或某个提交时的版本）、与 HEAD 的差异、最近 50 个提交及其改动的文件、全文搜索。开发者及以上可以直接编辑文本文件（1 MB 以内），保存时带上读取时的 SHA-256，文件在此期间被 Agent 或他人改过就会被拒绝；每次修改都记入运行事件。`.git`、`.loopforge`、SSH / GPG 密钥、`.netrc`、`.npmrc` 与 `.env` 不会显示，`node_modules` 等依赖目录不进文件树。

工作区可能由隔离沙箱里的 Agent 写入，所以仓库的 Git 配置里有会启动程序或指向工作区外的项（fsmonitor、hooks、filter、textconv、diff 驱动、pager、include、worktree 等）时，服务器不在该仓库运行 Git，只提供普通文件浏览。已回收、在执行机器或其他实例上的工作区会说明原因。

### 需求多泳道

需求可以绑定一条 SOP 链（上游 SOP bindings）：每一步是一条泳道，有顺序、负责人、依赖的前序步骤和预期的交接产物。项目页「流程套餐」可以定义多个命名的 SOP 链（如「完整需求」「仅后端」），新需求自动使用默认套餐；需求页可以换套餐或单独编辑自己的链。SOP 可标注泳道标签（产品 / 前端 / 后端 / 设计 / 修复 / 其他，不标时按名称推断）。

- 每条泳道单独启动，不同泳道可以并行；同一条 SOP 再跑一次需要选择「重新运行」；
- 后续步骤的运行输入会写明它接续的前序 SOP 与预期产物，要求在其交付物上继续；
- 需求页显示每条泳道的状态（未开始 / 进行中 / 等待处理 / 需关注 / 已完成）、节点阶段（含子 SOP）、运行次数、Token 与整体进度；不在链里的 SOP 运行显示为单独的泳道；
- 需求状态取各泳道最近一次运行：有进行中的就是进行中，有失败的就是受阻，全部完成后按 PR 判断待评审或已完成；
- 自动派发只处理还没跑过 SOP 的需求，后续泳道由人决定。

### 定时任务

项目页「定时任务」有三种类型，都按 cron（可指定时区）触发，也可「立即运行」，每次触发都记入执行记录：

- **运行 SOP**：按固定输入启动一个 SOP；上一次还在运行时可跳过。
- **AI 任务**：在项目里以最后保存任务的人的身份、凭据和完全权限开一轮助手对话执行提示词（不等待确认），记录回复、它启动的运行和对话链接。
- **自动派发需求**：把指定状态（默认 `todo`）的需求按优先级启动各自绑定的 SOP（或统一指定的 SOP），每次最多 N 个，跳过正在运行的需求。

任务以最后保存它的账户执行（定时 SOP 和派发出的运行使用这个人的个人凭据）；修改任务即接手它的执行身份。

### 个人凭据

「设置 → 个人凭据」里每个人可以配置自己的模型凭据（每个执行器：API Key、模型网关 Base URL、其他凭据变量）、Git Token（按主机）和 Git SSH 密钥（按主机，每个主机一把）。你启动的运行、你创建的定时任务、你在助手里的发言和你发起的需求澄清使用这些凭据：某个执行器配置了个人凭据时完全替代平台的 `loopforge.credentials`，Git Token 按主机替代 `loopforge.git.https`，SSH 密钥按主机替代部署的 SSH 密钥。密钥加密保存（生产环境请配置 `loopforge.security.credential-key`），接口只返回末尾几位或指纹。执行机器上的运行仍使用机器本地的凭据。

SSH 私钥（OpenSSH 或 PEM 格式的 ed25519、RSA ≥ 2048、ECDSA，不能有口令）从不写进工作区：服务端在每条命令（Agent CLI、脚本、门禁、检出）运行期间，在工作区控制目录里开一个只有属主可连接的 SSH agent 套接字，并生成只给对应主机提供对应密钥的 ssh 配置；命令结束即关闭。个人密钥只能用于受信任的主机：内置 GitHub 的公钥，其他主机由管理员写入 `loopforge.git.ssh-known-hosts-file`（标准 `known_hosts` 格式），未受信任的主机在设置页标出，SSH 连接会被拒绝。套接字路径受 Unix 108 字节限制，工作区根目录请尽量短（例如 `/data/workspaces`）。

### 让运行改代码并提交 PR

```yaml
loopforge:
  git:
    https:                       # 列表形式，可用 LOOPFORGE_GIT_HTTPS_0_HOST / _TOKEN 环境变量配置
      - host: github.com
        token: ${GITHUB_TOKEN}
    # ssh-private-key-file: /run/secrets/deploy_key   # 部署的 SSH 密钥（没有个人密钥的主机）
    # ssh-known-hosts-file: /etc/loopforge/known_hosts  # 个人 SSH 密钥可用的主机（GitHub 已内置）
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
      read-only-paths: [/usr, /bin, /lib, /lib64, /etc, /opt]   # 进程只能看到这些（只读）、JDK 与自己的工作区
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

### SOP 设计器

控制台「SOP」里打开一个 SOP 即进入设计视图：从工具栏添加 Agent / 脚本 / 人工审批 / 子 SOP 节点，拖动节点调整位置（保存到 `position`），从节点右侧的圆点拖到另一个节点即可连线；点选节点或连线后在右侧面板编辑角色、执行器、任务说明、脚本、产物、打回目标、失败策略与预算，Delete 键删除。「JSON」页签保留原始定义，两边同步；「校验」一次列出全部问题。

### 需求 AI 澄清

项目页需求行的「澄清」打开澄清页。每一轮在服务端后台运行（关闭网页不会中断）：分析师 Agent 在需求专属的工作区里只读检出需求绑定的仓库（每轮重置到基础分支最新提交），对照代码只追问阻塞开工的问题；你在页面上选择或填写回答后提交下一轮，第三轮起会主动收敛。结论写入需求描述末尾的「## LoopForge 澄清结论」（再次澄清会替换这一段），待规划的需求转为待办；从 GitHub / GitLab（工蜂）/ TAPD 同步来的需求同时把结论评论到原单（同一结论只回写一次）。澄清是可选步骤，不阻止启动 SOP；执行器与沙箱沿用 `loopforge.assistant.*`，单轮上限 `loopforge.assistant.clarify-timeout`（默认 10 分钟）。

### 平台助手（对话）

控制台「助手」里新建个人或项目对话（项目成员能查看项目对话，只有发起人能发言）。每一轮由默认执行器（或 `loopforge.assistant.executor`）在对话专属的工作区里运行，下一轮续接同一个 CLI 会话；助手拿到的平台 MCP 凭据代表本轮发言人（项目对话只限该项目），只在这一轮回复期间有效，永远没有管理员权限。工作区沙箱默认 `loopforge.assistant.sandbox-profile=local`，成员仍受隔离沙箱要求约束（为成员开放请设为 `docker` 或 `bwrap`）。

```yaml
loopforge:
  assistant:
    sandbox-profile: docker
    executor: claude
    turn-timeout: 30m
```

### 执行机器（个人机器 / 共享执行池）

在控制台「执行机器 → 注册」得到一次性显示的 Token，然后在装好并登录了 Claude Code / Codex 等 CLI 的机器上运行（容器镜像自带程序，可在同一页下载；也可以自己构建 `loopforge-runner/target/loopforge-runner-*-all.jar`）：

```bash
LOOPFORGE_SERVER_URL=https://loopforge.example.com LOOPFORGE_RUNNER_TOKEN=lfx_... java -jar loopforge-runner.jar
```

启动运行时选择执行机器（`POST /api/v1/runs` 的 `runnerId`）：运行在服务端排队，由这台机器认领并在本机工作区执行；状态、事件、产物都回到服务端，审批、打回、取消照常在控制台操作。个人机器只有所有者能用（脚本以所有者本人的权限运行，因此不受成员隔离沙箱限制）；共享执行池由管理员注册，仍受隔离沙箱要求约束。机器可配置 `LOOPFORGE_RUNNER_CAPACITY`（并发运行数）、`LOOPFORGE_RUNNER_GIT_HTTPS`（推送用的 Git 凭据）、`LOOPFORGE_RUNNER_WORKSPACES`。

### 外部需求同步（GitHub / GitLab（工蜂）/ TAPD）

项目的「外部需求同步」设置（`PUT /api/v1/projects/{id}/tracker`）里选择要拉取 Issue 的代码仓库（GitHub，或 `loopforge.gitlab` 中配置的 GitLab / 工蜂主机，凭据同 `loopforge.git.https`），以及 TAPD 的项目与迭代：

```yaml
loopforge:
  tapd:
    api-user: ${TAPD_API_USER}          # TAPD 开放平台 API 账号
    api-password: ${TAPD_API_PASSWORD}
  trackers:
    sync-interval: 10m                  # 定时同步（多实例时同一时间只有一个实例在拉取）
```

拉取时新的打开项成为需求（已关闭的旧项不导入），已同步的需求只刷新标题、描述与外部状态，状态、负责人、SOP 和运行记录归平台所有；TAPD 迭代里移除且还没开始的需求会被清理，按链接导入的需求保留。运行交付 PR 后，平台把 PR 链接作为评论回写到对应的 Issue / TAPD 条目（每个 PR 一次）。

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
| `GET/POST` | `/api/v1/workflows/{id}/revisions[/{r}[/restore]]`、`/publish`、`/clone` | SOP 历史版本（作者、变化摘要）与恢复；发布（版本 + 1）；复制为草稿 |
| `GET/PUT/DELETE` | `/api/v1/agents/{id}` | 角色定义（仍被 SOP 使用时拒绝删除） |
| `GET/POST` | `/api/v1/agents/{id}/revisions[/{r}[/restore]]` | 角色历史版本与恢复 |
| `GET` / `POST` | `/api/v1/catalog/export` / `/import` | 目录导出 / 导入（默认不覆盖） |
| `GET` | `/api/v1/executors` | 执行器及本机是否已安装 |
| `GET/PUT/DELETE` | `/api/v1/repositories/{id}` | 代码仓库 |
| `GET/PUT/DELETE` | `/api/v1/rules/{id}`、`/skills/{id}`、`/mcp-servers/{id}` | 角色可绑定的规则、Skills、MCP 服务器（规则与 Skills 可带 `projectId`） |
| `GET` / `POST` | `/api/v1/workflow-templates[/{id}]` / `/api/v1/workflow-templates/{id}/adopt` | 通用 SOP 模板；复制到项目（`projectId`、可选 `id`、`name`，需要维护者） |
| `GET` / `POST` | `/api/v1/auth/status`、`/auth/setup`、`/auth/login`、`/auth/logout` | 初始化与登录（公开） |
| `GET/POST/DELETE` | `/api/v1/me`、`/me/tokens`、`/me/password`；`/api/v1/users` | 当前账户与个人 Token；账户管理（管理员） |
| `GET/PUT/DELETE` | `/api/v1/me/credentials[/{kind}\|/kinds]` | 个人凭据：`executor:<kind>`（apiKey、baseUrl、variables）、`git:<host>`（token、username）与 `ssh:<host>`（privateKey；返回指纹、公钥与主机是否受信任）；从不返回密钥 |
| `GET/PUT/DELETE` | `/api/v1/projects/{id}`、`/members/{user}` | 项目与成员 |
| `GET/POST/PUT/DELETE` | `/api/v1/projects/{id}/requirements[/{n}[/runs\|/snapshot\|/sops]]` | 需求、启动泳道（`workflowId`、`rerun`）、泳道快照、SOP 链与套餐 |
| `GET/PUT` | `/api/v1/projects/{id}/playbooks` | 项目的流程套餐（写入需要维护者） |
| `GET/POST/DELETE` | `/api/v1/mcp-market[/install]`、`/api/v1/projects/{id}/mcp-servers[/{sid}]`、`/api/v1/me/mcp-servers[/{sid}]` | MCP 市场与安装到项目、项目 MCP、个人 MCP |
| `GET/PUT` | `/api/v1/business-domains`、`/api/v1/projects/{id}/business-domain[/history]` | 业务域（`createOnly`、乐观并发）、项目归属与历史 |
| `GET` | `/api/v1/business-domain-report?businessDomainId=&from=&to=&grain=&timeZone=` | 业务域报表：运行汇总与趋势、按业务域 / 项目、交付指标与趋势 |
| `GET/PUT` | `/api/v1/runs/{id}/workspace[/tree\|/file\|/diff\|/log\|/commit\|/search]` | 运行工作区：变更、文件树、文件（可带 `ref`）、差异、提交、搜索；`PUT /file` 带 `expectedSha256` 写入 |
| `GET/POST/PUT/DELETE` | `/api/v1/projects/{id}/schedules[/{sid}[/run\|/executions]]` | 定时任务（`kind`：`sop` / `assistant` / `dispatch`）与执行记录（结果、启动的运行、AI 回复与对话） |
| `GET/POST/PUT/DELETE` | `/api/v1/notifications[/{id}]` | Webhook 通知 |
| `GET` / `POST` | `/api/v1/runs/{id}/deliveries` / `/deliveries/refresh` | 运行交付的 PR 与合并状态 |
| `POST` / `GET` | `/api/v1/runs` | 启动运行 / 列表（按状态、SOP、父运行过滤） |
| `GET` | `/api/v1/runs/{id}`、`/events`、`/artifacts`、`/artifacts/{aid}` | 运行详情、事件、产物 |
| `GET` | `/api/v1/runs/{id}/stream` | SSE 实时流（浏览器可用 `?access_token=`） |
| `GET` | `/api/v1/runs/{id}/logs`、`/logs.txt` | 日志分页（`nodeId`、`after` / `before` 游标）与文本下载（含脚本输出，最多 20,000 行） |
| `DELETE` | `/api/v1/runs/{id}` | 删除已结束的运行（含子运行、事件、产物与工作区；项目维护者） |
| `GET/PUT/DELETE` | `/api/v1/sandboxes[/{id}]`、`/environments[/{id}]`、`/dockerfiles[/{id}]` | 沙箱配置（含是否可用、是否隔离）、环境与 Dockerfile（写入需要管理员） |
| `GET/POST` | `/api/v1/skillhub/skills[/{slug}[/versions\|/files\|/file]]`、`/categories`、`/install`、`/update`、`/installed` | SkillHub 技能市场：搜索、详情、文件预览、安装与更新（管理员） |
| `GET/POST` | `/api/v1/runs/{id}/eval[/stream\|/apply]`、`/evals` | 运行质量评测：发起、最新结果、最近 10 次、实时事件、应用建议 |
| `GET/POST/PUT/DELETE` | `/api/v1/runs/{id}/preview[/session[/{sid}]\|/backend\|/download]`、`/preview/{令牌}/**` | 前端预览：状态、会话链接（可带后端地址、可撤销）、默认后端地址、zip 下载、删除；免登录的隔离访问 |
| `POST` | `/api/v1/runs/{id}/cancel`、`/pause`、`/resume`、`/retry` | 运行控制 |
| `POST` | `/api/v1/runs/{id}/nodes/{node}/approve`、`/reject`、`/pass`、`/decline-retry` | 节点决策 |
| `GET/PUT/POST` | `/api/v1/projects/{id}/tracker`、`/tracker/sync`、`/tracker/logs`、`/tracker/import-tapd` | 外部需求同步设置、立即同步、同步记录、按链接导入 TAPD |
| `POST` | `/api/v1/projects/{id}/requirements/{n}/external` | 把本地需求推送为 GitHub / GitLab Issue 或 TAPD 需求 |
| `GET/POST/DELETE` | `/api/v1/assistant/sessions[/{id}[/messages\|/cancel\|/stream]]` | 平台助手对话、发言、停止与实时事件（SSE） |
| `GET/POST/PUT/DELETE` | `/api/v1/projects/{p}/requirements/{n}/clarification[/cancel\|/stream]` | 需求 AI 澄清：开始 / 回答一轮、修改结论、停止、重新开始、实时事件（SSE） |
| `GET/POST/DELETE` | `/api/v1/runners[/{id}]`、`/api/v1/runners/agent.jar` | 执行机器的注册、列表、移除与程序下载 |
| `*` | `/api/v1/runner-api/**` | 执行机器调用的接口（机器 Token 认证，只能访问分配给它的运行） |
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
