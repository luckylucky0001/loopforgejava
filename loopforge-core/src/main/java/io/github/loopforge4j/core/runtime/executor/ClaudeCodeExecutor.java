package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Claude Code in print mode with stream-json output (upstream {@code claudeProvider}). */
public final class ClaudeCodeExecutor extends CliAgentExecutor {

    public static final String KIND = "claude-code";

    public ClaudeCodeExecutor(CredentialProvider credentials) {
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
        // An unattended node must write files and its gate result without interactive approval.
        dropFlag(argv, "--permission-mode");
        argv.add("--permission-mode");
        argv.add("bypassPermissions");
        // Print mode only emits stream-json together with --verbose.
        if ((argv.contains("-p") || argv.contains("--print")) && !argv.contains("--verbose")) {
            argv.add("--verbose");
        }
        // Only the role's own configuration: no repository settings (hooks!) or MCP servers.
        dropFlag(argv, "--setting-sources");
        argv.add("--setting-sources");
        argv.add("user");
        addCompiledArgs(argv, invocation.compiled(), "--plugin-dir");
        if (invocation.resumeSessionId() != null
                && !invocation.resumeSessionId().isBlank()) {
            argv.add("--resume");
            argv.add(invocation.resumeSessionId());
        }
        return argv;
    }

    @Override
    protected Map<String, String> environment(AgentInvocation invocation, Sandbox sandbox) {
        // The sandbox is the isolation boundary; Claude Code refuses bypassPermissions for root otherwise.
        return Map.of("IS_SANDBOX", "1");
    }

    @Override
    protected StreamParser parser(EventSink sink) {
        return new AnthropicStreamParser(KIND, sink);
    }
}
