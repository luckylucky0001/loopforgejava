package io.github.loopforge4j.server.work;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Requirements; numbers are allocated per project. */
@Repository
public class RequirementStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RequirementStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public Requirement create(Requirement draft) {
        for (int attempt = 0; ; attempt++) {
            try {
                return JsonRows.store(() -> tx.execute(status -> {
                    int number = jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM requirements WHERE project_id = :p")
                            .param("p", draft.projectId())
                            .query(Integer.class)
                            .single();
                    var now = clock.instant();
                    Requirement created = new Requirement(
                            "req_" + UUID.randomUUID().toString().replace("-", ""),
                            draft.projectId(),
                            number,
                            draft.title(),
                            draft.description(),
                            draft.status(),
                            draft.priority(),
                            draft.assignee(),
                            draft.workflowId(),
                            draft.repositoryIds(),
                            draft.baseBranch(),
                            List.of(),
                            null,
                            List.of(),
                            draft.source(),
                            draft.external(),
                            draft.createdBy(),
                            now,
                            now);
                    jdbc.sql(
                                    "INSERT INTO requirements (id, project_id, number, status, latest_run_id, external_key, payload,"
                                            + " created_at, updated_at) VALUES (:id, :p, :n, :s, NULL, :key, :payload, :now, :now)")
                            .param(
                                    "key",
                                    created.external() == null
                                            ? null
                                            : created.external().key())
                            .param("id", created.id())
                            .param("p", created.projectId())
                            .param("n", number)
                            .param("s", created.status())
                            .param("payload", JsonRows.write(created))
                            .param("now", SqlTime.of(now))
                            .update();
                    return created;
                }));
            } catch (DuplicateKeyException e) {
                // a number taken concurrently is retried; an external item that exists already is not
                if (attempt >= 5
                        || draft.external() != null
                                && findByExternal(
                                                draft.projectId(),
                                                draft.external().key())
                                        .isPresent()) {
                    throw e;
                }
            }
        }
    }

    public Optional<Requirement> find(String projectId, int number) {
        return jdbc.sql("SELECT payload FROM requirements WHERE project_id = :p AND number = :n")
                .param("p", projectId)
                .param("n", number)
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), Requirement.class))
                .optional();
    }

    public Optional<Requirement> findByExternal(String projectId, String key) {
        return jdbc.sql("SELECT payload FROM requirements WHERE project_id = :p AND external_key = :k")
                .param("p", projectId)
                .param("k", key)
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), Requirement.class))
                .optional();
    }

    public Optional<Requirement> byLatestRun(String runId) {
        return jdbc.sql("SELECT payload FROM requirements WHERE latest_run_id = :run")
                .param("run", runId)
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), Requirement.class))
                .optional();
    }

    public List<Requirement> list(String projectId, String status) {
        String sql = "SELECT payload FROM requirements WHERE project_id = :p"
                + (status == null ? "" : " AND status = :s") + " ORDER BY number DESC";
        var statement = jdbc.sql(sql).param("p", projectId);
        if (status != null) {
            statement = statement.param("s", status);
        }
        return statement
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), Requirement.class))
                .list();
    }

    /** Read-modify-write under a row lock; returns the stored result. */
    public Optional<Requirement> update(String id, UnaryOperator<Requirement> change) {
        return JsonRows.store(() -> tx.execute(status -> {
            Optional<Requirement> current = jdbc.sql("SELECT payload FROM requirements WHERE id = :id FOR UPDATE")
                    .param("id", id)
                    .query((rs, i) -> JsonRows.read(rs.getString("payload"), Requirement.class))
                    .optional();
            if (current.isEmpty()) {
                return Optional.<Requirement>empty();
            }
            Requirement changed = change.apply(current.get());
            Requirement stamped = new Requirement(
                    changed.id(),
                    changed.projectId(),
                    changed.number(),
                    changed.title(),
                    changed.description(),
                    changed.status(),
                    changed.priority(),
                    changed.assignee(),
                    changed.workflowId(),
                    changed.repositoryIds(),
                    changed.baseBranch(),
                    changed.runIds(),
                    changed.latestRunId(),
                    changed.deliveries(),
                    changed.source(),
                    changed.external(),
                    changed.createdBy(),
                    changed.createdAt(),
                    clock.instant());
            jdbc.sql(
                            "UPDATE requirements SET status = :s, latest_run_id = :run, external_key = :key, payload = :payload,"
                                    + " updated_at = :now WHERE id = :id")
                    .param(
                            "key",
                            stamped.external() == null
                                    ? null
                                    : stamped.external().key())
                    .param("id", id)
                    .param("s", stamped.status())
                    .param("run", stamped.latestRunId())
                    .param("payload", JsonRows.write(stamped))
                    .param("now", SqlTime.of(stamped.updatedAt()))
                    .update();
            return Optional.of(stamped);
        }));
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM requirements WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }
}
