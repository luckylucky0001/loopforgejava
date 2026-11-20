package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.runtime.materialize.CompiledAgent;
import io.github.loopforge4j.core.runtime.sandbox.CommandSpec;
import io.github.loopforge4j.core.runtime.sandbox.ExecResult;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class of executors that launch an agent CLI as a process and parse its line protocol. Ported
 * from upstream {@code baseProvider}/{@code ExecutorRunner}.
 */
public abstract class CliAgentExecutor implements AgentExecutor {

    private final CredentialProvider credentials;

    protected CliAgentExecutor(CredentialProvider credentials) {
        this.credentials = credentials == null ? CredentialProvider.NONE : credentials;
    }

    /** Builds argv without the prompt; the prompt is appended or sent on stdin by the base class. */
    protected abstract List<String> arguments(AgentInvocation invocation);

    /** Argument builder with access to the sandbox (e.g. the CLI's private HOME). */
    protected List<String> arguments(AgentInvocation invocation, Sandbox sandbox) {
        return arguments(invocation);
    }

    protected abstract StreamParser parser(EventSink sink);

    /** Extra non-secret environment for the CLI process. */
    protected Map<String, String> environment(AgentInvocation invocation, Sandbox sandbox) {
        return Map.of();
    }

    /**
     * Positional argument that makes the CLI read its prompt from stdin, or {@code null} when the CLI
     * does so by itself (Claude Code and CodeBuddy print mode).
     */
    protected String stdinPromptMarker() {
        return null;
    }

    @Override
    public boolean materializes() {
        return true;
    }

    /**
     * Task prompt with the role's system instructions ahead of it (upstream {@code
     * AgentCLITaskPrompt}); a resumed session already has them.
     */
    protected static String taskPrompt(AgentInvocation invocation) {
        CompiledAgent compiled = invocation.compiled();
        boolean resumed = invocation.resumeSessionId() != null
                && !invocation.resumeSessionId().isBlank();
        if (compiled == null || resumed || compiled.systemInstructions().isBlank()) {
            return invocation.prompt();
        }
        return "<system_instructions>\n" + compiled.systemInstructions().strip() + "\n</system_instructions>\n\n"
                + invocation.prompt();
    }

    /** Prepares CLI-specific files (e.g. MCP configuration in the private HOME) before launch. */
    protected void prepare(AgentInvocation invocation, Sandbox sandbox, EventSink sink) throws IOException {}

    @Override
    public AgentResult execute(AgentInvocation invocation, Sandbox sandbox, EventSink sink, Cancellation cancellation)
            throws ExecutorException, IOException, CancelledException {
        String prompt = taskPrompt(invocation);
        writePromptFile(sandbox.workspace(), invocation.invocationId(), prompt);
        prepare(invocation, sandbox, sink);
        // The prompt always travels on stdin as UTF-8: argv is encoded with the JVM's native charset
        // (ASCII under a POSIX locale, which mangles Chinese task books), is limited to 128 KiB per
        // argument on Linux and is visible to every local user through ps.
        List<String> argv = new ArrayList<>(arguments(invocation, sandbox));
        if (stdinPromptMarker() != null) {
            argv.add(stdinPromptMarker());
        }
        String stdin = stdinPrompt(invocation, prompt);
        Map<String, String> env = new HashMap<>(invocation.environment());
        if (invocation.compiled() != null) {
            env.putAll(invocation.compiled().environment());
        }
        env.putAll(environment(invocation, sandbox));
        env.putAll(credentials.environmentFor(kind()));
        CommandSpec spec = new CommandSpec(argv, null, env, stdin, invocation.timeout(), kind());
        StreamParser parser = parser(sink);
        sink.emit(new Telemetry(
                "process",
                "process.started",
                "start",
                "info",
                invocation.executor().name(),
                null,
                null,
                null,
                Map.of("binary", argv.getFirst(), "invocationId", invocation.invocationId())));
        ExecResult exec;
        try (HookObserver hooks =
                invocation.compiled() == null || invocation.compiled().hooks().isEmpty()
                        ? null
                        : HookObserver.watch(invocation.compiled().hookOutput(), sink)) {
            exec = sandbox.exec(spec, parser::accept, cancellation);
        } catch (IOException e) {
            throw new ExecutorException("failed to start " + argv.getFirst() + ": " + e.getMessage(), null);
        }
        AgentResult result = parser.result(exec.exitCode());
        sink.emit(new Telemetry(
                "process",
                "process.finished",
                "finish",
                exec.succeeded() ? "info" : "error",
                "exit " + exec.exitCode(),
                result.sessionId(),
                null,
                null,
                Map.of(
                        "exitCode",
                        exec.exitCode(),
                        "durationMs",
                        exec.duration().toMillis())));
        if (exec.timedOut()) {
            throw new ExecutorException("executor timed out after " + invocation.timeout(), result);
        }
        if (result.maxTurns()) {
            throw new ExecutorException("executor reached max turns", result);
        }
        if (exec.exitCode() != 0 && !result.terminal()) {
            throw new ExecutorException(
                    "executor exited with code " + exec.exitCode() + ": " + JsonFields.clip(tail(exec.output()), 2000),
                    result);
        }
        if (result.failed()) {
            throw new ExecutorException(
                    "executor reported an error: " + JsonFields.clip(result.output(), 2000), result);
        }
        return result;
    }

    /** What goes to stdin; CLIs that take the prompt as an argument return {@code null}. */
    protected String stdinPrompt(AgentInvocation invocation, String prompt) {
        return prompt;
    }

    /** Workspace file holding the full task prompt of an invocation. */
    protected static Path promptFile(Path workspace, String invocationId) {
        return workspace
                .resolve(LocalSandbox.CONTROL_DIR)
                .resolve("prompts")
                .resolve(invocationId.replaceAll("[^A-Za-z0-9._-]", "_") + ".md");
    }

    private static void writePromptFile(Path workspace, String invocationId, String prompt) throws IOException {
        new io.github.loopforge4j.core.runtime.ManagedFiles(workspace)
                .write(promptFile(workspace, invocationId), prompt, false);
    }

    private static String tail(String output) {
        return output.length() <= 2000 ? output : output.substring(output.length() - 2000);
    }

    protected static void addModelArgs(List<String> argv, AgentInvocation invocation, boolean effortFlag) {
        String model = invocation.executor().model();
        if (model != null && !model.isBlank() && !"default".equals(model) && !argv.contains("--model")) {
            argv.add("--model");
            argv.add(model);
        }
        String effort = invocation.executor().effort();
        if (effortFlag && effort != null && !effort.isBlank() && !argv.contains("--effort")) {
            argv.add("--effort");
            argv.add(effort);
        }
    }

    /**
     * Claude-style flags for a compiled role: strict MCP allowlist, hook settings and the skills
     * plugin directory.
     */
    protected static void addCompiledArgs(List<String> argv, CompiledAgent compiled, String pluginFlag) {
        if (compiled == null) {
            return;
        }
        dropFlag(argv, "--mcp-config");
        argv.add("--mcp-config");
        argv.add(compiled.mcpConfig().toString());
        if (!argv.contains("--strict-mcp-config")) {
            argv.add("--strict-mcp-config");
        }
        if (compiled.hookSettings() != null) {
            dropFlag(argv, "--settings");
            argv.add("--settings");
            argv.add(compiled.hookSettings().toString());
        }
        if (compiled.hasSkills()) {
            dropFlag(argv, pluginFlag);
            argv.add(pluginFlag);
            argv.add(compiled.agentDir().toString());
        }
    }

    /** Removes {@code flag} and its value from argv. */
    protected static void dropFlag(List<String> argv, String flag) {
        for (int i = 0; i < argv.size(); i++) {
            if (argv.get(i).equals(flag)) {
                argv.remove(i);
                if (i < argv.size()) {
                    argv.remove(i);
                }
                i--;
            } else if (argv.get(i).startsWith(flag + "=")) {
                argv.remove(i);
                i--;
            }
        }
    }
}
