package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A CLI agent interrupted mid-invocation keeps its native session: the session id is persisted as soon
 * as the stream reports it, and resuming passes {@code --resume <id>} with the short continue prompt.
 */
class AgentSessionResumeTest {

    @TempDir
    Path root;

    @Test
    void pausedAgentResumesItsCliSession() throws Exception {
        Path log = root.resolve("calls.log");
        Path cli = root.resolve("fake-claude");
        Files.writeString(cli, """
                #!/usr/bin/env python3
                import json, os, re, sys, time
                argv = sys.argv[1:]
                prompt = sys.stdin.read()
                resume = argv[argv.index("--resume") + 1] if "--resume" in argv else None
                with open(%s, "a") as f:
                    f.write(json.dumps({"resume": resume, "prompt": prompt[:40]}, ensure_ascii=False) + "\\n")
                session = resume or "sess-fixed-1"
                print(json.dumps({"type": "system", "subtype": "init", "session_id": session}), flush=True)
                if resume is None:
                    time.sleep(30)
                gate = re.search(r"(\\.loopforge/gate-results/[^`\\s]+\\.json)", prompt).group(1)
                os.makedirs(os.path.dirname(gate), exist_ok=True)
                json.dump({"decision": "pass", "reason": "继续完成"}, open(gate, "w"))
                print(json.dumps({"type": "result", "session_id": session, "result": "done", "is_error": False}), flush=True)
                """.formatted("'" + log + "'"));
        cli.toFile().setExecutable(true);

        try (EngineFixture f = new EngineFixture(root.resolve("ws"))) {
            f.engine
                    .executors()
                    .define(new ExecutorDefinition(
                            "fake",
                            "Fake Claude",
                            "claude-code",
                            cli.toString(),
                            List.of("-p", "--output-format", "stream-json"),
                            "stream-json",
                            null,
                            null));
            f.agent(AgentDefinition.builder("dev")
                            .name("开发")
                            .executor("fake")
                            .prompt("实现功能")
                            .build())
                    .workflow(WorkflowDefinition.builder("wf")
                            .node(WorkflowNode.builder("implement", NodeType.AGENT)
                                    .agent("dev")
                                    .build())
                            .build());
            Run run = f.start("wf");
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> "sess-fixed-1"
                            .equals(f.engine
                                    .require(run.id())
                                    .requireNode("implement")
                                    .sessionId()));

            f.engine.pause(run.id());
            Run paused = f.awaitStatus(run.id(), RunStatus.WAITING);
            assertThat(paused.requireNode("implement").paused()).isTrue();
            assertThat(paused.requireNode("implement").status()).isEqualTo(NodeStatus.WAITING_APPROVAL);

            f.engine.resume(run.id(), "请补充单元测试");
            f.awaitStatus(run.id(), RunStatus.SUCCEEDED);

            List<String> calls = Files.readAllLines(log);
            assertThat(calls).hasSize(2);
            assertThat(calls.get(0)).contains("\"resume\": null").contains("节点任务书");
            assertThat(calls.get(1)).contains("\"resume\": \"sess-fixed-1\"").contains(PromptCompiler.RESUME_PROMPT);
        }
    }
}
