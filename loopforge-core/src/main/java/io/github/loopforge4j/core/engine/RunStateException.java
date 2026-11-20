package io.github.loopforge4j.core.engine;

/** The requested operation is not allowed in the current run or node state (HTTP 409). */
public class RunStateException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RunStateException(String message) {
        super(message);
    }
}
