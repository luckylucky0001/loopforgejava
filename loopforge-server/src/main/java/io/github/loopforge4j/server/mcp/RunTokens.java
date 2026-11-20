package io.github.loopforge4j.server.mcp;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Stateless credentials for the agent of one running node (upstream knowledge proxy token, extended
 * to the platform MCP). A token names its kind, run, project and role; it is signed with a key that
 * every instance shares through the database, expires, and stops working as soon as the run settles.
 */
@Component
public class RunTokens {

    public static final String PREFIX = "lfr1.";
    private static final String KEY_SETTING = "run-token.key";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    /** What a token is good for; a knowledge token never authenticates API calls. */
    public enum Kind {
        PLATFORM,
        KNOWLEDGE,
        /** The conversation assistant acting for the person speaking (never with administrator rights). */
        CONVERSATION
    }

    /** Conversation tokens name {@code conv:<session id>} instead of a run. */
    public static final String CONVERSATION_PREFIX = "conv:";

    /**
     * @param expiresAt epoch seconds
     */
    public record Claims(
            Kind kind, String runId, String projectId, String nodeId, String agentId, long expiresAt, String subject) {}

    private final JdbcClient jdbc;
    private final RunStore runs;
    private final Clock clock;
    private volatile byte[] key;

    public RunTokens(JdbcClient jdbc, RunStore runs, Clock clock) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.clock = clock;
    }

    public static boolean looksLike(String token) {
        return token != null && token.startsWith(PREFIX);
    }

    public String issue(Kind kind, String runId, String projectId, String nodeId, String agentId, Duration ttl) {
        return issue(kind, runId, projectId, nodeId, agentId, null, ttl);
    }

    /**
     * @param subject the account a conversation token acts for
     */
    public String issue(
            Kind kind, String runId, String projectId, String nodeId, String agentId, String subject, Duration ttl) {
        Claims claims = new Claims(
                kind,
                runId,
                projectId,
                nodeId,
                agentId,
                clock.instant().plus(ttl).getEpochSecond(),
                subject);
        String body = PREFIX + B64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    /**
     * @return the claims of a valid token of {@code kind} whose run is still active
     */
    public Optional<Claims> verify(String token, Kind kind) {
        if (!looksLike(token) || token.length() > 2048) {
            return Optional.empty();
        }
        int dot = token.lastIndexOf('.');
        if (dot <= PREFIX.length()) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        byte[] expected = sign(body).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        Claims claims;
        try {
            claims = Json.read(
                    new String(B64D.decode(body.substring(PREFIX.length())), StandardCharsets.UTF_8), Claims.class);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (claims.kind() != kind
                || claims.runId() == null
                || claims.expiresAt() <= clock.instant().getEpochSecond()) {
            return Optional.empty();
        }
        if (claims.runId().startsWith(CONVERSATION_PREFIX)) {
            // a conversation token lives as long as the turn it was issued for
            return (claims.kind() == Kind.CONVERSATION || claims.kind() == Kind.KNOWLEDGE)
                            && generating(claims.runId().substring(CONVERSATION_PREFIX.length()), claims.nodeId())
                    ? Optional.of(claims)
                    : Optional.empty();
        }
        if (claims.kind() == Kind.CONVERSATION) {
            return Optional.empty();
        }
        // a settled or deleted run revokes every token issued for it
        Optional<Run> run = runs.find(claims.runId());
        if (run.isEmpty() || run.get().status().terminal()) {
            return Optional.empty();
        }
        return Optional.of(claims);
    }

    /** The session is answering the very turn the token was issued for. */
    private boolean generating(String sessionId, String turnId) {
        if (turnId == null) {
            return false;
        }
        return jdbc.sql("SELECT COUNT(*) FROM assistant_sessions WHERE id = :id AND status = 'generating'"
                                + " AND turn_id = :turn")
                        .param("id", sessionId)
                        .param("turn", turnId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Header marking a request this server forwarded for a platform MCP tool. */
    public static final String FORWARDED = "X-LoopForge-Forwarded";

    /**
     * The value of {@link #FORWARDED}: derived from the shared token key, so every instance accepts
     * it and no agent knows it.
     */
    public String forwardingMark() {
        return sign("forwarded-by-platform-mcp:v1");
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] key() {
        byte[] k = key;
        if (k == null) {
            synchronized (this) {
                k = key;
                if (k == null) {
                    k = loadOrCreateKey();
                    key = k;
                }
            }
        }
        return k;
    }

    private byte[] loadOrCreateKey() {
        Optional<String> stored = read();
        if (stored.isEmpty()) {
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            try {
                jdbc.sql("INSERT INTO app_settings (setting_name, setting_value, updated_at) VALUES (:n, :v, :now)")
                        .param("n", KEY_SETTING)
                        .param("v", Base64.getEncoder().encodeToString(fresh))
                        .param("now", SqlTime.of(clock.instant()))
                        .update();
            } catch (DuplicateKeyException e) {
                // another instance created it first
            }
            stored = read();
        }
        return Base64.getDecoder().decode(stored.orElseThrow());
    }

    private Optional<String> read() {
        return jdbc.sql("SELECT setting_value FROM app_settings WHERE setting_name = :n")
                .param("n", KEY_SETTING)
                .query(String.class)
                .optional();
    }
}
