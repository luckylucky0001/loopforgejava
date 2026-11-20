package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;

/** Adapter for one executor kind (claude-code, codex, codebuddy, mock, ...). */
public interface AgentExecutor {

    String kind();

    /** Whether the role's MCP servers, skills and hooks are compiled into workspace files for it. */
    default boolean materializes() {
        return false;
    }

    /**
     * Runs the agent to completion inside the sandbox workspace.
     *
     * @throws CancelledException when the node is cancelled; the process tree is already killed
     */
    AgentResult execute(AgentInvocation invocation, Sandbox sandbox, EventSink sink, Cancellation cancellation)
            throws ExecutorException, IOException, CancelledException;
}
