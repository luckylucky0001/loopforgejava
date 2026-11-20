package io.github.loopforge4j.server.ops;

import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Applies {@code loopforge.retention.*} periodically; idempotent, so every instance may run it. */
@Component
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final RunEngine engine;
    private final LoopForgeProperties.Retention retention;

    RetentionJob(RunEngine engine, LoopForgeProperties properties) {
        this.engine = engine;
        this.retention = properties.retention();
    }

    @Scheduled(
            initialDelayString = "${loopforge.retention.interval:1h}",
            fixedDelayString = "${loopforge.retention.interval:1h}")
    void apply() {
        try {
            engine.applyRetention(retention.telemetry(), retention.runs());
        } catch (RuntimeException e) {
            log.warn("retention failed: {}", e.toString());
        }
    }
}
