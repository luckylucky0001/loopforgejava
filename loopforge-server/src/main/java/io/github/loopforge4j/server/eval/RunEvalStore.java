package io.github.loopforge4j.server.eval;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Evaluations of runs; one running evaluation per run at a time. */
@Repository
public class RunEvalStore {

    static final int KEEP = 10;

    private final JdbcClient jdbc;
    private final Clock clock;

    public RunEvalStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Inserts a running evaluation unless the run already has one that is not stale.
     *
     * @return whether it was inserted
     */
    boolean start(RunEval eval, Instant staleBefore) {
        Instant now = clock.instant();
        int inserted = jdbc.sql("INSERT INTO run_evals (id, run_id, status, payload, created_at, updated_at)"
                        + " SELECT :id, :run, 'running', :payload, :now, :now FROM (SELECT 1 AS one) x"
                        + " WHERE NOT EXISTS (SELECT 1 FROM run_evals WHERE run_id = :run AND status = 'running'"
                        + " AND created_at >= :stale)")
                .param("id", eval.id())
                .param("run", eval.runId())
                .param("payload", Json.write(eval))
                .param("now", SqlTime.of(now))
                .param("stale", SqlTime.of(staleBefore))
                .update();
        if (inserted == 1) {
            jdbc.sql("DELETE FROM run_evals WHERE run_id = :run AND id NOT IN (SELECT id FROM (SELECT id FROM run_evals"
                            + " WHERE run_id = :run ORDER BY created_at DESC LIMIT :keep) kept)")
                    .param("run", eval.runId())
                    .param("keep", KEEP)
                    .update();
        }
        return inserted == 1;
    }

    void save(RunEval eval) {
        jdbc.sql("UPDATE run_evals SET status = :status, payload = :payload, updated_at = :now WHERE id = :id")
                .param("id", eval.id())
                .param("status", eval.status())
                .param("payload", Json.write(eval))
                .param("now", SqlTime.of(clock.instant()))
                .update();
    }

    Optional<RunEval> find(String id) {
        return jdbc.sql("SELECT payload FROM run_evals WHERE id = :id")
                .param("id", id)
                .query((rs, i) -> Json.read(rs.getString("payload"), RunEval.class))
                .optional();
    }

    List<RunEval> list(String runId) {
        return jdbc.sql("SELECT payload FROM run_evals WHERE run_id = :run ORDER BY created_at DESC LIMIT :keep")
                .param("run", runId)
                .param("keep", KEEP)
                .query((rs, i) -> Json.read(rs.getString("payload"), RunEval.class))
                .list();
    }
}
