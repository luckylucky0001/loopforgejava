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
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-sent events per run. Engine callbacks only signal a subscriber; each subscriber has its own
 * virtual thread that reads fresh state from the store, so a slow client never blocks execution and
 * bursts are coalesced (upstream "slow clients receive the newest state").
 */
@Component
class RunStreamBroadcaster implements RunListener {

    private static final Logger log = LoggerFactory.getLogger(RunStreamBroadcaster.class);
    private static final Duration HEARTBEAT = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final RunStore runs;
    private final Map<String, Set<Subscription>> subscriptions = new ConcurrentHashMap<>();

    RunStreamBroadcaster(RunStore runs) {
        this.runs = runs;
    }

    SseEmitter subscribe(String runId, long afterSequence) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        Subscription subscription = new Subscription(runId, emitter, afterSequence);
        subscriptions.computeIfAbsent(runId, k -> ConcurrentHashMap.newKeySet()).add(subscription);
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
        private volatile boolean closed;
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

        void close() {
            closed = true;
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
                while (!closed) {
                    if (!dirty.tryAcquire(HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS)) {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        continue;
                    }
                    dirty.drainPermits();
                    if (closed || !flush()) {
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
