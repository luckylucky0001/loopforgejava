package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.json.Json;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the revision history written by {@link JdbcCatalogStore}. */
@Repository
public class DefinitionRevisionStore {

    private final JdbcClient jdbc;

    public DefinitionRevisionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first, without payloads. */
    public List<DefinitionRevision> list(String kind, String entityId) {
        return jdbc.sql("SELECT kind, entity_id, revision, name, author, summary, created_at FROM definition_revisions"
                        + " WHERE kind = :kind AND entity_id = :id ORDER BY revision DESC")
                .param("kind", kind)
                .param("id", entityId)
                .query((rs, row) -> new DefinitionRevision(
                        rs.getString("kind"),
                        rs.getString("entity_id"),
                        rs.getInt("revision"),
                        rs.getString("name"),
                        rs.getString("author"),
                        rs.getString("summary"),
                        null,
                        Timestamps.read(rs, "created_at")))
                .list();
    }

    /** One revision with its definition deserialized as {@code type}. */
    public <T> Optional<DefinitionRevision> find(String kind, String entityId, int revision, Class<T> type) {
        return jdbc.sql("SELECT kind, entity_id, revision, name, author, summary, payload, created_at"
                        + " FROM definition_revisions WHERE kind = :kind AND entity_id = :id AND revision = :rev")
                .param("kind", kind)
                .param("id", entityId)
                .param("rev", revision)
                .query((rs, row) -> new DefinitionRevision(
                        rs.getString("kind"),
                        rs.getString("entity_id"),
                        rs.getInt("revision"),
                        rs.getString("name"),
                        rs.getString("author"),
                        rs.getString("summary"),
                        Json.read(rs.getString("payload"), type),
                        Timestamps.read(rs, "created_at")))
                .optional();
    }
}
