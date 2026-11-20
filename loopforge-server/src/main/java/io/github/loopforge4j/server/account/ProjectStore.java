package io.github.loopforge4j.server.account;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Projects and their members. */
@Repository
public class ProjectStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public ProjectStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Project create(String id, String name, String description, String createdBy) {
        var now = clock.instant();
        jdbc.sql("INSERT INTO projects (id, name, description, created_by, created_at, updated_at)"
                        + " VALUES (:id, :name, :description, :by, :now, :now)")
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("by", createdBy)
                .param("now", SqlTime.of(now))
                .update();
        return find(id).orElseThrow();
    }

    public Project update(String id, String name, String description) {
        jdbc.sql("UPDATE projects SET name = :name, description = :description, updated_at = :now WHERE id = :id")
                .param("id", id)
                .param("name", name)
                .param("description", description)
                .param("now", SqlTime.of(clock.instant()))
                .update();
        return find(id).orElseThrow();
    }

    public Optional<Project> find(String id) {
        return jdbc.sql("SELECT * FROM projects WHERE id = :id")
                .param("id", id)
                .query(ProjectStore::map)
                .optional();
    }

    public List<Project> list() {
        return jdbc.sql("SELECT * FROM projects ORDER BY id")
                .query(ProjectStore::map)
                .list();
    }

    public List<Project> listFor(String userId) {
        return jdbc.sql("SELECT p.* FROM projects p JOIN project_members m ON m.project_id = p.id"
                        + " WHERE m.user_id = :user ORDER BY p.id")
                .param("user", userId)
                .query(ProjectStore::map)
                .list();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM projects WHERE id = :id").param("id", id).update() > 0;
    }

    public List<ProjectMember> members(String projectId) {
        return jdbc.sql("SELECT m.project_id, m.user_id, m.role, u.username FROM project_members m"
                        + " JOIN users u ON u.id = m.user_id WHERE m.project_id = :project ORDER BY u.username")
                .param("project", projectId)
                .query((rs, i) -> new ProjectMember(
                        rs.getString("project_id"),
                        rs.getString("user_id"),
                        rs.getString("username"),
                        ProjectRole.fromWire(rs.getString("role"))))
                .list();
    }

    public void putMember(String projectId, String userId, ProjectRole role) {
        int updated = jdbc.sql(
                        "UPDATE project_members SET role = :role WHERE project_id = :project AND user_id = :user")
                .param("project", projectId)
                .param("user", userId)
                .param("role", role.wire())
                .update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO project_members (project_id, user_id, role) VALUES (:project, :user, :role)")
                    .param("project", projectId)
                    .param("user", userId)
                    .param("role", role.wire())
                    .update();
        }
    }

    public boolean removeMember(String projectId, String userId) {
        return jdbc.sql("DELETE FROM project_members WHERE project_id = :project AND user_id = :user")
                        .param("project", projectId)
                        .param("user", userId)
                        .update()
                > 0;
    }

    /** project id → role of the account. */
    public Map<String, ProjectRole> rolesOf(String userId) {
        return jdbc
                .sql("SELECT project_id, role FROM project_members WHERE user_id = :user")
                .param("user", userId)
                .query((rs, i) -> Map.entry(rs.getString("project_id"), ProjectRole.fromWire(rs.getString("role"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static Project map(ResultSet rs, int row) throws SQLException {
        return new Project(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("created_by"),
                SqlTime.read(rs, "created_at"),
                SqlTime.read(rs, "updated_at"));
    }

    /** The project's named SOP chains (JSON; empty when none were saved). */
    public java.util.List<io.github.loopforge4j.server.work.SopPlaybook> playbooks(String projectId) {
        String raw = jdbc.sql("SELECT sop_playbooks FROM projects WHERE id = :id")
                .param("id", projectId)
                .query(String.class)
                .optional()
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        return java.util.List.of(
                io.github.loopforge4j.core.json.Json.read(raw, io.github.loopforge4j.server.work.SopPlaybook[].class));
    }

    public void savePlaybooks(String projectId, java.util.List<io.github.loopforge4j.server.work.SopPlaybook> books) {
        jdbc.sql("UPDATE projects SET sop_playbooks = :books, updated_at = :now WHERE id = :id")
                .param("id", projectId)
                .param("books", books.isEmpty() ? null : io.github.loopforge4j.core.json.Json.write(books))
                .param("now", io.github.loopforge4j.server.persistence.SqlTime.of(clock.instant()))
                .update();
    }
}
