package io.github.loopforge4j.core.runtime.sandbox;

/** Observes process starts so a sandbox can record them durably (see {@link ProcessRecords}). */
@FunctionalInterface
public interface ProcessTracker {

    ProcessTracker NONE = process -> () -> {};

    /**
     * Called right after a process started.
     *
     * @return action run once the process and its group have been reaped
     */
    Runnable started(Process process);
}
