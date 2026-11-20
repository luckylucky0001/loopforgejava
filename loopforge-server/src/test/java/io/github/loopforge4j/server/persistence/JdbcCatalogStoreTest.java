package io.github.loopforge4j.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.time.Clock;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCatalogStoreTest {

    private JdbcCatalogStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        store = new JdbcCatalogStore(
                JdbcClient.create(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Clock.systemUTC());
    }

    @Test
    void workflowRevisionsIncreaseAndCreationTimeIsKept() {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .name("v1")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT).script("true").build())
                .build();
        WorkflowDefinition first = store.saveWorkflow(wf);
        WorkflowDefinition second = store.saveWorkflow(WorkflowDefinition.builder("wf")
                .name("v2")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT).script("false").build())
                .build());
        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(store.workflow("wf"))
                .get()
                .extracting(WorkflowDefinition::name)
                .isEqualTo("v2");
        assertThat(store.workflows()).hasSize(1);
        assertThat(store.deleteWorkflow("wf")).isTrue();
        assertThat(store.deleteWorkflow("wf")).isFalse();
    }

    @Test
    void agentsUpsert() {
        store.saveAgent(AgentDefinition.builder("dev").name("A").build());
        store.saveAgent(AgentDefinition.builder("dev").name("B").prompt("p").build());
        assertThat(store.agents())
                .singleElement()
                .extracting(AgentDefinition::name)
                .isEqualTo("B");
        assertThat(store.agent("dev"))
                .get()
                .extracting(AgentDefinition::updatedAt)
                .isNotNull();
        assertThat(store.deleteAgent("dev")).isTrue();
        assertThat(store.agent("dev")).isEmpty();
    }

    @Test
    void repositoriesRoundTripAndAreReplacedInPlace() {
        store.saveRepository(new io.github.loopforge4j.core.domain.RepositoryDefinition(
                "app", "App", null, "https://github.com/acme/app.git", null, null, null, "p1", null));
        store.saveRepository(new io.github.loopforge4j.core.domain.RepositoryDefinition(
                "app", "App 2", null, "https://github.com/acme/app.git", null, "trunk", null, "p1", null));
        assertThat(store.repositories()).hasSize(1);
        assertThat(store.repository("app")).get().satisfies(r -> {
            assertThat(r.name()).isEqualTo("App 2");
            assertThat(r.defaultBranch()).isEqualTo("trunk");
            assertThat(r.githubRepo()).isEqualTo("acme/app");
            assertThat(r.provider()).isEqualTo(io.github.loopforge4j.core.domain.RepositoryProvider.GITHUB);
            assertThat(r.updatedAt()).isNotNull();
        });
        assertThat(store.deleteRepository("app")).isTrue();
        assertThat(store.deleteRepository("app")).isFalse();
        assertThat(store.repository("app")).isEmpty();
    }
}
