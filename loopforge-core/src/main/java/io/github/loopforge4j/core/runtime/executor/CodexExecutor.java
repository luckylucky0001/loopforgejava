package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.runtime.EventSink;
import java.util.ArrayList;
import java.util.List;

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
        // `resume` is a subcommand of `codex exec`; every exec option must precede it.
        if (invocation.resumeSessionId() != null
                && !invocation.resumeSessionId().isBlank()) {
            argv.add("resume");
            argv.add(invocation.resumeSessionId());
        }
        return argv;
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
