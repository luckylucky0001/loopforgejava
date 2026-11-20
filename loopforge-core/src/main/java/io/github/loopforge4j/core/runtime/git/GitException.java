package io.github.loopforge4j.core.runtime.git;

/** A git operation failed; the message never contains credentials. */
public class GitException extends Exception {

    private static final long serialVersionUID = 1L;

    public GitException(String message) {
        super(message);
    }
}
