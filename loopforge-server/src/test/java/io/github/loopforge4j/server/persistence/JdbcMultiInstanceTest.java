package io.github.loopforge4j.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.engine.EngineSettings;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Two engines on one database: leases and the instance registry through the JDBC adapters. */
class JdbcMultiInstanceTest {

    @TempDir
    Path root;

    @Test
    void secondInstanceWaitsForTheLeaseAndContinuesAfterAHandOver() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcRunStore runs = new JdbcRunStore(jdbc, tx, Clock.systemUTC());
        JdbcCatalogStore catalog = new JdbcCatalogStore(jdbc, tx, Clock.systemUTC());
        JdbcInstanceRegistry registry = new JdbcInstanceRegistry(jdbc, tx);
        catalog.saveWorkflow(WorkflowDefinition.builder("slow")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                        .script(
                                "for i in $(seq 1 25); do echo $i >> \"$LOOPFORGE_WORKSPACE/../beats.txt\"; sleep 0.2; done")
                        .build())
                .build());

        RunEngine a = engine("a", runs, catalog, registry, false);
        RunEngine b = engine("b", runs, catalog, registry, true);
        try {
            Run run = a.start("slow", "", ExecutionContext.EMPTY);
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(root.resolve("ws/beats.txt")));
            assertThat(registry.instances()).extracting(i -> i.id()).contains("jdbc-a", "jdbc-b");
            Thread.sleep(1_200);
            assertThat(b.scheduling(run.id())).isFalse();
            assertThat(runs.find(run.id()).orElseThrow().ownerInstanceId()).isEqualTo("jdbc-a");

            a.close();
            assertThat(registry.find("jdbc-a")).isEmpty();
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> runs.find(run.id()).orElseThrow().status() == RunStatus.SUCCEEDED);
            assertThat(b.events(run.id(), 0, 0)).extracting(e -> e.type()).contains("run.parked", "run.recovered");
        } finally {
            a.close();
            b.close();
        }
    }

    private RunEngine engine(
            String name, JdbcRunStore runs, JdbcCatalogStore catalog, JdbcInstanceRegistry registry, boolean claim) {
        return RunEngine.builder()
                .runStore(runs)
                .catalogStore(catalog)
                .executors(ExecutorRegistry.withBuiltIns(CredentialProvider.NONE))
                .sandbox(new LocalSandboxProvider(new LocalSandboxOptions(
                        root.resolve("ws"), LocalSandboxOptions.DEFAULT_PASSTHROUGH, true, Duration.ofMinutes(2))))
                .settings(
                        new EngineSettings("jdbc-" + name, "mock", Duration.ofMinutes(2), Duration.ofMillis(25), false)
                                .withLease(Duration.ofMillis(900), Duration.ofMillis(150)))
                .instances(registry)
                .claimOrphans(claim)
                .build();
    }
}
