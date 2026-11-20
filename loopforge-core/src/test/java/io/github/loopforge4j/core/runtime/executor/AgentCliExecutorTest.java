package io.github.loopforge4j.core.runtime.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AgentHook;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.ScriptLanguage;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.runtime.materialize.AgentAssets;
import io.github.loopforge4j.core.runtime.materialize.AgentMaterializer;
import io.github.loopforge4j.core.runtime.materialize.CompiledAgent;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class AgentCliExecutorTest {

    @TempDir
    Path root;

    // ------------------------------------------------------------------ stream translation

    @Test
    void openCodeStream() {
        AgentResult result = parse(
                AgentCliExecutor.OPENCODE,
                "{\"type\":\"step_start\",\"sessionID\":\"ses_1\"}",
                "{\"type\":\"reasoning\",\"sessionID\":\"ses_1\",\"part\":{\"text\":\"想一想\"}}",
                "{\"type\":\"tool_use\",\"part\":{\"callID\":\"c1\",\"tool\":\"bash\",\"state\":{\"status\":\"completed\",\"input\":{\"command\":\"ls\"},\"output\":\"a\"}}}",
                "{\"type\":\"step_finish\",\"part\":{\"reason\":\"tool-calls\",\"tokens\":{\"input\":10,\"output\":2,\"cache\":{\"read\":5}}}}",
                "{\"type\":\"text\",\"part\":{\"text\":\"完成了\"}}",
                "{\"type\":\"step_finish\",\"part\":{\"reason\":\"stop\",\"tokens\":{\"input\":3,\"output\":4}}}");
        assertThat(result.sessionId()).isEqualTo("ses_1");
        assertThat(result.output()).isEqualTo("完成了");
        assertThat(result.terminal()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.usage().inputTokens()).isEqualTo(13);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(5);
    }

    @Test
    void cursorStreamWithPartialOutput() {
        AgentResult result = parse(
                AgentCliExecutor.CURSOR,
                "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"cur-1\",\"model\":\"gpt-5\"}",
                "{\"type\":\"assistant\",\"timestamp_ms\":1,\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Hel\"}]}}",
                "{\"type\":\"assistant\",\"timestamp_ms\":2,\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"lo\"}]}}",
                "{\"type\":\"tool_call\",\"subtype\":\"started\",\"call_id\":\"t1\",\"tool_call\":{\"mcpToolCall\":{\"args\":{\"providerIdentifier\":\"loopforge-gate\",\"toolName\":\"submit_gate_result\",\"args\":{\"decision\":\"pass\"}}}}}",
                "{\"type\":\"tool_call\",\"subtype\":\"completed\",\"call_id\":\"t1\",\"tool_call\":{\"mcpToolCall\":{\"result\":{\"success\":{\"content\":[{\"text\":\"ok\"}]}}}}}",
                "{\"type\":\"result\",\"result\":\"Hello\",\"is_error\":false,\"usage\":{\"inputTokens\":7,\"outputTokens\":8}}");
        assertThat(result.sessionId()).isEqualTo("cur-1");
        assertThat(result.model()).isEqualTo("gpt-5");
        assertThat(result.output()).isEqualTo("Hello");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.usage().outputTokens()).isEqualTo(8);
    }

    @Test
    void antigravityStreamReportsFailure() {
        AgentResult result = parse(
                AgentCliExecutor.ANTIGRAVITY,
                "{\"event\":\"init\",\"conversation_id\":\"conv-1\",\"init\":{\"model\":\"gemini\"}}",
                "{\"event\":\"step_update\",\"step_update\":{\"step_index\":1,\"step_type\":\"error_message\",\"error\":{\"message\":\"quota\"}}}",
                "{\"event\":\"result\",\"result\":{\"status\":\"FAILED\"}}");
        assertThat(result.sessionId()).isEqualTo("conv-1");
        assertThat(result.failed()).isTrue();
        assertThat(result.output()).isEqualTo("quota");
    }

    @Test
    void kimiSessionArrivesWithTheResumeHint() {
        AgentResult result = parse(
                AgentCliExecutor.KIMI,
                "{\"role\":\"assistant\",\"content\":[{\"type\":\"think\",\"think\":\"...\"},{\"type\":\"text\",\"text\":\"好的\"}],\"tool_calls\":[{\"id\":\"k1\",\"function\":{\"name\":\"Shell\",\"arguments\":\"{\\\"cmd\\\":\\\"ls\\\"}\"}}]}",
                "{\"role\":\"tool\",\"tool_call_id\":\"k1\",\"content\":\"a.txt\"}",
                "{\"role\":\"meta\",\"type\":\"session.resume_hint\",\"session_id\":\"kimi-9\"}");
        assertThat(result.sessionId()).isEqualTo("kimi-9");
        assertThat(result.output()).isEqualTo("好的");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void piStream() {
        AgentResult result = parse(
                AgentCliExecutor.PI,
                "{\"type\":\"session\",\"id\":\"pi-1\"}",
                "{\"type\":\"turn_start\"}",
                "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"contentIndex\":0,\"delta\":\"x\"}}",
                "{\"type\":\"message_end\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"结果\"}],\"usage\":{\"input\":2,\"output\":3}}}",
                "{\"type\":\"agent_end\"}");
        assertThat(result.sessionId()).isEqualTo("pi-1");
        assertThat(result.output()).isEqualTo("结果");
        assertThat(result.usage().totalTokens()).isGreaterThanOrEqualTo(0);
        assertThat(result.terminal()).isTrue();
    }

    @Test
    void deepSeekFinalReplacesTruncatedText() {
        AgentResult result = parse(
                AgentCliExecutor.DEEPSEEK,
                "{\"type\":\"session\",\"sessionId\":\"dsh-1\"}",
                "{\"type\":\"text\",\"text\":\"abc\",\"truncated\":true}",
                "{\"type\":\"final\",\"text\":\"abcdef\",\"reason\":{\"kind\":\"completed\"}}");
        assertThat(result.sessionId()).isEqualTo("dsh-1");
        assertThat(result.output()).isEqualTo("abcdef");
        assertThat(result.failed()).isFalse();
    }

    @Test
    void zcodeStream() {
        AgentResult result = parse(
                AgentCliExecutor.ZCODE,
                "{\"type\":\"model.streaming\",\"sessionId\":\"z-1\",\"payload\":{\"assistantMessageId\":\"m\",\"kind\":\"text_delta\",\"delta\":\"he\"}}",
                "{\"type\":\"model.streaming\",\"payload\":{\"assistantMessageId\":\"m\",\"kind\":\"text_delta\",\"delta\":\"llo\"}}",
                "{\"type\":\"model.streaming\",\"payload\":{\"assistantMessageId\":\"m\",\"kind\":\"text_end\"}}",
                "{\"type\":\"turn.failed\",\"payload\":{\"error\":{\"message\":\"bad key\",\"attribution\":{\"statusCode\":401}}}}");
        assertThat(result.sessionId()).isEqualTo("z-1");
        assertThat(result.failed()).isTrue();
        assertThat(result.output()).isEqualTo("bad key (HTTP 401)");
    }

    // ------------------------------------------------------------------ process integration

    @Test
    void openCodeGetsItsMcpConfigInThePrivateHomeAndAPointerPrompt() throws Exception {
        Path fake = fakeCli("opencode", """
                printf '%s\\n' "$@" > argv.txt
                cp "$HOME/.config/opencode/opencode.json" seen-config.json
                echo '{"type":"step_start","sessionID":"ses_x"}'
                echo '{"type":"text","part":{"text":"done"}}'
                echo '{"type":"step_finish","part":{"reason":"stop","tokens":{"input":1,"output":1}}}'
                """);
        Sandbox sandbox = sandbox();
        AgentDefinition agent = AgentDefinition.builder("dev")
                .hooks(List.of(new AgentHook("h", null, "Stop", null, ScriptLanguage.SHELL, "true", 5, null)))
                .build();
        CompiledAgent compiled = compile(sandbox, agent, new AgentAssets(List.of(), List.of(), List.of()));
        List<Telemetry> events = new CopyOnWriteArrayList<>();
        AgentResult result = new AgentCliExecutor(AgentCliExecutor.OPENCODE, CredentialProvider.NONE)
                .execute(
                        invocation(definition(AgentCliExecutor.OPENCODE, fake, "glm-5"), agent, compiled, "任务", null),
                        sandbox,
                        events::add,
                        Cancellation.create());
        assertThat(result.output()).isEqualTo("done");
        assertThat(result.sessionId()).isEqualTo("ses_x");
        List<String> argv = Files.readAllLines(sandbox.workspace().resolve("argv.txt"));
        assertThat(argv).startsWith("run", "--format", "json", "--thinking", "-m", "glm-5");
        assertThat(argv.getLast()).contains(".loopforge/prompts/inv-1.md").doesNotContain("任务");
        assertThat(Files.readString(sandbox.workspace().resolve(".loopforge/prompts/inv-1.md")))
                .startsWith("<system_instructions>")
                .endsWith("任务");
        JsonNode config =
                Json.mapper().readTree(Files.readString(sandbox.workspace().resolve("seen-config.json")));
        assertThat(config.path("mcp").path("loopforge-gate").path("type").asString())
                .isEqualTo("local");
        assertThat(config.path("mcp")
                        .path("loopforge-gate")
                        .path("command")
                        .get(0)
                        .asString())
                .endsWith("java");
        assertThat(events).extracting(Telemetry::name).contains("agent.hooks.unsupported");
    }

    @Test
    void deepSeekLoadsTheMcpPatch() throws Exception {
        Path fake = fakeCli("dsh", """
                printf '%s\\n' "$@" > argv.txt
                echo "$DSH_HOME" > dsh-home.txt
                echo '{"type":"final","sessionId":"d","text":"ok","reason":{"kind":"completed"}}'
                """);
        Sandbox sandbox = sandbox();
        AgentDefinition agent = AgentDefinition.builder("dev").build();
        CompiledAgent compiled = compile(sandbox, agent, AgentAssets.NONE);
        new AgentCliExecutor(AgentCliExecutor.DEEPSEEK, CredentialProvider.NONE)
                .execute(
                        invocation(definition(AgentCliExecutor.DEEPSEEK, fake, null), agent, compiled, "t", "prev"),
                        sandbox,
                        EventSink.NOOP,
                        Cancellation.create());
        List<String> argv = Files.readAllLines(sandbox.workspace().resolve("argv.txt"));
        assertThat(argv).startsWith("--profile", "headless", "--patch");
        assertThat(argv.get(3)).endsWith(".dsh/agentflow-mcp.json");
        assertThat(argv).containsSubsequence("--session-id", "prev");
        assertThat(Files.readString(Path.of(argv.get(3)))).contains("@deepseek-ai/dsh-mcp-client", "loopforge-gate");
        assertThat(Files.readString(sandbox.workspace().resolve("dsh-home.txt")).strip())
                .endsWith("/.dsh");
    }

    @Test
    void everyKindHasAnArgvShapeAndConfigDocument() {
        McpServerDefinition stdio = McpServerDefinition.stdio("s", "srv", List.of("a"), Map.of("K", "v"));
        McpServerDefinition http = new McpServerDefinition(
                "h", null, null, "http", null, null, "https://x/mcp", null, Map.of("A", "b"), null, 0, null);
        for (String kind : AgentCliExecutor.KINDS) {
            AgentCliExecutor executor = new AgentCliExecutor(kind, CredentialProvider.NONE);
            List<String> argv = executor.arguments(invocation(
                    definition(kind, Path.of("bin"), "m1"),
                    AgentDefinition.builder("a").build(),
                    null,
                    "x",
                    "r1"));
            assertThat(argv).as(kind).isNotEmpty().contains("r1");
            assertThat(String.join(" ", argv)).as(kind).contains(".loopforge/prompts/inv-1.md");
            String doc = Json.write(AgentCliExecutor.mcpDocument(kind, List.of(stdio, http)));
            assertThat(doc).as(kind).contains("https://x/mcp", "srv");
        }
        assertThat(Json.write(AgentCliExecutor.mcpDocument(AgentCliExecutor.ANTIGRAVITY, List.of(http))))
                .contains("serverUrl");
        assertThat(Json.write(AgentCliExecutor.mcpDocument(AgentCliExecutor.KIMI, List.of(stdio))))
                .contains("toolTimeoutMs");
    }

    @Test
    void codexKeepsSecretsOffTheCommandLine() throws Exception {
        Sandbox sandbox = sandbox();
        McpServerDefinition stdio =
                McpServerDefinition.stdio("tracker", "tracker-mcp", List.of("--x"), Map.of("API_KEY", "${secret:T}"));
        McpServerDefinition http = new McpServerDefinition(
                "docs",
                null,
                null,
                "http",
                null,
                null,
                "https://docs/mcp",
                null,
                Map.of("Authorization", "Bearer ${secret:T}"),
                null,
                15,
                null);
        AgentDefinition agent = AgentDefinition.builder("dev")
                .hooks(List.of(
                        new AgentHook("guard", null, "PreToolUse", "shell", ScriptLanguage.SHELL, "true", 5, null)))
                .build();
        CompiledAgent compiled = AgentMaterializer.materialize(new AgentMaterializer.Request(
                sandbox.workspace(),
                "inv-1",
                "codex",
                agent,
                new AgentAssets(
                        List.of(),
                        List.of(new SkillDefinition("tdd", null, "d", "body", Map.of(), null)),
                        List.of(stdio, http)),
                ".loopforge/gate-results/n/attempt-1.json",
                null,
                Map.of("T", "top-secret")::get,
                List.of()));
        CodexExecutor codex = new CodexExecutor(CredentialProvider.NONE);
        List<String> argv = codex.arguments(invocation(
                new ExecutorDefinition(
                        "codex", "Codex", "codex", "codex", List.of("exec", "--json"), "jsonl", null, null),
                agent,
                compiled,
                "x",
                null));
        String joined = String.join(" ", argv);
        assertThat(joined).doesNotContain("top-secret");
        assertThat(joined)
                .contains("mcp_servers.tracker.command=\"sh\"")
                .contains("mcp_servers.docs.url=\"https://docs/mcp\"")
                .contains("mcp_servers.docs.env_http_headers={ \"Authorization\" = \"LOOPFORGE_MCP_HEADER_0\" }")
                .contains("mcp_servers.docs.tool_timeout_sec=15.0")
                .contains("mcp_servers.loopforge-gate.command=")
                .contains("hooks.PreToolUse=[{hooks=[{type=\"command\",command=\"sh '")
                .contains("skills.config=[{ path = ")
                .contains("--dangerously-bypass-hook-trust");
        assertThat(codex.environment(invocation(null, agent, compiled, "x", null), sandbox))
                .containsEntry("LOOPFORGE_MCP_HEADER_0", "Bearer top-secret");
        Path launcher = compiled.runtimeDir().resolve("mcp-tracker.sh");
        assertThat(Files.readString(launcher)).contains("'API_KEY=top-secret'", "'tracker-mcp' '--x'");
        compiled.cleanup();
        assertThat(launcher).doesNotExist();
    }

    // ------------------------------------------------------------------ helpers

    private static AgentResult parse(String kind, String... lines) {
        Function<String, List<String>> translator = AgentCliTranslators.forKind(kind);
        AnthropicStreamParser parser = new AnthropicStreamParser(kind, EventSink.NOOP);
        List<String> rendered = new ArrayList<>();
        for (String line : lines) {
            for (String out : translator.apply(line)) {
                rendered.add(out);
                parser.accept(out);
            }
        }
        assertThat(rendered).allSatisfy(l -> assertThat(Json.tryParseObject(l)).isNotNull());
        return parser.result(0);
    }

    private Sandbox sandbox() throws Exception {
        return new LocalSandboxProvider(LocalSandboxOptions.defaults(root.resolve("ws"))).acquire("run-1");
    }

    private CompiledAgent compile(Sandbox sandbox, AgentDefinition agent, AgentAssets assets) throws Exception {
        return AgentMaterializer.materialize(new AgentMaterializer.Request(
                sandbox.workspace(),
                "inv-1",
                "x",
                agent,
                assets,
                ".loopforge/gate-results/n/attempt-1.json",
                null,
                null,
                List.of()));
    }

    private static ExecutorDefinition definition(String kind, Path binary, String model) {
        return new ExecutorDefinition(kind, kind, kind, binary.toString(), List.of(), "agent-cli-stream", model, null);
    }

    private static AgentInvocation invocation(
            ExecutorDefinition definition,
            AgentDefinition agent,
            CompiledAgent compiled,
            String prompt,
            String resume) {
        return new AgentInvocation(
                "run-1",
                "node",
                1,
                "inv-1",
                definition == null ? definition(AgentCliExecutor.OPENCODE, Path.of("x"), null) : definition,
                agent,
                prompt,
                resume,
                null,
                List.of(),
                null,
                Map.of(),
                compiled);
    }

    private Path fakeCli(String name, String body) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body);
        file.toFile().setExecutable(true);
        return file;
    }
}
