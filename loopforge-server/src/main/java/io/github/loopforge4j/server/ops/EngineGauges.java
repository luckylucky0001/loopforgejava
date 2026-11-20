package io.github.loopforge4j.server.ops;

import io.github.loopforge4j.core.engine.RunEngine;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Gauges read from the engine on scrape. */
@Component
class EngineGauges {

    EngineGauges(RunEngine engine, MeterRegistry registry) {
        Gauge.builder("loopforge.runs.active", engine, RunEngine::activeRunCount)
                .description("runs executed by this instance")
                .register(registry);
    }
}
