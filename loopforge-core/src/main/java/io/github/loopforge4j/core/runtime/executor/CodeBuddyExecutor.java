package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.runtime.EventSink;
import java.util.ArrayList;
import java.util.List;

/** CodeBuddy Code; speaks the same stream-json protocol as Claude Code. */
public final class CodeBuddyExecutor extends CliAgentExecutor {

    public static final String KIND = "codebuddy";

    public CodeBuddyExecutor(CredentialProvider credentials) {
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
        dropFlag(argv, "--resume");
        addModelArgs(argv, invocation, true);
        if (invocation.compiled() != null) {
            // --tools is a built-in allowlist that CodeBuddy also applies to MCP tools
            dropFlag(argv, "--tools");
        }
        addCompiledArgs(argv, invocation.compiled(), "--plugin-dir");
        if (invocation.resumeSessionId() != null
                && !invocation.resumeSessionId().isBlank()) {
            argv.add("--resume");
            argv.add(invocation.resumeSessionId());
        }
        return argv;
    }

    @Override
    protected StreamParser parser(EventSink sink) {
        return new AnthropicStreamParser(KIND, sink);
    }
}
