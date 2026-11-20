package io.github.loopforge4j.server.delivery;

import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.store.RunQuery;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Merge detection: periodically refreshes open pull requests of succeeded runs. */
@Component
class DeliveryRefresher {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRefresher.class);

    private final RunEngine engine;

    DeliveryRefresher(RunEngine engine) {
        this.engine = engine;
    }

    @Scheduled(
            initialDelayString = "${loopforge.github.refresh-interval:5m}",
            fixedDelayString = "${loopforge.github.refresh-interval:5m}")
    void refreshOpenDeliveries() {
        for (Run run : engine.list(new RunQuery(Set.of(RunStatus.SUCCEEDED), null, null, 500))) {
            if (run.deliveries().stream().anyMatch(PullRequest::open)) {
                try {
                    engine.refreshDeliveries(run.id());
                } catch (RuntimeException e) {
                    log.warn("refreshing deliveries of {} failed: {}", run.id(), e.toString());
                }
            }
        }
    }
}
