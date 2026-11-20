package io.github.loopforge4j.core.store;

/** Raised when a run write keeps losing the optimistic-concurrency race. */
public class StaleRunException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleRunException(String runId, long expectedRevision) {
        super("run " + runId + " changed concurrently (expected revision " + expectedRevision + ")");
    }
}
