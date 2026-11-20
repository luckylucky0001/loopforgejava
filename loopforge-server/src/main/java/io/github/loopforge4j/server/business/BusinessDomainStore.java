package io.github.loopforge4j.server.business;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Business domains, project assignments and their history. */
@Repository
public class BusinessDomainStore {

    /** One change of a project's business domain. */
    public record Assignment(
            String id,
            String projectId,
            String fromDomainId,
            String toDomainId,
            String actor,
            String reason,
            Instant createdAt) {}

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public BusinessDomainStore(JdbcClient jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    public List<BusinessDomain> list() {
        return jdbc.sql("SELECT * FROM business_domains ORDER BY name")
                .query(this::map)
                .list();
    }

    public Optional<BusinessDomain> find(String id) {
        return jdbc.sql("SELECT * FROM business_domains WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public boolean nameTaken(String name, String exceptId) {
        return jdbc.sql("SELECT COUNT(*) FROM business_domains WHERE LOWER(name) = LOWER(:name) AND id <> :id")
                        .param("name", name)
                        .param("id", exceptId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public BusinessDomain insert(BusinessDomain d) {
        Instant now = clock.instant();
        jdbc.sql(
                        "INSERT INTO business_domains (id, name, description, active, managers, viewers, created_at, updated_at)"
                                + " VALUES (:id, :name, :description, :active, :managers, :viewers, :now, :now)")
                .param("id", d.id())
                .param("name", d.name())
                .param("description", d.description())
                .param("active", d.active())
                .param("managers", Json.write(d.managers()))
                .param("viewers", Json.write(d.viewers()))
                .param("now", SqlTime.of(now))
                .update();
        return find(d.id()).orElseThrow();
    }

    /**
     * Updates a domain if it was not changed since {@code expected}.
     *
     * @return false when someone changed it meanwhile
     */
    public boolean update(BusinessDomain d, Instant expected) {
        return jdbc.sql("UPDATE business_domains SET name = :name, description = :description, active = :active,"
                                + " managers = :managers, viewers = :viewers, updated_at = :now"
                                + " WHERE id = :id AND updated_at = :expected")
                        .param("id", d.id())
                        .param("name", d.name())
                        .param("description", d.description())
                        .param("active", d.active())
                        .param("managers", Json.write(d.managers()))
                        .param("viewers", Json.write(d.viewers()))
                        .param("now", SqlTime.of(clock.instant()))
                        .param("expected", SqlTime.of(expected))
                        .update()
                > 0;
    }

    /**
     * Moves a project to {@code to} (null clears it) if it still is in {@code expectedFrom}.
     *
     * @return false when the project's domain changed meanwhile
     */
    public boolean assign(String projectId, String expectedFrom, String to, String actor, String reason) {
        Boolean done = tx.execute(status -> {
            String current = jdbc.sql("SELECT business_domain_id FROM projects WHERE id = :id FOR UPDATE")
                    .param("id", projectId)
                    .query((rs, n) -> Optional.ofNullable(rs.getString("business_domain_id")))
                    .single()
                    .orElse(null);
            if (!java.util.Objects.equals(current, expectedFrom)) {
                return false;
            }
            Instant now = clock.instant();
            jdbc.sql("UPDATE projects SET business_domain_id = :to, updated_at = :now WHERE id = :id")
                    .param("id", projectId)
                    .param("to", to)
                    .param("now", SqlTime.of(now))
                    .update();
            jdbc.sql(
                            "INSERT INTO business_domain_assignments (id, project_id, from_domain_id, to_domain_id, actor, reason,"
                                    + " created_at) VALUES (:id, :project, :from, :to, :actor, :reason, :now)")
                    .param("id", "bda_" + UUID.randomUUID().toString().replace("-", ""))
                    .param("project", projectId)
                    .param("from", current)
                    .param("to", to)
                    .param("actor", actor)
                    .param("reason", reason)
                    .param("now", SqlTime.of(now))
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    public List<Assignment> history(String projectId) {
        return jdbc.sql("SELECT * FROM business_domain_assignments WHERE project_id = :p ORDER BY created_at DESC, id")
                .param("p", projectId)
                .query((rs, n) -> new Assignment(
                        rs.getString("id"),
                        rs.getString("project_id"),
                        rs.getString("from_domain_id"),
                        rs.getString("to_domain_id"),
                        rs.getString("actor"),
                        rs.getString("reason"),
                        SqlTime.read(rs, "created_at")))
                .list();
    }

    private BusinessDomain map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new BusinessDomain(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("active"),
                List.of(Json.read(rs.getString("managers"), String[].class)),
                List.of(Json.read(rs.getString("viewers"), String[].class)),
                SqlTime.read(rs, "created_at"),
                SqlTime.read(rs, "updated_at"));
    }
}
