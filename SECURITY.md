# 安全策略

请不要通过公开 Issue 报告漏洞。请使用 GitHub 的 *Private vulnerability reporting*（Security → Report a vulnerability）私下提交，并附上复现步骤与影响范围。

部署建议：

- 生产环境使用 `prod` profile，并通过 `LOOPFORGE_SECURITY_APITOKENS` 配置至少 32 位的随机 Token。
- 只在可信网络中暴露服务，或在前面放置 HTTPS 反向代理。
- 本地沙箱隔离环境变量但不隔离文件系统，只用于可信的团队与脚本。
- 有非管理员账户时保持 `loopforge.security.members-require-isolation=true`（默认）：成员只能编写和启动隔离沙箱（`loopforge.security.isolated-profiles`，默认 `docker`）中的 SOP，本地沙箱中的脚本能读到服务的 Token 与数据库。
- 运行只能使用全局或本项目的 SOP、代码仓库和凭据；没有项目的运行只属于管理员。
- 没有 Docker 的 Linux 主机可以用 `bwrap` 沙箱作为成员可用的隔离环境（加入 `isolated-profiles`）：进程只能看到系统目录（只读）、JDK 与自己的工作区，看不到 `/run`、`/var` 下的主机套接字、主目录与其他运行，但默认共享网络；不要把 `/run`、`/var` 或主目录加入 `read-only-paths`。
- 平台助手以对话发起人的身份操作平台（不含管理员权限）；它的令牌只对当前这一轮回复有效，只能通过平台 MCP 的工具使用（直接调用 API 会被拒绝），不能保存定时任务或通知、不能在执行机器上启动运行。项目成员可以查看项目对话，但只有发起人能发言：工作区与 CLI 会话在多轮之间保留，第二个发言人留下的内容（CLI 设置、git 钩子）会在发起人的下一轮以发起人的权限运行。对话里粘贴或助手读到的外部内容可能诱导它执行操作，因此有影响的操作要求先确认。
- 执行机器的 Token（`lfx_`）只能访问分配给这台机器的运行，不能调用普通 API；移除机器立即吊销。个人机器上的脚本以机器所有者的身份运行，只有所有者能把运行分配给它。
- 启用 OIDC 时设置 `loopforge.public-url`（回调地址）；`link-by-email` 只在身份提供方确实验证邮箱时打开。
- Webhook 默认不能指向回环、私有、链路本地（云元数据）、CGNAT 等内网地址；只有通知目标确实在内网时才设置 `loopforge.notifications.allow-private-targets=true`。
- Agent 通过平台 MCP 和知识库拿到的运行令牌只读、只能访问运行所在项目、运行结束即失效；不要把账户 Token 写进角色的 MCP 配置。
- 知识库上游 Token 默认保存在数据库（只写不读）；不希望落库时用 `loopforge.knowledge.token` 配置。
- Agent 能把工作区里的目录换成符号链接；引擎侧的控制文件读写都拒绝跟随链接。如果自行扩展执行器或沙箱，请使用 `ManagedFiles` 而不是直接调用 `Files`。
- 执行器凭据通过 `loopforge.credentials.*` 注入，不要写进 SOP 定义、脚本或提示词。
- 个人凭据用 AES-256-GCM 加密保存，密文绑定所属账户与类型；生产环境设置 `loopforge.security.credential-key`（32 字节 base64），否则密钥生成后保存在数据库里，数据库备份同时包含密钥与密文。接口从不返回密钥，只返回末尾几位。
- 运行事件、脚本输出与节点结果里出现的凭据值（进程环境里名称像 TOKEN / KEY / SECRET / PASSWORD 的变量）会被替换成 `***`；运行一个 SOP 意味着把你的凭据交给它的步骤，只运行你信任的作者编写的 SOP（SOP 的每次修改都记录作者）。
- 需求澄清在每一轮开始前重新克隆仓库并清空 git 的私有 HOME，克隆用的 SSH 私钥在克隆后立即删除：分析 Agent 留在检出目录里的 git 配置不会在下一轮带着凭据执行。
- 执行机器提交运行状态时不能修改运行的仓库（服务端用自己的 Token 查询这些仓库的 PR）；移除执行机器会让它未完成的运行失败。
- 某个执行器配置了个人凭据时完全替代平台凭据（个人的 Base URL 永远收不到平台的 Key）；个人可设置的额外变量只限凭据类名称（`*_API_KEY`、`*_BASE_URL`、`*_MODEL` 等），不能改 `PATH`、`LD_*`、`NODE_*`、`GIT_*`、`LOOPFORGE_*` 等。
- 助手对话的工作区里只注入发言人自己的 Git Token，不注入平台的；任何 Agent 令牌（包括代表发言人的助手）都不能创建个人 Token、修改密码、管理个人凭据或注册执行机器，避免被诱导后留下比本轮回复更持久的访问。
