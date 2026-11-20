# API 演练

以下命令假设服务运行在 `localhost:8090`，Token 已导出到 `TOKEN`（未配置时见 `./data/api-token`）。

```bash
export TOKEN=$(cat data/api-token)
```

## 1. 定义一个 SOP

两个脚本节点夹一个人工门禁；`approve` 节点可打回 `build`，最多 2 次。

```bash
curl -s -X PUT localhost:8090/api/v1/workflows/release-check \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
  "name": "发布前检查",
  "status": "published",
  "nodes": [
    {"id": "build", "name": "构建", "type": "script", "script": "echo building; date > build.txt",
     "artifactOutputs": [{"name": "build", "path": "build.txt", "mediaType": "text/plain", "required": true}]},
    {"id": "approve", "name": "确认构建", "type": "approval", "rejectTo": "build", "maxReworks": 2,
     "taskPrompt": "检查构建产物"},
    {"id": "publish", "name": "发布", "type": "script", "script": "cat .loopforge/inputs/build/build.txt"}
  ],
  "edges": [{"source": "build", "target": "approve"}, {"source": "approve", "target": "publish"}]
}'
```

校验失败时返回 `422`，`problems` 列出全部问题；只校验不保存用 `POST /api/v1/workflows/validate`。

## 2. 启动并观察

```bash
RUN=$(curl -s -X POST localhost:8090/api/v1/runs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"workflowId": "release-check", "input": "v1.2.0"}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
```

```bash
curl -N "localhost:8090/api/v1/runs/$RUN/stream?access_token=$TOKEN"
```

流中有两类事件：`event`（审计与遥测，`id` 为序号，断线后用 `Last-Event-ID` 续传）和 `run`（运行摘要快照）。运行结束后流自动关闭。

## 3. 人工决策

```bash
curl -s -X POST localhost:8090/api/v1/runs/$RUN/nodes/approve/reject \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"reason": "需要带上版本号", "humanContext": "构建产物里写入 v1.2.0"}'
```

```bash
curl -s -X POST localhost:8090/api/v1/runs/$RUN/nodes/approve/approve \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"humanContext": "确认"}'
```

其他控制：`POST /runs/{id}/pause`、`/resume`、`/cancel`、`/retry`（`{"nodeId": "build"}` 从指定节点重放）、`/nodes/{node}/pass`（跳过失败的脚本节点）。不允许的操作返回 `409`。

## 4. 产物与事件

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8090/api/v1/runs/$RUN/artifacts
```

```bash
curl -s -H "Authorization: Bearer $TOKEN" "localhost:8090/api/v1/runs/$RUN/events?after=0&limit=100"
```

产物按节点尝试版本保留（打回后旧版本仍可查），内容通过 `GET /runs/{id}/artifacts/{artifactId}` 以纯文本或 JSON 返回。

## 5. Agent 节点与终态

Agent 节点完成后必须把终态写入任务书中给出的 `.loopforge/gate-results/<node>/attempt-<n>.json`：

```json
{"decision": "rework", "reason": "缺少回归测试", "actionRequired": "为空输入补一个测试", "findings": [{"severity": "high", "title": "无回归测试"}]}
```

| decision | 引擎行为 |
| --- | --- |
| `pass` | 校验产物契约与命令门禁后流转到下游 |
| `rework` | 打回 `rejectTo` 节点（返工预算耗尽时转人工决策） |
| `fail` | 节点失败（尝试预算内会重试） |
| `human` | 进入 `waiting_approval`，`actionRequired` 必填 |

没有写入终态时，引擎会恢复同一 CLI 会话要求补交一次；仍然没有则转人工确认。

## 6. 导入导出

```bash
curl -s -H "Authorization: Bearer $TOKEN" localhost:8090/api/v1/catalog/export > catalog.json
```

```bash
curl -s -X POST "localhost:8090/api/v1/catalog/import?overwrite=false" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data @catalog.json
```

上游 LoopForge 导出的 workflow JSON（`nodes`/`edges`/`artifactOutputs` 等字段）可以直接放进 `workflows` 数组导入；未移植的字段会被忽略。

## 7. 代码仓库与 PR 交付

先在服务端配置 Git 凭据（见 README「让运行改代码并提交 PR」），再登记仓库：

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X PUT \
  -d '{"name":"App","cloneUrl":"https://github.com/acme/app.git","defaultBranch":"main"}' \
  localhost:8090/api/v1/repositories/app
```

SOP 中的脚本与命令门禁可以设置 `"executionScope":"repositories"`，在每个仓库目录各执行一次（`$LOOPFORGE_WORK_BRANCH`、`$LOOPFORGE_REPOSITORY_DIR` 等变量可用）。角色的 `verification` 里加入 `{"id":"pr","name":"PR 已创建","mode":"mr"}`，Agent 推送提交后必须创建覆盖最新提交的 PR 才能通过：

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"workflowId":"claude-bugfix","input":"修复登录超时",
       "repositories":[{"repositoryId":"app","mode":"auto"}]}' localhost:8090/api/v1/runs
curl -s -H "Authorization: Bearer $TOKEN" localhost:8090/api/v1/runs/<runId>/deliveries          # PR 编号、链接、open/merged/closed
curl -s -H "Authorization: Bearer $TOKEN" -X POST localhost:8090/api/v1/runs/<runId>/deliveries/refresh
```

`mode` 取值：`auto`（默认，工作分支存在则续用，否则新建）、`continue`（必须已存在）、`new`（必须不存在）、`read_only`（不允许推送，MR 门禁跳过）。服务端每 5 分钟（`loopforge.github.refresh-interval`）刷新已成功运行的未合并 PR。

## 8. 规则、Skills、MCP 服务器与 Hooks

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X PUT \
  -d '{"name":"禁止删除","content":"不得删除生产数据","priority":9}' localhost:8090/api/v1/rules/no-delete
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X PUT \
  -d '{"description":"测试先行","content":"先写失败的测试，再实现","files":{"scripts/check.sh":"./mvnw -q test\n"}}' \
  localhost:8090/api/v1/skills/tdd
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -X PUT \
  -d '{"command":"tracker-mcp","environment":{"TRACKER_TOKEN":"${secret:TRACKER}"}}' \
  localhost:8090/api/v1/mcp-servers/tracker
```

角色引用它们（`rules` 里不是目录 id 的条目按内联规则文本处理）：

```json
{"name":"开发","executorId":"claude","prompt":"……",
 "rules":["no-delete","提交信息用中文"],"skills":["tdd"],"mcpServers":["tracker"],
 "hooks":[{"id":"guard","event":"PreToolUse","matcher":"Bash","language":"shell",
           "script":"cat > /dev/null; exit 0","timeoutSec":30}]}
```

敏感值（变量名含 TOKEN/SECRET/PASSWORD/KEY、`Authorization` 头）必须写成 `${secret:NAME}`，值配置在 `loopforge.mcp.secrets.NAME`。运行事件中会出现 `agent.materialized`（本次注入的 MCP、Skills、Hooks 数量）与 `hook.started`/`hook.finished`（Hook id、退出码、耗时）。

