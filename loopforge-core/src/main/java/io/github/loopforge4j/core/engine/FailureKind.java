package io.github.loopforge4j.core.engine;

/**
 * Typed failure classification. Upstream matches error strings ("verification ", "max turns"); the
 * port keeps the same routing rules on an explicit enum.
 */
public enum FailureKind {
    /** Executor process or protocol failure; retried within the attempt budget. */
    EXECUTOR,
    /** A script node exited non-zero; may be routed to its rework target. */
    SCRIPT,
    /** A command verification gate failed; never retried blindly, may be routed upstream. */
    VERIFICATION,
    /** Declared artifacts are missing or invalid. */
    ARTIFACT_CONTRACT,
    /** The agent itself submitted rework/fail/human or never submitted. */
    GATE_DECISION,
    /** The executor ran out of turns; a human decides whether to retry. */
    MAX_TURNS,
    /** Missing agent/executor/sandbox: retrying cannot help. */
    CONFIGURATION,
    /** A child SOP run failed or was cancelled. */
    CHILD_RUN
}
