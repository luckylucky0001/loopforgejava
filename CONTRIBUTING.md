# 贡献指南

## 开发环境

- JDK 21+，使用仓库自带的 `./mvnw`（Maven 3.9.11）
- 建议 `LANG=C.UTF-8`

```bash
./mvnw -B verify          # 测试 + Spotless 格式检查 + JaCoCo 覆盖率门槛
./mvnw spotless:apply     # 自动格式化（palantir-java-format）
```

设置 `LOOPFORGE_TEST_POSTGRES_URL`（如 `jdbc:postgresql://localhost:5432/loopforge_test`）后会额外对 PostgreSQL 运行存储契约测试；CI 总会运行。

## 约定

- `loopforge-core` 不得依赖 Spring；新增持久化或执行能力先定义端口，再在服务模块实现。
- 运行状态只能在 `RunStore.update` 的 mutator 中修改，mutator 必须无副作用（可能被重试）。
- 新的存储实现必须通过 `RunStoreContract`。
- 新增引擎行为需配套 `RunEngineTest` 场景测试（使用 Mock 执行器与真实本地沙箱）。
- 数据库变更只通过新的 Flyway 迁移（`V<n>__描述.sql`），SQL 需同时兼容 H2 与 PostgreSQL。
- 从上游移植代码或文案时，在 [docs/porting.md](docs/porting.md) 记录来源；不要引入上游未以 MIT 授权的第三方素材。
- 提交信息使用 Conventional Commits（`feat(engine): ...`、`fix(server): ...`）。
