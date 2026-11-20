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
