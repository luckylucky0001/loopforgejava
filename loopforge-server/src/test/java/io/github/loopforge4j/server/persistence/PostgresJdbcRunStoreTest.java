package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.core.store.RunStoreContract;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Store contract against a real PostgreSQL. Runs when {@code LOOPFORGE_TEST_POSTGRES_URL} is set (CI
 * provides a service container); each test migrates its own schema.
 */
@EnabledIfEnvironmentVariable(named = "LOOPFORGE_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class PostgresJdbcRunStoreTest extends RunStoreContract {

    private JdbcRunStore store;

    @BeforeEach
    void setUp() {
        String schema = "t" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("LOOPFORGE_TEST_POSTGRES_URL");
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        dataSource.setUser(System.getenv().getOrDefault("LOOPFORGE_TEST_POSTGRES_USER", "loopforge"));
        dataSource.setPassword(System.getenv().getOrDefault("LOOPFORGE_TEST_POSTGRES_PASSWORD", ""));
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        store = new JdbcRunStore(
                JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.systemUTC());
    }

    @Override
    protected RunStore store() {
        return store;
    }
}
