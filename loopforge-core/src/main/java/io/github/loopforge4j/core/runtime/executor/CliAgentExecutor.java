package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.runtime.sandbox.CommandSpec;
import io.github.loopforge4j.core.runtime.sandbox.ExecResult;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    public AgentResult execute(AgentInvocation invocation, Sandbox sandbox, EventSink sink, Cancellation cancellation)
            throws ExecutorException, IOException, CancelledException {
        writePromptFile(sandbox.workspace(), invocation);
        // The prompt always travels on stdin as UTF-8: argv is encoded with the JVM's native charset
        // (ASCII under a POSIX locale, which mangles Chinese task books), is limited to 128 KiB per
        // argument on Linux and is visible to every local user through ps.
        List<String> argv = new ArrayList<>(arguments(invocation));
        if (stdinPromptMarker() != null) {
            argv.add(stdinPromptMarker());
        }
        String stdin = invocation.prompt();
        Map<String, String> env = new HashMap<>(environment(invocation, sandbox));
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
        try {
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

    private static void writePromptFile(Path workspace, AgentInvocation invocation) throws IOException {
        Path dir = workspace.resolve(LocalSandbox.CONTROL_DIR).resolve("prompts");
        Files.createDirectories(dir);
        String name = invocation.invocationId().replaceAll("[^A-Za-z0-9._-]", "_") + ".md";
        Files.writeString(dir.resolve(name), invocation.prompt(), StandardCharsets.UTF_8);
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
