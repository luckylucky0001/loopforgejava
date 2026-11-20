package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Opt-in import of the bundled demo catalog ({@code classpath:catalog/*.json}) on the first start
 * only, i.e. while the catalog is empty. Definitions an operator later edits or deletes therefore
 * stay that way across restarts. Disable with {@code loopforge.demo-catalog=false}.
 */
@Component
@Order(10)
class DemoCatalogImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoCatalogImporter.class);

    private final CatalogService catalog;
    private final io.github.loopforge4j.core.store.CatalogStore store;
    private final LoopForgeProperties properties;

    DemoCatalogImporter(
            CatalogService catalog,
            io.github.loopforge4j.core.store.CatalogStore store,
            LoopForgeProperties properties) {
        this.catalog = catalog;
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.demoCatalog()) {
            return;
        }
        if (!store.workflows().isEmpty() || !store.agents().isEmpty()) {
            log.debug("catalog is not empty; skipping the demo catalog");
            return;
        }
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:catalog/*.json");
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                CatalogBundle bundle =
                        Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8), CatalogBundle.class);
                List<String> written = catalog.importBundle(bundle, false);
                if (!written.isEmpty()) {
                    log.info("imported demo catalog {}: {}", resource.getFilename(), written);
                }
            }
        }
    }
}
