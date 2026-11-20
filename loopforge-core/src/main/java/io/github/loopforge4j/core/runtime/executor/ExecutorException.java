package io.github.loopforge4j.core.runtime.executor;

/** The executor could not complete the invocation (process error, protocol error, max turns, ...). */
public class ExecutorException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient AgentResult partial;
    private final boolean maxTurns;

    public ExecutorException(String message, AgentResult partial) {
        super(message);
        this.partial = partial;
        this.maxTurns = partial != null && partial.maxTurns();
    }

    /** What the executor produced before failing; may be {@code null}. */
    public AgentResult partial() {
        return partial;
    }

    public boolean maxTurns() {
        return maxTurns;
    }
}
