package io.github.loopforge4j.core.runtime;

/** The current node execution was cancelled (pause, cancel, rework of a running branch, shutdown). */
public class CancelledException extends Exception {

    private static final long serialVersionUID = 1L;

    public CancelledException() {
        super("execution cancelled");
    }
}
