package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.engine.RunListener;
import io.github.loopforge4j.core.store.RunStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events per run. Engine callbacks only signal a subscriber; each subscriber has its own
 * virtual thread that reads fresh state from the store, so a slow client never blocks execution and
 * bursts are coalesced (upstream "slow clients receive the newest state").
 */
@Component
class RunStreamBroadcaster implements RunListener, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunStreamBroadcaster.class);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    static final int MAX_PER_RUN = 32;
    static final int MAX_TOTAL = 1_000;

    private final RunStore runs;
    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger total = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean running;

    RunStreamBroadcaster(RunStore runs) {
        this.runs = runs;
    }

    SseEmitter subscribe(String runId, long afterSequence) {
        if (!running) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "server is shutting down");
        }
        Set<Subscription> forRun = subscriptions.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet());
        // every subscriber reads the store on each change; bound the fan-out so streams cannot starve
        // the engine's connection pool
        if (forRun.size() >= MAX_PER_RUN || total.incrementAndGet() > MAX_TOTAL) {
            if (forRun.size() < MAX_PER_RUN) {
                total.decrementAndGet();
            }
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many open streams");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscription subscription = new Subscription(runId, emitter, afterSequence);
        forRun.add(subscription);
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(e -> subscription.close());
        Thread.ofVirtual().name("sse-" + runId).start(subscription::pump);
        return emitter;
    }

    @Override
    public void runChanged(Run run) {
        signal(run.id());
    }

    @Override
    public void eventsAppended(String runId, List<RunEvent> events) {
        signal(runId);
    }

    /**
     * Completes every stream before the web server's graceful shutdown starts (this lifecycle has a
     * higher phase), otherwise open streams hold shutdown until its timeout and the engine never
     * gets to park its runs.
     */
    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        subscriptions.values().forEach(set -> List.copyOf(set).forEach(Subscription::shutdown));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    int subscriberCount(String runId) {
        return subscriptions.getOrDefault(runId, Set.of()).size();
    }

    private void signal(String runId) {
        Set<Subscription> set = subscriptions.get(runId);
        if (set != null) {
            set.forEach(Subscription::signal);
        }
    }

    private final class Subscription {
        private final String runId;
        private final SseEmitter emitter;
        private final Semaphore dirty = new Semaphore(1);
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        private long lastSequence;
        private long lastRevision = -1;

        Subscription(String runId, SseEmitter emitter, long afterSequence) {
            this.runId = runId;
            this.emitter = emitter;
            this.lastSequence = afterSequence;
        }

        void signal() {
            if (dirty.availablePermits() == 0) {
                dirty.release();
            }
        }

        void shutdown() {
            close();
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                // already completed
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            total.decrementAndGet();
            Set<Subscription> set = subscriptions.get(runId);
            if (set != null) {
                set.remove(this);
                if (set.isEmpty()) {
                    subscriptions.remove(runId, set);
                }
            }
            dirty.release();
        }

        void pump() {
            try {
                while (!closed.get()) {
                    if (!dirty.tryAcquire(HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS)) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        continue;
                    }
                    dirty.drainPermits();
                    if (closed.get() || !flush()) {
                        break;
                    }
                }
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE client of run {} went away: {}", runId, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                close();
            }
        }

        /** Sends new events and the latest snapshot; returns false once the run is terminal. */
        private boolean flush() throws IOException {
            List<RunEvent> events;
            do {
                events = runs.events(runId, lastSequence, 500);
                for (RunEvent event : events) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name("event")
                            .data(event));
                    lastSequence = event.sequence();
                }
            } while (events.size() == 500);
            Run run = runs.find(runId).orElse(null);
            if (run == null) {
                return false;
            }
            if (run.revision() != lastRevision) {
                lastRevision = run.revision();
                emitter.send(SseEmitter.event().name("run").data(RunSummary.of(run)));
            }
            return !run.status().terminal()
                    || !runs.events(runId, lastSequence, 1).isEmpty();
        }
    }
}
