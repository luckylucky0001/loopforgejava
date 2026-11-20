package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.core.store.RunStoreContract;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs the shared store contract against the JDBC adapter on a migrated H2 database. */
class JdbcRunStoreTest extends RunStoreContract {

    private JdbcRunStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure()
                .dataSource(dataSource)
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
