package io.github.loopforge4j.core.runtime.sandbox;

import java.time.Duration;

/**
 * Outcome of a finished process.
 *
 * @param output tail of the combined stdout/stderr (bounded, see {@link OutputBuffer})
 */
public record ExecResult(int exitCode, String output, boolean timedOut, Duration duration) {

    public boolean succeeded() {
        return exitCode == 0 && !timedOut;
    }
}
