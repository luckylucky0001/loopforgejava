package io.github.loopforge4j.server.knowledge;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Knowledge base settings, the review queue and the run artifacts that look like distilled knowledge. */
@Repository
public class KnowledgeStore {

    /** The conventional deliverable a knowledge-enabled role writes (upstream). */
    public static final String ARTIFACT_NAME = "knowledge.md";

    private static final String SETTINGS_ID = "default";

    private final JdbcClient jdbc;
    private final Clock clock;

    public KnowledgeStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public KnowledgeSettings settings() {
        return jdbc.sql("SELECT payload FROM knowledge_settings WHERE id = :id")
                .param("id", SETTINGS_ID)
                .query(String.class)
                .optional()
                .map(raw -> Json.read(raw, KnowledgeSettings.class))
                .orElse(KnowledgeSettings.EMPTY);
    }

    public KnowledgeSettings saveSettings(KnowledgeSettings settings) {
        Instant now = clock.instant();
        String payload = Json.write(settings);
        int updated = jdbc.sql("UPDATE knowledge_settings SET payload = :p, updated_at = :now WHERE id = :id")
                .param("p", payload)
                .param("now", SqlTime.of(now))
                .param("id", SETTINGS_ID)
                .update();
        if (updated == 0) {
            try {
                jdbc.sql("INSERT INTO knowledge_settings (id, payload, updated_at) VALUES (:id, :p, :now)")
                        .param("id", SETTINGS_ID)
                        .param("p", payload)
                        .param("now", SqlTime.of(now))
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException e) {
                return saveSettings(settings);
            }
        }
        return settings;
    }

    public Optional<KnowledgeSubmission> submission(String id) {
        return jdbc.sql("SELECT payload FROM knowledge_submissions WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .map(raw -> Json.read(raw, KnowledgeSubmission.class));
    }

    /**
     * @param projects visible projects, {@code null} for all; projectless submissions are listed
     *     only when {@code projects} is {@code null}
     */
    public List<KnowledgeSubmission> submissions(String projectId, String status, Set<String> projects, int limit) {
        StringBuilder sql = new StringBuilder("SELECT payload FROM knowledge_submissions WHERE 1 = 1");
        if (projectId != null && !projectId.isBlank()) {
            sql.append(" AND project_id = :project");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
        }
        if (projects != null) {
            if (projects.isEmpty()) {
                return List.of();
            }
            sql.append(" AND project_id IN (:projects)");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var statement = jdbc.sql(sql.toString()).param("limit", limit);
        if (projectId != null && !projectId.isBlank()) {
            statement = statement.param("project", projectId);
        }
        if (status != null && !status.isBlank()) {
            statement = statement.param("status", status);
        }
        if (projects != null) {
            statement = statement.param("projects", List.copyOf(projects));
        }
        return statement.query(String.class).list().stream()
                .map(raw -> Json.read(raw, KnowledgeSubmission.class))
                .toList();
    }

    /** Inserts or replaces a submission; a second submission of the same artifact fails. */
    public KnowledgeSubmission save(KnowledgeSubmission item) {
        Instant now = clock.instant();
        Optional<KnowledgeSubmission> existing = submission(item.id());
        KnowledgeSubmission stamped = new KnowledgeSubmission(
                item.id(),
                item.projectId(),
                item.runId(),
                item.nodeId(),
                item.artifactId(),
                item.workflowId(),
                item.title(),
                item.source(),
                item.path(),
                item.content(),
                item.status(),
                item.commit(),
                item.error(),
                item.reviewNote(),
                item.createdBy(),
                item.reviewedBy(),
                existing.map(KnowledgeSubmission::createdAt).orElse(now),
                now,
                item.publishedAt());
        String sql = existing.isPresent()
                ? "UPDATE knowledge_submissions SET status = :status, payload = :payload, updated_at = :now"
                        + " WHERE id = :id"
                : "INSERT INTO knowledge_submissions (id, project_id, status, artifact_id, payload, created_at,"
                        + " updated_at) VALUES (:id, :project, :status, :artifact, :payload, :now, :now)";
        var statement = jdbc.sql(sql)
                .param("id", stamped.id())
                .param("status", stamped.status())
                .param("payload", Json.write(stamped))
                .param("now", SqlTime.of(now));
        if (existing.isEmpty()) {
            statement = statement.param("project", stamped.projectId()).param("artifact", stamped.artifactId());
        }
        statement.update();
        return stamped;
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM knowledge_submissions WHERE id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    public Optional<String> submissionOfArtifact(String artifactId) {
        return jdbc.sql("SELECT id FROM knowledge_submissions WHERE artifact_id = :a")
                .param("a", artifactId)
                .query(String.class)
                .optional();
    }

    /** A run artifact that looks like distilled knowledge (metadata only). */
    public record Candidate(
            String artifactId,
            String runId,
            String nodeId,
            int attempt,
            String name,
            String path,
            long size,
            Instant createdAt,
            String projectId,
            String workflowId,
            String runStatus,
            String submissionId) {}

    /**
     * Newest {@code knowledge.md} artifacts, the latest attempt per run, node and path.
     *
     * @param projects visible projects, {@code null} for all
     */
    public List<Candidate> candidates(String projectId, Set<String> projects, int limit) {
        if (projects != null && projects.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT a.id, a.run_id, a.node_id, a.attempt, a.name, a.path, a.size_bytes, a.created_at,"
                        + " r.project_id, r.workflow_id, r.status, s.id AS submission_id"
                        + " FROM run_artifacts a JOIN runs r ON r.id = a.run_id"
                        + " LEFT JOIN knowledge_submissions s ON s.artifact_id = a.id"
                        + " WHERE (a.path = :name OR a.path LIKE :suffix)");
        if (projectId != null && !projectId.isBlank()) {
            sql.append(" AND r.project_id = :project");
        }
        if (projects != null) {
            sql.append(" AND r.project_id IN (:projects)");
        }
        sql.append(" ORDER BY a.created_at DESC, a.seq DESC LIMIT :fetch");
        var statement = jdbc.sql(sql.toString())
                .param("name", ARTIFACT_NAME)
                .param("suffix", "%/" + ARTIFACT_NAME)
                .param("fetch", limit * 4);
        if (projectId != null && !projectId.isBlank()) {
            statement = statement.param("project", projectId);
        }
        if (projects != null) {
            statement = statement.param("projects", List.copyOf(projects));
        }
        List<Candidate> rows = statement
                .query((rs, n) -> new Candidate(
                        rs.getString("id"),
                        rs.getString("run_id"),
                        rs.getString("node_id"),
                        rs.getInt("attempt"),
                        rs.getString("name"),
                        rs.getString("path"),
                        rs.getLong("size_bytes"),
                        SqlTime.read(rs, "created_at"),
                        rs.getString("project_id"),
                        rs.getString("workflow_id"),
                        rs.getString("status"),
                        rs.getString("submission_id")))
                .list();
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Candidate c : rows) {
            if (seen.add(c.runId() + "\u0000" + c.nodeId() + "\u0000" + c.path())) {
                out.add(c);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }
}
