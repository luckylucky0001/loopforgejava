package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code codex exec --json} (upstream {@code codexProvider}). */
public final class CodexExecutor extends CliAgentExecutor {

    public static final String KIND = "codex";

    public CodexExecutor(CredentialProvider credentials) {
        super(credentials);
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    protected List<String> arguments(AgentInvocation invocation) {
        List<String> argv = new ArrayList<>();
        argv.add(invocation.executor().binary());
        argv.addAll(invocation.executor().args());
        addModelArgs(argv, invocation, false);
        String effort = invocation.executor().effort();
        if (effort != null && !effort.isBlank() && argv.stream().noneMatch(a -> a.contains("model_reasoning_effort"))) {
            argv.add("-c");
            argv.add("model_reasoning_effort=\"" + effort.replace("\"", "") + "\"");
        }
        if (invocation.compiled() != null) {
            try {
                argv.addAll(CodexMcpArgs.render(invocation.compiled()).args());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            if (!invocation.compiled().hooks().isEmpty() && !argv.contains("--dangerously-bypass-hook-trust")) {
                argv.add("--dangerously-bypass-hook-trust");
            }
        }
        // `resume` is a subcommand of `codex exec`; every exec option must precede it.
        if (invocation.resumeSessionId() != null
                && !invocation.resumeSessionId().isBlank()) {
            argv.add("resume");
            argv.add(invocation.resumeSessionId());
        }
        return argv;
    }

    @Override
    protected Map<String, String> environment(AgentInvocation invocation, Sandbox sandbox) {
        // header values referenced by env_http_headers; deterministic, so arguments() agrees
        return invocation.compiled() == null ? Map.of() : CodexMcpArgs.headerEnvironment(invocation.compiled());
    }

    @Override
    protected String stdinPromptMarker() {
        return "-";
    }

    @Override
    protected StreamParser parser(EventSink sink) {
        return new CodexJsonParser(sink);
    }
}
