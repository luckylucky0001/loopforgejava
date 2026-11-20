package io.github.loopforge4j.server.config;

import io.github.loopforge4j.core.engine.RunEngine;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Re-attaches runs interrupted by the previous shutdown once the application is ready. */
@Component
@Order(100)
class EngineLifecycle implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EngineLifecycle.class);

    private final RunEngine engine;
    private final LoopForgeProperties properties;

    EngineLifecycle(RunEngine engine, LoopForgeProperties properties) {
        this.engine = engine;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.engine().recoverOnStartup()) {
            return;
        }
        List<String> recovered = engine.recoverInterruptedRuns();
        if (!recovered.isEmpty()) {
            log.info("recovered {} interrupted run(s): {}", recovered.size(), recovered);
        }
    }
}
