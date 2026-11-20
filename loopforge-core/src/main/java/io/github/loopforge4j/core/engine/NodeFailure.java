package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.FailurePolicy;

/**
 * Failure of one node attempt, carrying the routing decision chosen by the node logic.
 *
 * @param action requested routing ({@code null} lets the node defaults decide)
 */
final class NodeFailure extends Exception {

    private static final long serialVersionUID = 1L;

    private final FailureKind kind;
    private final transient FailurePolicy action;

    NodeFailure(FailureKind kind, String message, FailurePolicy action) {
        super(message);
        this.kind = kind;
        this.action = action;
    }

    NodeFailure(FailureKind kind, String message) {
        this(kind, message, null);
    }

    FailureKind kind() {
        return kind;
    }

    FailurePolicy action() {
        return action;
    }
}
