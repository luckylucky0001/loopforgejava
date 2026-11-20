package io.github.loopforge4j.core.runtime;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cooperative cancellation of one node execution. The engine never interrupts worker threads (an
 * interrupt closes pooled JDBC sockets and H2 file channels on virtual threads); instead it cancels
 * the token, whose hooks kill the running process tree, and blocking waits observe the flag.
 */
public final class Cancellation {

    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();
    // A lock/condition rather than Object.wait: on JDK 21 a virtual thread waiting on a monitor pins
    // its carrier, and many sleeping sub-SOP nodes would starve the scheduler pool.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition signal = lock.newCondition();
    private volatile boolean cancelled;

    public static Cancellation create() {
        return new Cancellation();
    }

    public void cancel() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        hooks.forEach(Runnable::run);
        lock.lock();
        try {
            signal.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Registers a hook that runs on cancellation (immediately when already cancelled). */
    public Registration onCancel(Runnable hook) {
        hooks.add(hook);
        if (cancelled) {
            hook.run();
        }
        return () -> hooks.remove(hook);
    }

    public void check() throws CancelledException {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** Sleeps up to {@code duration}; returns early with an exception when cancelled. */
    public void sleep(Duration duration) throws CancelledException {
        long remaining = duration.toNanos();
        lock.lock();
        try {
            while (!cancelled && remaining > 0) {
                remaining = signal.awaitNanos(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } finally {
            lock.unlock();
        }
        check();
    }

    /** Handle to unregister a hook. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
