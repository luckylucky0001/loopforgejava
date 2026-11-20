package io.github.loopforge4j.core.engine;

/** The referenced run, workflow or agent does not exist. */
public class RunNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RunNotFoundException(String message) {
        super(message);
    }
}
