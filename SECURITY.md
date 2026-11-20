# 安全策略

请不要通过公开 Issue 报告漏洞。请使用 GitHub 的 *Private vulnerability reporting*（Security → Report a vulnerability）私下提交，并附上复现步骤与影响范围。

部署建议：

- 生产环境使用 `prod` profile，并通过 `LOOPFORGE_SECURITY_APITOKENS` 配置至少 32 位的随机 Token。
- 只在可信网络中暴露服务，或在前面放置 HTTPS 反向代理。
- 本地沙箱隔离环境变量但不隔离文件系统，只用于可信的团队与脚本。
- 有非管理员账户时保持 `loopforge.security.members-require-isolation=true`（默认）：成员只能编写和启动隔离沙箱（`loopforge.security.isolated-profiles`，默认 `docker`）中的 SOP，本地沙箱中的脚本能读到服务的 Token 与数据库。
- 运行只能使用全局或本项目的 SOP、代码仓库和凭据；没有项目的运行只属于管理员。
- Webhook 默认不能指向回环、私有、链路本地（云元数据）、CGNAT 等内网地址；只有通知目标确实在内网时才设置 `loopforge.notifications.allow-private-targets=true`。
- Agent 能把工作区里的目录换成符号链接；引擎侧的控制文件读写都拒绝跟随链接。如果自行扩展执行器或沙箱，请使用 `ManagedFiles` 而不是直接调用 `Files`。
- 执行器凭据通过 `loopforge.credentials.*` 注入，不要写进 SOP 定义、脚本或提示词。
