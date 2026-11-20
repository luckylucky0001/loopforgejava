package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AgentHook;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.ScriptLanguage;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * End to end through a fake Claude Code CLI that behaves like the real one: it loads the strict MCP
 * config, starts the gate MCP server (the packaged jar in a separate JVM), submits its decision via
 * {@code submit_gate_result} and runs the PreToolUse hook from the settings file.
 */
class RoleMaterializationTest {

    @TempDir
    Path root;

    @Test
    void cliSubmitsItsGateResultThroughTheMcpServerAndRunsRoleHooks() throws Exception {
        Path log = root.resolve("argv.json");
        Path cli = root.resolve("fake-claude");
        Files.writeString(cli, """
                #!/usr/bin/env python3
                import json, os, subprocess, sys
                argv = sys.argv[1:]
                prompt = sys.stdin.read()
                def opt(name):
                    return argv[argv.index(name) + 1] if name in argv else None
                json.dump({"argv": argv, "system": prompt.startswith("<system_instructions>"),
                           "env": sorted(k for k in os.environ if k.startswith("LOOPFORGE_"))}, open(%s, "w"))
                print(json.dumps({"type": "system", "subtype": "init", "session_id": "s-1"}), flush=True)
                config = json.load(open(opt("--mcp-config")))["mcpServers"]
                gate = config["loopforge-gate"]
                env = dict(os.environ); env.update(gate.get("env", {}))
                server = subprocess.Popen([gate["command"]] + gate["args"], stdin=subprocess.PIPE,
                                          stdout=subprocess.PIPE, env=env, text=True)
                requests = [
                    {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}},
                    {"jsonrpc": "2.0", "method": "notifications/initialized"},
                    {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "submit_gate_result",
                        "arguments": {"decision": "pass", "reason": "通过 MCP 提交", "summary": "done"}}},
                ]
                out, _ = server.communicate("\\n".join(json.dumps(r) for r in requests) + "\\n", timeout=60)
                responses = [json.loads(l) for l in out.splitlines() if l.strip()]
                assert "error" not in responses[1], responses
                settings = json.load(open(opt("--settings")))
                for group in settings["hooks"]["PreToolUse"]:
                    for hook in group["hooks"]:
                        subprocess.run(hook["command"], shell=True, input='{"tool_name":"Bash"}', text=True)
                print(json.dumps({"type": "result", "session_id": "s-1", "result": "ok", "is_error": False}), flush=True)
                """.formatted("'" + log + "'"));
        cli.toFile().setExecutable(true);

        try (EngineFixture f =
                new EngineFixture(root.resolve("ws"), new io.github.loopforge4j.core.store.InMemoryRunStore(), false)) {
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
            f.catalog.saveAsset(AssetKind.SKILL, new SkillDefinition("tdd", "TDD", "测试先行", "先写失败的测试。", Map.of(), null));
            f.catalog.saveAsset(
                    AssetKind.MCP_SERVER, McpServerDefinition.stdio("echo", "/bin/cat", List.of(), Map.of()));
            f.agent(AgentDefinition.builder("dev")
                            .name("开发")
                            .executor("fake")
                            .prompt("实现功能")
                            .skills(List.of("tdd"))
                            .mcpServers(List.of("echo"))
                            .hooks(List.of(new AgentHook(
                                    "audit",
                                    "审计",
                                    "PreToolUse",
                                    "Bash",
                                    ScriptLanguage.SHELL,
                                    "cat > \"$LOOPFORGE_WORKSPACE/hook-input.json\"",
                                    30,
                                    null)))
                            .build())
                    .workflow(WorkflowDefinition.builder("wf")
                            .node(WorkflowNode.builder("implement", NodeType.AGENT)
                                    .agent("dev")
                                    .build())
                            .build());
            Run run = f.start("wf");
            Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            assertThat(done.requireNode("implement").gateResult().reason()).isEqualTo("通过 MCP 提交");

            JsonNode call = Json.mapper().readTree(Files.readString(log));
            List<String> argv = Json.mapper().convertValue(call.path("argv"), List.class);
            assertThat(argv).contains("--strict-mcp-config", "--mcp-config", "--settings", "--plugin-dir");
            assertThat(argv.get(argv.indexOf("--setting-sources") + 1)).isEqualTo("user");
            assertThat(call.path("system").asBoolean()).isTrue();
            assertThat(call.path("env").toString()).contains("LOOPFORGE_HOOK_OUTPUT");

            Path ws = Path.of(f.engine.require(run.id()).workspaceRoot());
            assertThat(Files.readString(ws.resolve("hook-input.json"))).isEqualTo("{\"tool_name\":\"Bash\"}");
            assertThat(ws.resolve(".loopforge/agents/dev/skills/tdd/SKILL.md")).exists();
            // runtime files with resolved MCP configuration are gone after the invocation
            try (var children = Files.list(ws.resolve(".loopforge/runtime"))) {
                assertThat(children.toList()).isEmpty();
            }
            List<String> types = f.engine.events(run.id(), 0, 1000).stream()
                    .map(RunEvent::type)
                    .toList();
            assertThat(types).contains("agent.materialized", "hook.started", "hook.finished");
        }
    }

    @Test
    void unknownSkillFailsTheNodeAsConfigurationError() throws Exception {
        try (EngineFixture f = new EngineFixture(root.resolve("ws"))) {
            f.agent(AgentDefinition.builder("dev")
                            .executor("mock")
                            .skills(List.of("ghost"))
                            .build())
                    .workflow(WorkflowDefinition.builder("wf")
                            .node(WorkflowNode.builder("a", NodeType.AGENT)
                                    .agent("dev")
                                    .maxAttempts(3)
                                    .build())
                            .build());
            Run run = f.start("wf");
            Run failed = f.awaitStatus(run.id(), RunStatus.FAILED);
            assertThat(failed.requireNode("a").error()).contains("skill 'ghost' not found");
            assertThat(failed.requireNode("a").attempt()).isEqualTo(1);
        }
    }
}
