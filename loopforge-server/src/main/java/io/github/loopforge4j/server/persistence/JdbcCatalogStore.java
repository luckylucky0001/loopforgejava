package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.CatalogAsset;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.core.store.StoreException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
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
        return withUpsertRetry(() -> tx.execute(status -> {
            // FOR UPDATE serializes concurrent writers of an existing id, so revisions never get lost;
            // a race on a new id surfaces as a duplicate key and is retried as an update.
            Optional<WorkflowDefinition> previous = jdbc.sql("SELECT payload FROM workflows WHERE id = :id FOR UPDATE")
                    .param("id", definition.id())
                    .query((rs, row) -> Json.read(rs.getString("payload"), WorkflowDefinition.class))
                    .optional();
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
                            null,
                            definition.preview())
                    .nextRevision(now);
            String sql = previous.isPresent() ? """
                    UPDATE workflows SET name = :name, status = :status, revision = :revision, payload = :payload,
                        updated_at = :updatedAt WHERE id = :id""" : """
                    INSERT INTO workflows (id, name, status, revision, payload, created_at, updated_at)
                    VALUES (:id, :name, :status, :revision, :payload, :createdAt, :updatedAt)""";
            var statement = jdbc.sql(sql)
                    .param("id", stamped.id())
                    .param("name", stamped.name())
                    .param("status", stamped.status().wire())
                    .param("revision", stamped.revision())
                    .param("payload", Json.write(stamped))
                    .param("updatedAt", Timestamps.of(now));
            if (previous.isEmpty()) {
                statement = statement.param("createdAt", Timestamps.of(stamped.createdAt()));
            }
            statement.update();
            recordRevision(
                    DefinitionRevision.WORKFLOW,
                    stamped.id(),
                    stamped.revision(),
                    stamped.name(),
                    RevisionSummary.of(previous.orElse(null), stamped),
                    stamped,
                    now);
            return stamped;
        }));
    }

    @Override
    public boolean deleteWorkflow(String id) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            deleteRevisions(DefinitionRevision.WORKFLOW, id);
            return jdbc.sql("DELETE FROM workflows WHERE id = :id")
                            .param("id", id)
                            .update()
                    > 0;
        }));
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
        return withUpsertRetry(() -> tx.execute(status -> {
            Optional<AgentDefinition> previous = jdbc.sql("SELECT payload FROM agents WHERE id = :id FOR UPDATE")
                    .param("id", definition.id())
                    .query((rs, row) -> Json.read(rs.getString("payload"), AgentDefinition.class))
                    .optional();
            boolean exists = previous.isPresent();
            AgentDefinition stamped = definition.withUpdatedAt(clock.instant());
            String sql = exists
                    ? "UPDATE agents SET name = :name, payload = :payload, updated_at = :updatedAt WHERE id = :id"
                    : "INSERT INTO agents (id, name, payload, updated_at) VALUES (:id, :name, :payload, :updatedAt)";
            jdbc.sql(sql)
                    .param("id", stamped.id())
                    .param("name", stamped.name())
                    .param("payload", Json.write(stamped))
                    .param("updatedAt", Timestamps.of(stamped.updatedAt()))
                    .update();
            int revision = jdbc.sql("SELECT COALESCE(MAX(revision), 0) FROM definition_revisions"
                                    + " WHERE kind = 'agent' AND entity_id = :id")
                            .param("id", stamped.id())
                            .query(Integer.class)
                            .single()
                    + 1;
            recordRevision(
                    DefinitionRevision.AGENT,
                    stamped.id(),
                    revision,
                    stamped.name(),
                    RevisionSummary.of(previous.orElse(null), stamped),
                    stamped,
                    stamped.updatedAt());
            return stamped;
        }));
    }

    /** Writes a revision in the caller's transaction and drops the ones beyond the kept history. */
    private void recordRevision(
            String kind, String id, int revision, String name, String summary, Object payload, Instant at) {
        jdbc.sql("INSERT INTO definition_revisions (kind, entity_id, revision, name, author, summary, payload,"
                        + " created_at) VALUES (:kind, :id, :rev, :name, :author, :summary, :payload, :at)")
                .param("kind", kind)
                .param("id", id)
                .param("rev", revision)
                .param("name", name)
                .param("author", author())
                .param("summary", summary)
                .param("payload", Json.write(payload))
                .param("at", Timestamps.of(at))
                .update();
        jdbc.sql("DELETE FROM definition_revisions WHERE kind = :kind AND entity_id = :id AND revision <= :oldest")
                .param("kind", kind)
                .param("id", id)
                .param("oldest", revision - DefinitionRevision.KEEP)
                .update();
    }

    private void deleteRevisions(String kind, String id) {
        jdbc.sql("DELETE FROM definition_revisions WHERE kind = :kind AND entity_id = :id")
                .param("kind", kind)
                .param("id", id)
                .update();
    }

    /** Who is saving: the signed-in account (or agent), {@code system} for startup imports and jobs. */
    private static String author() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof io.github.loopforge4j.server.account.CurrentUser user) {
            return user.username();
        }
        return "system";
    }

    private static <T> T withUpsertRetry(java.util.function.Supplier<T> write) {
        for (int attempt = 1; ; attempt++) {
            try {
                return write.get();
            } catch (DuplicateKeyException e) {
                if (attempt >= 5) {
                    throw new StoreException("concurrent catalog write did not settle", e);
                }
            } catch (DataAccessException e) {
                throw new StoreException("catalog write failed", e);
            }
        }
    }

    @Override
    public boolean deleteAgent(String id) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            deleteRevisions(DefinitionRevision.AGENT, id);
            return jdbc.sql("DELETE FROM agents WHERE id = :id").param("id", id).update() > 0;
        }));
    }

    @Override
    public Optional<RepositoryDefinition> repository(String id) {
        return jdbc.sql("SELECT payload FROM repositories WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> Json.read(rs.getString("payload"), RepositoryDefinition.class))
                .optional();
    }

    @Override
    public List<RepositoryDefinition> repositories() {
        return jdbc.sql("SELECT payload FROM repositories ORDER BY id")
                .query((rs, row) -> Json.read(rs.getString("payload"), RepositoryDefinition.class))
                .list();
    }

    @Override
    public RepositoryDefinition saveRepository(RepositoryDefinition definition) {
        return withUpsertRetry(() -> tx.execute(status -> {
            boolean exists = jdbc.sql("SELECT id FROM repositories WHERE id = :id FOR UPDATE")
                    .param("id", definition.id())
                    .query(String.class)
                    .optional()
                    .isPresent();
            RepositoryDefinition stamped = definition.withUpdatedAt(clock.instant());
            String sql = exists
                    ? "UPDATE repositories SET name = :name, payload = :payload, updated_at = :updatedAt WHERE id = :id"
                    : "INSERT INTO repositories (id, name, payload, updated_at) VALUES (:id, :name, :payload, :updatedAt)";
            jdbc.sql(sql)
                    .param("id", stamped.id())
                    .param("name", stamped.name())
                    .param("payload", Json.write(stamped))
                    .param("updatedAt", Timestamps.of(stamped.updatedAt()))
                    .update();
            return stamped;
        }));
    }

    @Override
    public boolean deleteRepository(String id) {
        return jdbc.sql("DELETE FROM repositories WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    @Override
    public <T extends CatalogAsset> Optional<T> asset(AssetKind<T> kind, String id) {
        return jdbc.sql("SELECT payload FROM catalog_assets WHERE kind = :kind AND id = :id")
                .param("kind", kind.wire())
                .param("id", id)
                .query((rs, row) -> Json.read(rs.getString("payload"), kind.type()))
                .optional();
    }

    @Override
    public <T extends CatalogAsset> List<T> assets(AssetKind<T> kind) {
        return jdbc.sql("SELECT payload FROM catalog_assets WHERE kind = :kind ORDER BY id")
                .param("kind", kind.wire())
                .query((rs, row) -> Json.read(rs.getString("payload"), kind.type()))
                .list();
    }

    @Override
    public <T extends CatalogAsset> T saveAsset(AssetKind<T> kind, T asset) {
        return withUpsertRetry(() -> tx.execute(status -> {
            boolean exists = jdbc.sql("SELECT id FROM catalog_assets WHERE kind = :kind AND id = :id FOR UPDATE")
                    .param("kind", kind.wire())
                    .param("id", asset.id())
                    .query(String.class)
                    .optional()
                    .isPresent();
            T stamped = kind.cast(asset.withUpdatedAt(clock.instant()));
            String sql = exists
                    ? "UPDATE catalog_assets SET name = :name, payload = :payload, updated_at = :updatedAt"
                            + " WHERE kind = :kind AND id = :id"
                    : "INSERT INTO catalog_assets (kind, id, name, payload, updated_at)"
                            + " VALUES (:kind, :id, :name, :payload, :updatedAt)";
            jdbc.sql(sql)
                    .param("kind", kind.wire())
                    .param("id", stamped.id())
                    .param("name", stamped.name())
                    .param("payload", Json.write(stamped))
                    .param("updatedAt", Timestamps.of(stamped.updatedAt()))
                    .update();
            return stamped;
        }));
    }

    @Override
    public boolean deleteAsset(AssetKind<?> kind, String id) {
        return jdbc.sql("DELETE FROM catalog_assets WHERE kind = :kind AND id = :id")
                        .param("kind", kind.wire())
                        .param("id", id)
                        .update()
                > 0;
    }
}
