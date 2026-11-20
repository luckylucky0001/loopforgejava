package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.store.RunStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Batches executor telemetry per run (upstream {@code telemetry batching}): a streaming CLI emits many
 * small events, and one transaction per event dominated store load. Events are flushed every {@link
 * #interval}, when a run's batch is full, before any state change of the run (so the event log keeps
 * its order) and before reads.
 */
final class TelemetryBuffer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBuffer.class);
    static final int MAX_BATCH = 200;
    /** Bound per run: a store outage must not exhaust memory; the oldest telemetry is dropped. */
    static final int MAX_PENDING = 10_000;

    private final RunStore store;
    private final BiConsumer<String, List<RunEvent>> listener;
    private final Duration interval;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Thread flusher;
    private volatile boolean stopped;

    private static final class Pending {
        final ReentrantLock lock = new ReentrantLock();
        List<RunEvent> events = new ArrayList<>();
    }

    TelemetryBuffer(RunStore store, Duration interval, BiConsumer<String, List<RunEvent>> listener) {
        this.store = store;
        this.interval = interval;
        this.listener = listener;
        this.flusher = interval.isZero()
                ? null
                : Thread.ofVirtual().name("telemetry-flusher").start(this::loop);
    }

    void add(String runId, RunEvent event) {
        if (flusher == null || stopped) {
            write(runId, List.of(event));
            return;
        }
        boolean full;
        while (true) {
            Pending p = pending.computeIfAbsent(runId, id -> new Pending());
            p.lock.lock();
            try {
                // a flush may have retired this buffer between lookup and lock: use the current one
                if (pending.get(runId) != p) {
                    continue;
                }
                if (p.events.size() >= MAX_PENDING) {
                    p.events.removeFirst();
                }
                p.events.add(event);
                full = p.events.size() >= MAX_BATCH;
                break;
            } finally {
                p.lock.unlock();
            }
        }
        if (full) {
            flush(runId);
        }
    }

    /** Writes the run's pending events; the per-run lock keeps concurrent flushes in order. */
    void flush(String runId) {
        Pending p = pending.get(runId);
        if (p == null) {
            return;
        }
        p.lock.lock();
        try {
            if (p.events.isEmpty()) {
                pending.remove(runId, p);
                return;
            }
            List<RunEvent> batch = p.events;
            p.events = new ArrayList<>();
            try {
                write(runId, batch);
            } catch (RuntimeException e) {
                // keep them for the next attempt, ahead of newer telemetry
                batch.addAll(p.events);
                p.events = batch;
                throw e;
            }
        } finally {
            p.lock.unlock();
        }
    }

    void flushAll() {
        for (String runId : List.copyOf(pending.keySet())) {
            try {
                flush(runId);
            } catch (RuntimeException e) {
                if (gone(runId)) {
                    // deleted (by a person or retention) while telemetry was buffered: nothing to keep
                    discard(runId);
                    continue;
                }
                log.warn("cannot flush telemetry of run {}: {}", runId, e.toString());
            }
        }
    }

    /** Runs with buffered telemetry (tests). */
    int pendingRuns() {
        return pending.size();
    }

    /** Drops the run's buffered telemetry (the run is being deleted). */
    void discard(String runId) {
        Pending p = pending.get(runId);
        if (p == null) {
            return;
        }
        p.lock.lock();
        try {
            p.events = new ArrayList<>();
            pending.remove(runId, p);
        } finally {
            p.lock.unlock();
        }
    }

    private boolean gone(String runId) {
        try {
            return store.find(runId).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    void close() {
        stopped = true;
        if (flusher != null) {
            flusher.interrupt();
            try {
                flusher.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushAll();
    }

    private void loop() {
        while (!stopped) {
            try {
                Thread.sleep(interval.toMillis());
            } catch (InterruptedException e) {
                return;
            }
            flushAll();
        }
    }

    private void write(String runId, List<RunEvent> batch) {
        List<RunEvent> stored = store.appendEvents(runId, batch);
        if (!stored.isEmpty()) {
            listener.accept(runId, stored);
        }
    }
}
