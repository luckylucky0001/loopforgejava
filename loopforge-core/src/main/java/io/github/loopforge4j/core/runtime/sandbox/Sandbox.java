package io.github.loopforge4j.core.runtime.sandbox;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Isolation boundary of one run. All nodes of a run share its workspace so later steps see earlier
 * files. Implementations must terminate the whole process tree when the cancellation fires.
 */
public interface Sandbox {

    String id();

    /** Driver name, e.g. {@code local} or {@code docker}. */
    String driver();

    /** Absolute workspace root on the engine host. */
    Path workspace();

    /**
     * Private HOME of processes launched with this label, or {@code null} when processes share the
     * server's HOME (CLI configuration must then not be written there).
     */
    default Path privateHome(String label) {
        return null;
    }

    /**
     * Runs a command to completion.
     *
     * @throws CancelledException when {@code cancellation} fired; the process tree is killed first
     */
    ExecResult exec(CommandSpec command, LineListener stdout, Cancellation cancellation)
            throws IOException, CancelledException;
}
