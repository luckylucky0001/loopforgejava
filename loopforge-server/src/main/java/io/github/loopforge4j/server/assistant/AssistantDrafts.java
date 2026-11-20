package io.github.loopforge4j.server.assistant;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Platform writes the conversation assistant proposed (upstream assistant drafts): with approval
 * mode {@code confirm} every write waits here for the speaker, with {@code assisted} only the risky
 * ones, with {@code auto} none.
 */
@Component
public class AssistantDrafts {

    public static final String CONFIRM = "confirm";
    public static final String ASSISTED = "assisted";
    public static final String AUTO = "auto";

    public static final String PENDING = "pending";
    public static final String APPLIED = "applied";
    public static final String FAILED = "failed";
    public static final String DISCARDED = "discarded";

    /** Deletes, publishing, dispatching, starting and other run controls (upstream risky tools). */
    private static final Pattern RISKY = Pattern.compile(
            ".*(_delete|_publish|_dispatch|_start|_cancel|_reject|_run|_restore|_pass|_retry|_pause|_resume|_reopen"
                    + "|_approve|_rework)$");

    public record Draft(
            String id,
            String sessionId,
            String turnId,
            String userId,
            String tool,
            String method,
            String target,
            String body,
            String status,
            Integer resultStatus,
            String resultText,
            String decidedBy,
            Instant createdAt,
            Instant decidedAt) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    public AssistantDrafts(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static String normalize(String mode) {
        String m = mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT);
        return switch (m) {
            case ASSISTED, AUTO -> m;
            default -> CONFIRM;
        };
    }

    /** Whether a write must wait for the speaker under {@code mode}. */
    public static boolean needsApproval(String mode, String tool, String method) {
        return switch (normalize(mode)) {
            case AUTO -> false;
            case ASSISTED -> "DELETE".equals(method) || RISKY.matcher(tool).matches();
            default -> true;
        };
    }

    public String mode(String userId) {
        if (userId == null) {
            return CONFIRM;
        }
        return normalize(jdbc.sql("SELECT approval_mode FROM assistant_preferences WHERE user_id = :u")
                .param("u", userId)
                .query(String.class)
                .optional()
                .orElse(null));
    }

    void mode(String userId, String mode) {
        String value = normalize(mode);
        Instant now = clock.instant();
        int updated = jdbc.sql(
                        "UPDATE assistant_preferences SET approval_mode = :m, updated_at = :now WHERE user_id = :u")
                .param("m", value)
                .param("now", SqlTime.of(now))
                .param("u", userId)
                .update();
        if (updated == 0) {
            try {
                jdbc.sql("INSERT INTO assistant_preferences (user_id, steer_on_send, approval_mode, updated_at)"
                                + " VALUES (:u, FALSE, :m, :now)")
                        .param("u", userId)
                        .param("m", value)
                        .param("now", SqlTime.of(now))
                        .update();
            } catch (DuplicateKeyException e) {
                mode(userId, value);
            }
        }
    }

    public Draft propose(
            String sessionId, String turnId, String userId, String tool, String method, String target, String body) {
        Draft draft = new Draft(
                "drf-" + UUID.randomUUID(),
                sessionId,
                turnId,
                userId,
                tool,
                method,
                target,
                body,
                PENDING,
                null,
                null,
                null,
                clock.instant(),
                null);
        jdbc.sql("INSERT INTO assistant_drafts (id, session_id, turn_id, user_id, tool, method, target, body, status,"
                        + " created_at) VALUES (:id, :s, :t, :u, :tool, :m, :target, :body, :status, :at)")
                .param("id", draft.id())
                .param("s", sessionId)
                .param("t", turnId)
                .param("u", userId)
                .param("tool", tool)
                .param("m", method)
                .param("target", target)
                .param("body", body)
                .param("status", PENDING)
                .param("at", SqlTime.of(draft.createdAt()))
                .update();
        return draft;
    }

    public List<Draft> list(String sessionId) {
        return jdbc.sql("SELECT * FROM assistant_drafts WHERE session_id = :s ORDER BY created_at, id")
                .param("s", sessionId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<Draft> find(String sessionId, String id) {
        return jdbc.sql("SELECT * FROM assistant_drafts WHERE session_id = :s AND id = :id")
                .param("s", sessionId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Takes a pending draft for deciding; false when someone decided it first. */
    boolean claim(String id, String decidedBy) {
        return jdbc.sql("UPDATE assistant_drafts SET status = 'deciding', decided_by = :by, decided_at = :at"
                                + " WHERE id = :id AND status = :pending")
                        .param("by", decidedBy)
                        .param("at", SqlTime.of(clock.instant()))
                        .param("id", id)
                        .param("pending", PENDING)
                        .update()
                == 1;
    }

    void finish(String id, String status, Integer resultStatus, String resultText) {
        String text =
                resultText == null ? null : resultText.length() > 4000 ? resultText.substring(0, 4000) : resultText;
        jdbc.sql("UPDATE assistant_drafts SET status = :st, result_status = :rs, result_text = :rt, decided_at = :at"
                        + " WHERE id = :id")
                .param("st", status)
                .param("rs", resultStatus)
                .param("rt", text)
                .param("at", SqlTime.of(clock.instant()))
                .param("id", id)
                .update();
    }

    private static Draft map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Integer resultStatus = rs.getObject("result_status") == null ? null : rs.getInt("result_status");
        return new Draft(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("turn_id"),
                rs.getString("user_id"),
                rs.getString("tool"),
                rs.getString("method"),
                rs.getString("target"),
                rs.getString("body"),
                rs.getString("status"),
                resultStatus,
                rs.getString("result_text"),
                rs.getString("decided_by"),
                SqlTime.read(rs, "created_at"),
                SqlTime.read(rs, "decided_at"));
    }
}
