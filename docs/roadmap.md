# 路线图

每个迭代都保持：`./mvnw verify` 通过（测试、格式、覆盖率门槛）、文档同步更新、移植对照表（[porting.md](porting.md)）记录新增的上游对应关系。

## 迭代 1 — 引擎核心（当前）

- [x] 领域模型与上游 JSON 兼容；工作流校验（返回全部问题）与图算法
- [x] 并行调度、四类节点、门禁终态、命令门禁、自修正循环、返工与尝试预算
- [x] 人工控制：审批 / 打回 / 重试 / 跳过 / 暂停 / 继续 / 取消 / 拒绝重试
- [x] Claude Code、Codex、CodeBuddy、Mock 执行器；本地沙箱（环境隔离、超时、进程树终止）
- [x] JDBC 存储（H2 / PostgreSQL）、Flyway、REST + SSE、Token 认证、OpenAPI、Actuator
- [x] 停机挂起与重启恢复；演示目录；CI、容器镜像、Compose

## 迭代 2 — 代码仓库与交付

- Git 仓库检出到运行工作区（多仓库、工作分支命名、只读仓库）
- HTTPS / SSH 凭据通过 credential helper 注入，不落盘
- `{{repository}}`、`{{existingMR}}` 占位符；GitHub PR 创建、状态刷新、合并检测
- `executionScope=repositories` 的脚本与门禁

## 迭代 3 — 隔离与执行面

- Docker 沙箱（每运行一个容器、只读根文件系统选项、资源限制）
- 门禁 MCP 服务（`submit_gate_result` 工具，基于 MCP Java SDK），保留文件通道作为回退
- 角色级 MCP 服务器与 Skills 物化；Hooks
- 其余 Agent CLI 适配（OpenCode、Cursor、Kimi、Pi 等）

## 迭代 4 — 多实例与运维

- 运行租约（owner + lease_until + 心跳）与跨实例接管
- 遥测批量写入、事件保留策略、运行归档
- Micrometer 指标（节点耗时、门禁通过率、返工率、token 成本）与 OpenTelemetry 追踪

## 迭代 5 — 协作

- 账户与项目、成员与权限、需求跟踪
- Web 控制台（SOP 设计器、运行视图、待审批收件箱）
- 定时任务与通知
