# 安全策略

请不要通过公开 Issue 报告漏洞。请使用 GitHub 的 *Private vulnerability reporting*（Security → Report a vulnerability）私下提交，并附上复现步骤与影响范围。

部署建议：

- 生产环境使用 `prod` profile，并通过 `LOOPFORGE_SECURITY_APITOKENS` 配置至少 32 位的随机 Token。
- 只在可信网络中暴露服务，或在前面放置 HTTPS 反向代理。
- 本地沙箱隔离环境变量但不隔离文件系统，只用于可信的团队与脚本。
- 执行器凭据通过 `loopforge.credentials.*` 注入，不要写进 SOP 定义、脚本或提示词。
