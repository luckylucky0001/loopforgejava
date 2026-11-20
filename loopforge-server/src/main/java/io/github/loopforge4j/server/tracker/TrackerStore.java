package io.github.loopforge4j.server.tracker;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Per-project tracker settings and the cluster-wide lease of the periodic sync. */
@Repository
public class TrackerStore {

    private static final String LEASE = "tracker.sync.lease";

    private final JdbcClient jdbc;
    private final Clock clock;

    public TrackerStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public TrackerSettings settings(String projectId) {
        return jdbc.sql("SELECT payload FROM project_trackers WHERE project_id = :p")
                .param("p", projectId)
                .query(String.class)
                .optional()
                .map(raw -> Json.read(raw, TrackerSettings.class))
                .orElse(TrackerSettings.NONE);
    }

    /** Every project that has trackers configured. */
    public Map<String, TrackerSettings> all() {
        Map<String, TrackerSettings> out = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT project_id, payload FROM project_trackers ORDER BY project_id")
                .query((rs, i) -> Map.entry(rs.getString(1), Json.read(rs.getString(2), TrackerSettings.class)))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public TrackerSettings save(String projectId, TrackerSettings settings) {
        String payload = Json.write(settings);
        int updated = jdbc.sql(
                        "UPDATE project_trackers SET payload = :payload, updated_at = :now WHERE project_id = :p")
                .param("payload", payload)
                .param("now", SqlTime.of(clock.instant()))
                .param("p", projectId)
                .update();
        if (updated == 0) {
            try {
                jdbc.sql("INSERT INTO project_trackers (project_id, payload, updated_at) VALUES (:p, :payload, :now)")
                        .param("p", projectId)
                        .param("payload", payload)
                        .param("now", SqlTime.of(clock.instant()))
                        .update();
            } catch (DuplicateKeyException e) {
                return save(projectId, settings);
            }
        }
        return settings;
    }

    /**
     * Takes the periodic-sync lease until {@code untilMillis} if nobody holds it (compare-and-set on
     * the previous value), so one instance pulls at a time.
     */
    public boolean acquireLease(long nowMillis, long untilMillis) {
        Optional<String> current = jdbc.sql("SELECT setting_value FROM app_settings WHERE setting_name = :n")
                .param("n", LEASE)
                .query(String.class)
                .optional();
        if (current.isEmpty()) {
            try {
                return jdbc.sql(
                                        "INSERT INTO app_settings (setting_name, setting_value, updated_at) VALUES (:n, :v, :now)")
                                .param("n", LEASE)
                                .param("v", String.valueOf(untilMillis))
                                .param("now", SqlTime.of(clock.instant()))
                                .update()
                        == 1;
            } catch (DuplicateKeyException e) {
                return false;
            }
        }
        long held;
        try {
            held = Long.parseLong(current.get());
        } catch (NumberFormatException e) {
            held = 0;
        }
        if (held > nowMillis) {
            return false;
        }
        return jdbc.sql("UPDATE app_settings SET setting_value = :v, updated_at = :now WHERE setting_name = :n"
                                + " AND setting_value = :old")
                        .param("v", String.valueOf(untilMillis))
                        .param("now", SqlTime.of(clock.instant()))
                        .param("n", LEASE)
                        .param("old", current.get())
                        .update()
                == 1;
    }

    static List<String> ids(List<String> in) {
        return in == null
                ? List.of()
                : in.stream()
                        .filter(s -> s != null && !s.isBlank())
                        .map(String::strip)
                        .distinct()
                        .toList();
    }
}
