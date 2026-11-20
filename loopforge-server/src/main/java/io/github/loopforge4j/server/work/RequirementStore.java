package io.github.loopforge4j.server.work;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
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
                            now,
                            draft.playbookId(),
                            draft.sopBindings());
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
    /** Requirements in a status across projects (for pollers), at most {@code limit}. */
    public List<Requirement> byStatus(String status, int limit) {
        return jdbc.sql("SELECT payload FROM requirements WHERE status = :s ORDER BY updated_at LIMIT :limit")
                .param("s", status)
                .param("limit", limit)
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), Requirement.class))
                .list();
    }

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
                    clock.instant(),
                    changed.playbookId(),
                    changed.sopBindings());
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

    /** A run of a requirement and the SOP lane it ran. */
    public record LinkedRun(String runId, String workflowId, Instant createdAt) {}

    public void linkRun(String requirementId, String runId, String workflowId) {
        jdbc.sql("INSERT INTO requirement_runs (run_id, requirement_id, workflow_id, created_at)"
                        + " VALUES (:run, :req, :wf, :now)")
                .param("run", runId)
                .param("req", requirementId)
                .param("wf", workflowId)
                .param("now", io.github.loopforge4j.server.persistence.SqlTime.of(clock.instant()))
                .update();
    }

    /** The requirement's linked runs, oldest first. */
    public List<LinkedRun> linkedRuns(String requirementId) {
        return jdbc.sql("SELECT run_id, workflow_id, created_at FROM requirement_runs WHERE requirement_id = :req"
                        + " ORDER BY created_at, run_id")
                .param("req", requirementId)
                .query((rs, i) -> new LinkedRun(
                        rs.getString("run_id"),
                        rs.getString("workflow_id"),
                        io.github.loopforge4j.server.persistence.SqlTime.read(rs, "created_at")))
                .list();
    }

    /** The requirement a run belongs to: linked, or (before lanes) as its latest run. */
    public Optional<Requirement> byRun(String runId) {
        Optional<Requirement> linked = jdbc.sql("SELECT r.payload FROM requirements r JOIN requirement_runs l"
                        + " ON l.requirement_id = r.id WHERE l.run_id = :run")
                .param("run", runId)
                .query((rs, i) -> JsonRows.read(rs.getString("payload"), Requirement.class))
                .optional();
        return linked.isPresent() ? linked : byLatestRun(runId);
    }

    /**
     * Reserves the {@code generation}-th start of a lane.
     *
     * @return false when someone else reserved it first
     */
    public boolean reserveLane(String requirementId, String workflowId, int generation) {
        try {
            jdbc.sql("INSERT INTO requirement_lane_starts (requirement_id, workflow_id, generation, created_at)"
                            + " VALUES (:req, :wf, :gen, :now)")
                    .param("req", requirementId)
                    .param("wf", workflowId)
                    .param("gen", generation)
                    .param("now", io.github.loopforge4j.server.persistence.SqlTime.of(clock.instant()))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** The latest reservation of a lane, if it was ever started. */
    public Optional<LaneStart> latestLaneStart(String requirementId, String workflowId) {
        return jdbc.sql("SELECT generation, run_id, created_at FROM requirement_lane_starts"
                        + " WHERE requirement_id = :req AND workflow_id = :wf ORDER BY generation DESC LIMIT 1")
                .param("req", requirementId)
                .param("wf", workflowId)
                .query((rs, i) -> new LaneStart(
                        rs.getInt("generation"),
                        rs.getString("run_id"),
                        io.github.loopforge4j.server.persistence.SqlTime.read(rs, "created_at")))
                .optional();
    }

    /** Records the run a reservation started. */
    public void attachLane(String requirementId, String workflowId, int generation, String runId) {
        jdbc.sql("UPDATE requirement_lane_starts SET run_id = :run WHERE requirement_id = :req"
                        + " AND workflow_id = :wf AND generation = :gen")
                .param("run", runId)
                .param("req", requirementId)
                .param("wf", workflowId)
                .param("gen", generation)
                .update();
    }

    /** One start of a lane: its generation, the run it started (null while creating) and when. */
    public record LaneStart(int generation, String runId, Instant createdAt) {}

    /** Frees a reservation whose run could not be started. */
    public void releaseLane(String requirementId, String workflowId, int generation) {
        jdbc.sql("DELETE FROM requirement_lane_starts WHERE requirement_id = :req AND workflow_id = :wf"
                        + " AND generation = :gen")
                .param("req", requirementId)
                .param("wf", workflowId)
                .param("gen", generation)
                .update();
    }
}
