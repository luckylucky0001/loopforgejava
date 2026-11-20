package io.github.loopforge4j.server.account;

import io.github.loopforge4j.server.persistence.SqlTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Session and personal access tokens of accounts. */
@Repository
public class TokenStore {

    public static final String PREFIX = "lfu_";
    private static final SecureRandom RANDOM = new SecureRandom();
    /** last_used_at is written at most this often per token. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final JdbcClient jdbc;
    private final Clock clock;

    public TokenStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** A freshly issued token: the secret plus its stored metadata. */
    public record Issued(String token, AccessToken metadata) {}

    public Issued issue(String userId, String name, String kind, Instant expiresAt) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant now = clock.instant();
        AccessToken metadata = new AccessToken(
                "tok_" + UUID.randomUUID().toString().replace("-", ""),
                name,
                kind,
                token.substring(0, 12),
                now,
                null,
                expiresAt);
        jdbc.sql("INSERT INTO user_tokens (id, user_id, name, kind, token_hash, prefix, created_at, expires_at)"
                        + " VALUES (:id, :user, :name, :kind, :hash, :prefix, :now, :expires)")
                .param("id", metadata.id())
                .param("user", userId)
                .param("name", name)
                .param("kind", kind)
                .param("hash", hash(token))
                .param("prefix", metadata.prefix())
                .param("now", SqlTime.of(now))
                .param("expires", SqlTime.of(expiresAt))
                .update();
        return new Issued(token, metadata);
    }

    /** The enabled account a valid, unexpired token belongs to. */
    public Optional<User> authenticate(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() > 200) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        record Row(String tokenId, Instant lastUsed, User user) {}
        Optional<Row> row = jdbc.sql("SELECT t.id AS token_id, t.last_used_at, u.* FROM user_tokens t JOIN users u"
                        + " ON u.id = t.user_id WHERE t.token_hash = :hash AND u.disabled = FALSE"
                        + " AND (t.expires_at IS NULL OR t.expires_at > :now)")
                .param("hash", hash(token))
                .param("now", SqlTime.of(now))
                .query((rs, i) ->
                        new Row(rs.getString("token_id"), SqlTime.read(rs, "last_used_at"), UserStore.map(rs, i)))
                .optional();
        row.ifPresent(r -> {
            if (r.lastUsed() == null || r.lastUsed().isBefore(now.minus(TOUCH_INTERVAL))) {
                jdbc.sql("UPDATE user_tokens SET last_used_at = :now WHERE id = :id")
                        .param("now", SqlTime.of(now))
                        .param("id", r.tokenId())
                        .update();
            }
        });
        return row.map(Row::user);
    }

    public List<AccessToken> list(String userId) {
        return jdbc.sql("SELECT * FROM user_tokens WHERE user_id = :user AND (expires_at IS NULL OR expires_at > :now)"
                        + " ORDER BY created_at DESC")
                .param("user", userId)
                .param("now", SqlTime.of(clock.instant()))
                .query((rs, i) -> new AccessToken(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getString("prefix"),
                        SqlTime.read(rs, "created_at"),
                        SqlTime.read(rs, "last_used_at"),
                        SqlTime.read(rs, "expires_at")))
                .list();
    }

    public boolean revoke(String userId, String tokenId) {
        return jdbc.sql("DELETE FROM user_tokens WHERE user_id = :user AND id = :id")
                        .param("user", userId)
                        .param("id", tokenId)
                        .update()
                > 0;
    }

    public void revokeToken(String token) {
        jdbc.sql("DELETE FROM user_tokens WHERE token_hash = :hash")
                .param("hash", hash(token))
                .update();
    }

    public void revokeAll(String userId) {
        jdbc.sql("DELETE FROM user_tokens WHERE user_id = :user")
                .param("user", userId)
                .update();
    }

    public int pruneExpired() {
        return jdbc.sql("DELETE FROM user_tokens WHERE expires_at IS NOT NULL AND expires_at < :now")
                .param("now", SqlTime.of(clock.instant()))
                .update();
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
