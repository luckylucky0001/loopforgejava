# 安全策略

请不要通过公开 Issue 报告漏洞。请使用 GitHub 的 *Private vulnerability reporting*（Security → Report a vulnerability）私下提交，并附上复现步骤与影响范围。

部署建议：

- 生产环境使用 `prod` profile，并通过 `LOOPFORGE_SECURITY_APITOKENS` 配置至少 32 位的随机 Token。
- 只在可信网络中暴露服务，或在前面放置 HTTPS 反向代理。
- 本地沙箱隔离环境变量但不隔离文件系统，只用于可信的团队与脚本。
- 有非管理员账户时保持 `loopforge.security.members-require-isolation=true`（默认）：成员只能编写和启动隔离沙箱（`loopforge.security.isolated-profiles`，默认 `docker`）中的 SOP，本地沙箱中的脚本能读到服务的 Token 与数据库。
- 运行只能使用全局或本项目的 SOP、代码仓库和凭据；没有项目的运行只属于管理员。
- 没有 Docker 的 Linux 主机可以用 `bwrap` 沙箱作为成员可用的隔离环境（加入 `isolated-profiles`）：进程只能看到系统目录（只读）、JDK 与自己的工作区，看不到 `/run`、`/var` 下的主机套接字、主目录与其他运行，但默认共享网络；不要把 `/run`、`/var` 或主目录加入 `read-only-paths`。
- 执行机器的 Token（`lfx_`）只能访问分配给这台机器的运行，不能调用普通 API；移除机器立即吊销。个人机器上的脚本以机器所有者的身份运行，只有所有者能把运行分配给它。
- 启用 OIDC 时设置 `loopforge.public-url`（回调地址）；`link-by-email` 只在身份提供方确实验证邮箱时打开。
- Webhook 默认不能指向回环、私有、链路本地（云元数据）、CGNAT 等内网地址；只有通知目标确实在内网时才设置 `loopforge.notifications.allow-private-targets=true`。
- Agent 通过平台 MCP 和知识库拿到的运行令牌只读、只能访问运行所在项目、运行结束即失效；不要把账户 Token 写进角色的 MCP 配置。
- 知识库上游 Token 默认保存在数据库（只写不读）；不希望落库时用 `loopforge.knowledge.token` 配置。
- Agent 能把工作区里的目录换成符号链接；引擎侧的控制文件读写都拒绝跟随链接。如果自行扩展执行器或沙箱，请使用 `ManagedFiles` 而不是直接调用 `Files`。
- 执行器凭据通过 `loopforge.credentials.*` 注入，不要写进 SOP 定义、脚本或提示词。
