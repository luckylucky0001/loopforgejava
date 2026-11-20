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

## 迭代 6 — 对齐补充（已完成）

- [x] GitLab / 工蜂（GitLab 兼容 API）MR 交付与合并检测；`glab` 凭据注入；按仓库路由的组合交付提供方
- [x] 运行准入：全局与每项目的并发运行上限，超出排队并按先后自动启动（等待人工的运行不占名额）
- [x] 成本估算：移植上游公开价格表，CLI 未报告成本时按模型估算，可配置覆盖

## 迭代 7 — 平台 MCP、知识库与分析（已完成）

- [x] 平台 MCP：`/mcp` 无状态 Streamable HTTP、操作清单与 `/api/v1/capabilities`、JDK-only stdio 桥
- [x] 角色开关 `platformMcp` / `knowledgeBase`；每次调用签发只读、限本项目、随运行失效的运行令牌
- [x] 团队知识库：接入配置、按项目授权来源、Agent 只读代理 MCP、使用规范、`knowledge.md` 审核写回、调用记录
- [x] 分析报表与 SOP 运营指标；控制台分析页与知识库页

## 迭代 8 — 群机器人、轻量沙箱与单点登录（已完成）

- [x] 企业微信 / 飞书 / 钉钉 / Slack 群机器人通知（原生消息格式、加签、平台错误码、运行链接）
- [x] bubblewrap 轻量沙箱：主机只读、只写本运行工作区、私有 /tmp、独立命名空间，隐藏其他运行与服务端 HOME
- [x] OIDC 单点登录：授权码 + PKCE、JWKS 校验 RS256/ES256、issuer+subject 绑定账户、邮箱域限制；控制台 SSO 入口

## 迭代 9 — 外部需求同步（已完成）

- [x] 从 GitHub / GitLab（工蜂）Issue、TAPD 迭代（需求与缺陷）拉取需求；平台拥有导入后的生命周期
- [x] 按链接导入 TAPD 需求 / 缺陷 / 任务；把本地需求推送为外部 Issue / TAPD 需求
- [x] 交付后回写 PR 链接；定时同步（多实例租约）与同步记录；控制台

## 迭代 10 — 执行机器（已完成）

- [x] 运行可分配给执行机器（个人 / 共享执行池）；服务端只排队与协调，从不接管机器的运行
- [x] 机器注册、Token、心跳与在线状态；机器接口只暴露分配给它的运行与所需的目录
- [x] `loopforge-runner`：机器上运行完整引擎（远程存储、本机沙箱与 CLI），PR 核验与平台 MCP 由服务端完成
- [x] 独立审查迭代 7–9 发现的 9 个问题全部修复（bwrap 套接字逃逸、SSO 会话固定、机器人通知读取上限等）

## 迭代 11 — 平台助手（已完成）

- [x] 个人与项目对话；每轮在对话专属工作区运行 CLI 并续接会话；可停止；删除对话即删除工作区
- [x] 平台 MCP 代表本轮发言人（无管理员权限、项目对话限本项目、只在回复期间有效）；知识库与使用规范
- [x] 实时活动（SSE）；控制台「助手」页；成员仍受隔离沙箱要求约束

## 后续

- 对话的群协作、浏览器工具与自动登记 PR；云端执行池的自动扩缩容
- 企业微信智能机器人（卡片内审批）；需求 AI 澄清
- 可视化拖拽式 SOP 设计器；业务域报表与运行质量评估
