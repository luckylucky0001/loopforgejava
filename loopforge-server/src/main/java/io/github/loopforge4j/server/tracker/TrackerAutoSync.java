package io.github.loopforge4j.server.tracker;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic pulls of every project with trackers (upstream {@code StartAutoSync}). A lease in the
 * database lets one instance sync at a time.
 */
@Component
class TrackerAutoSync {

    private static final Logger log = LoggerFactory.getLogger(TrackerAutoSync.class);

    private final TrackerService trackers;
    private final TrackerStore store;
    private final Clock clock;
    private final boolean enabled;
    private final long intervalMillis;

    TrackerAutoSync(TrackerService trackers, TrackerStore store, Clock clock, LoopForgeProperties properties) {
        this.trackers = trackers;
        this.store = store;
        this.clock = clock;
        this.enabled = properties.trackers().autoSync();
        this.intervalMillis = properties.trackers().syncInterval().toMillis();
    }

    @Scheduled(
            initialDelayString = "${loopforge.trackers.sync-interval:10m}",
            fixedDelayString = "${loopforge.trackers.sync-interval:10m}")
    void tick() {
        if (!enabled) {
            return;
        }
        long now = clock.millis();
        // hold the lease a little shorter than the interval so the next tick anywhere may take it
        if (!store.acquireLease(now, now + Math.max(1000, intervalMillis - 1000))) {
            return;
        }
        for (Map.Entry<String, TrackerSettings> e : store.all().entrySet()) {
            if (!e.getValue().autoSync() || !e.getValue().anything()) {
                continue;
            }
            try {
                trackers.sync(e.getKey(), "auto-sync");
            } catch (RuntimeException ex) {
                log.warn("auto sync of project {} failed: {}", e.getKey(), ex.getMessage());
            }
        }
    }
}
