package io.github.loopforge4j.core.runtime.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real CLI adapters against fake binaries that replay recorded protocol frames. */
class CliAgentExecutorTest {

    @TempDir
    Path root;

    @Test
    void claudeAdapterBuildsUnattendedCommandAndParsesStream() throws Exception {
        Path fake = fakeCli("claude", """
                printf '%s\\n' "$@" > argv.txt
                cat > stdin.txt
                echo "key=$ANTHROPIC_API_KEY sandbox=$IS_SANDBOX" > env.txt
                echo '{"type":"system","subtype":"init","session_id":"abc","model":"m1"}'
                echo '{"type":"result","result":"ok","session_id":"abc","usage":{"input_tokens":3,"output_tokens":4}}'
                """);
        ClaudeCodeExecutor executor = new ClaudeCodeExecutor(kind -> Map.of("ANTHROPIC_API_KEY", "sk-test"));
        ExecutorDefinition definition = new ExecutorDefinition(
                "claude",
                "Claude Code",
                "claude-code",
                fake.toString(),
                List.of("-p", "--output-format", "stream-json", "--permission-mode", "dontAsk"),
                "stream-json",
                "sonnet",
                "high");
        Sandbox sandbox = sandbox();

        AgentResult result = executor.execute(
                invocation(definition, "做事", "sess-1"), sandbox, EventSink.NOOP, Cancellation.create());

        assertThat(result.output()).isEqualTo("ok");
        assertThat(result.sessionId()).isEqualTo("abc");
        List<String> argv = Files.readAllLines(sandbox.workspace().resolve("argv.txt"));
        assertThat(argv)
                .containsSubsequence("--model", "sonnet")
                .containsSubsequence("--effort", "high")
                .containsSubsequence("--permission-mode", "bypassPermissions")
                .containsSubsequence("--resume", "sess-1")
                .contains("--verbose")
                .doesNotContain("dontAsk");
        assertThat(argv).doesNotContain("做事");
        assertThat(Files.readString(sandbox.workspace().resolve("stdin.txt"))).isEqualTo("做事");
        assertThat(Files.readString(sandbox.workspace().resolve("env.txt"))).contains("key=sk-test", "sandbox=1");
        assertThat(sandbox.workspace().resolve(".loopforge/prompts/inv-1.md")).exists();
    }

    @Test
    void codexAdapterPlacesResumeAfterOptions() throws Exception {
        Path fake = fakeCli("codex", """
                printf '%s\\n' "$@" > argv.txt
                echo '{"type":"thread.started","thread_id":"t1"}'
                echo '{"type":"item.completed","item":{"type":"agent_message","text":"done"}}'
                echo '{"type":"turn.completed","usage":{"input_tokens":1,"output_tokens":1}}'
                """);
        ExecutorDefinition definition = new ExecutorDefinition(
                "codex", "Codex", "codex", fake.toString(), List.of("exec", "--json"), "jsonl", "gpt-x", "high");
        Sandbox sandbox = sandbox();

        AgentResult result = new CodexExecutor(CredentialProvider.NONE)
                .execute(invocation(definition, "task", "t0"), sandbox, EventSink.NOOP, Cancellation.create());

        assertThat(result.output()).isEqualTo("done");
        List<String> argv = Files.readAllLines(sandbox.workspace().resolve("argv.txt"));
        assertThat(argv)
                .containsExactly(
                        "exec",
                        "--json",
                        "--model",
                        "gpt-x",
                        "-c",
                        "model_reasoning_effort=\"high\"",
                        "resume",
                        "t0",
                        "-");
    }

    @Test
    void nonZeroExitWithoutResultFails() throws Exception {
        Path fake = fakeCli("broken", "echo 'auth failed' >&2\nexit 2\n");
        ExecutorDefinition definition = new ExecutorDefinition(
                "claude", "Claude", "claude-code", fake.toString(), List.of("-p"), "stream-json", null, null);
        assertThatThrownBy(() -> new ClaudeCodeExecutor(null)
                        .execute(invocation(definition, "x", null), sandbox(), EventSink.NOOP, Cancellation.create()))
                .isInstanceOf(ExecutorException.class)
                .hasMessageContaining("exited with code 2")
                .hasMessageContaining("auth failed");
    }

    @Test
    void missingBinaryIsReported() throws Exception {
        ExecutorDefinition definition = new ExecutorDefinition(
                "claude",
                "Claude",
                "claude-code",
                root.resolve("does-not-exist").toString(),
                List.of(),
                "stream-json",
                null,
                null);
        assertThatThrownBy(() -> new ClaudeCodeExecutor(null)
                        .execute(invocation(definition, "x", null), sandbox(), EventSink.NOOP, Cancellation.create()))
                .isInstanceOf(ExecutorException.class)
                .hasMessageContaining("failed to start");
    }

    @Test
    void mockDirectives() {
        assertThat(MockAgentExecutor.decision("[[mock:gate=rework,pass]]", 1)).isEqualTo("rework");
        assertThat(MockAgentExecutor.decision("[[mock:gate=rework,pass]]", 2)).isEqualTo("pass");
        assertThat(MockAgentExecutor.decision("[[mock:gate=rework,pass]]", 7)).isEqualTo("pass");
        assertThat(MockAgentExecutor.decision("no directives", 1)).isEqualTo("pass");
        assertThat(MockAgentExecutor.directives("[[mock:finding=a]] x [[mock:finding=b]]", "finding"))
                .containsExactly("a", "b");
    }

    private Sandbox sandbox() throws Exception {
        return new LocalSandboxProvider(LocalSandboxOptions.defaults(root.resolve("ws"))).acquire("run-1");
    }

    private AgentInvocation invocation(ExecutorDefinition definition, String prompt, String resume) {
        return new AgentInvocation(
                "run-1",
                "node",
                1,
                "inv-1",
                definition,
                AgentDefinition.builder("dev").prompt("p").build(),
                prompt,
                resume,
                null,
                List.of(),
                null);
    }

    private Path fakeCli(String name, String body) throws Exception {
        Path file = root.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body);
        file.toFile().setExecutable(true);
        return file;
    }
}
