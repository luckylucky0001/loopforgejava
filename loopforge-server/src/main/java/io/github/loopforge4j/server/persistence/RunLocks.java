package io.github.loopforge4j.server.persistence;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Striped in-process locks serializing writes per run. Optimistic revisions protect against other
 * instances; the local lock keeps one instance's commits (and therefore event sequences of a run) in
 * order and avoids pointless conflict retries.
 */
final class RunLocks {

    private final ReentrantLock[] stripes;

    RunLocks(int size) {
        stripes = new ReentrantLock[size];
        for (int i = 0; i < size; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    ReentrantLock forRun(String runId) {
        return stripes[Math.floorMod(runId.hashCode(), stripes.length)];
    }
}
