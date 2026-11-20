package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.CatalogStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link CatalogStore} on a relational database; each definition is one JSON document. */
@Repository
public class JdbcCatalogStore implements CatalogStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JdbcCatalogStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public Optional<WorkflowDefinition> workflow(String id) {
        return jdbc.sql("SELECT payload FROM workflows WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> Json.read(rs.getString("payload"), WorkflowDefinition.class))
                .optional();
    }

    @Override
    public List<WorkflowDefinition> workflows() {
        return jdbc.sql("SELECT payload FROM workflows ORDER BY id")
                .query((rs, row) -> Json.read(rs.getString("payload"), WorkflowDefinition.class))
                .list();
    }

    @Override
    public WorkflowDefinition saveWorkflow(WorkflowDefinition definition) {
        return tx.execute(status -> {
            Optional<WorkflowDefinition> previous = workflow(definition.id());
            Instant now = clock.instant();
            WorkflowDefinition stamped = new WorkflowDefinition(
                            definition.id(),
                            definition.name(),
                            definition.version(),
                            previous.map(WorkflowDefinition::revision).orElse(0),
                            definition.status(),
                            definition.projectId(),
                            definition.owner(),
                            definition.tags(),
                            definition.description(),
                            definition.sandboxProfileId(),
                            definition.nodes(),
                            definition.edges(),
                            previous.map(WorkflowDefinition::createdAt).orElse(null),
                            null)
                    .nextRevision(now);
            int updated = jdbc.sql("""
                    UPDATE workflows SET name = :name, status = :status, revision = :revision, payload = :payload,
                        updated_at = :updatedAt WHERE id = :id""")
                    .param("name", stamped.name())
                    .param("status", stamped.status().wire())
                    .param("revision", stamped.revision())
                    .param("payload", Json.write(stamped))
                    .param("updatedAt", Timestamps.of(now))
                    .param("id", stamped.id())
                    .update();
            if (updated == 0) {
                jdbc.sql("""
                        INSERT INTO workflows (id, name, status, revision, payload, created_at, updated_at)
                        VALUES (:id, :name, :status, :revision, :payload, :createdAt, :updatedAt)""")
                        .param("id", stamped.id())
                        .param("name", stamped.name())
                        .param("status", stamped.status().wire())
                        .param("revision", stamped.revision())
                        .param("payload", Json.write(stamped))
                        .param("createdAt", Timestamps.of(stamped.createdAt()))
                        .param("updatedAt", Timestamps.of(now))
                        .update();
            }
            return stamped;
        });
    }

    @Override
    public boolean deleteWorkflow(String id) {
        return jdbc.sql("DELETE FROM workflows WHERE id = :id").param("id", id).update() > 0;
    }

    @Override
    public Optional<AgentDefinition> agent(String id) {
        return jdbc.sql("SELECT payload FROM agents WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> Json.read(rs.getString("payload"), AgentDefinition.class))
                .optional();
    }

    @Override
    public List<AgentDefinition> agents() {
        return jdbc.sql("SELECT payload FROM agents ORDER BY id")
                .query((rs, row) -> Json.read(rs.getString("payload"), AgentDefinition.class))
                .list();
    }

    @Override
    public AgentDefinition saveAgent(AgentDefinition definition) {
        return tx.execute(status -> {
            AgentDefinition stamped = definition.withUpdatedAt(clock.instant());
            int updated = jdbc.sql(
                            "UPDATE agents SET name = :name, payload = :payload, updated_at = :updatedAt WHERE id = :id")
                    .param("name", stamped.name())
                    .param("payload", Json.write(stamped))
                    .param("updatedAt", Timestamps.of(stamped.updatedAt()))
                    .param("id", stamped.id())
                    .update();
            if (updated == 0) {
                jdbc.sql("INSERT INTO agents (id, name, payload, updated_at) VALUES (:id, :name, :payload, :updatedAt)")
                        .param("id", stamped.id())
                        .param("name", stamped.name())
                        .param("payload", Json.write(stamped))
                        .param("updatedAt", Timestamps.of(stamped.updatedAt()))
                        .update();
            }
            return stamped;
        });
    }

    @Override
    public boolean deleteAgent(String id) {
        return jdbc.sql("DELETE FROM agents WHERE id = :id").param("id", id).update() > 0;
    }
}
