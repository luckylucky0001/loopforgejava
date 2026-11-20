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
