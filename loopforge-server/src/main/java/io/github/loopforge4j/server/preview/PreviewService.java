package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Preview sessions, the backend address and retention (port of upstream {@code preview.Service}). */
@Service
public class PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final String PREFIX = "/preview/";

    /** A created session and the link that opens it (the token is never stored). */
    public record Opened(JdbcPreviewStore.Session session, String url) {}

    /** A live session with its servable preview. */
    record Grant(JdbcPreviewStore.Session session, JdbcPreviewStore.Preview preview, String token) {}

    /** A rejected request: the message is meant for the caller. */
    public static final class PreviewException extends RuntimeException {
        private final int status;

        PreviewException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final JdbcPreviewStore store;
    private final LoopForgeProperties.Preview config;
    private final Clock clock;

    public PreviewService(JdbcPreviewStore store, LoopForgeProperties properties, Clock clock) {
        this.store = store;
        this.config = properties.preview();
        this.clock = clock;
    }

    LoopForgeProperties.Preview config() {
        return config;
    }

    /**
     * Opens a preview link. Without an explicit address the run's stored backend address is used.
     *
     * @param origin the scheme, host and port of the request, used without a separate preview origin
     */
    public Opened open(String runId, String backendUrl, String createdBy, String origin) {
        JdbcPreviewStore.Preview preview =
                store.find(runId).orElseThrow(() -> new PreviewException(404, "preview not found"));
        if (!preview.serveable()) {
            throw new PreviewException(409, "preview is not ready (" + preview.status() + ")");
        }
        String backend = normalizeBackend(backendUrl == null ? preview.backendUrl() : backendUrl);
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        Instant now = clock.instant();
        JdbcPreviewStore.Session session = new JdbcPreviewStore.Session(
                "pvs-" + UUID.randomUUID(), runId, backend, createdBy, now.plus(config.sessionTtl()), now, null, null);
        store.insertSession(session, hash(token));
        String base = config.separateOrigin() ? trimSlash(config.publicUrl()) : trimSlash(origin);
        return new Opened(session, base + PREFIX + token + "/");
    }

    /** The live session of {@code token} and its servable preview. */
    Optional<Grant> lookup(String token) {
        if (!validToken(token)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return store.sessionByHash(hash(token))
                .filter(s -> s.live(now))
                .flatMap(s -> store.find(s.runId())
                        .filter(JdbcPreviewStore.Preview::serveable)
                        .map(p -> new Grant(s, p, token)));
    }

    /** Slides the session's expiry: a link in use stays open. */
    void renew(JdbcPreviewStore.Session session) {
        Instant now = clock.instant();
        // write at most once a minute per session
        if (session.lastAccessedAt() == null || session.lastAccessedAt().isBefore(now.minusSeconds(60))) {
            store.touch(session, now.plus(config.sessionTtl()), now);
        }
    }

    public boolean revoke(String runId, String sessionId) {
        return store.revoke(runId, sessionId, clock.instant());
    }

    public JdbcPreviewStore.Preview setBackend(String runId, String backendUrl) {
        store.find(runId).orElseThrow(() -> new PreviewException(404, "preview not found"));
        store.backend(runId, normalizeBackend(backendUrl));
        return store.find(runId).orElseThrow();
    }

    public boolean delete(String runId) {
        return store.delete(runId);
    }

    /** Expires ready previews past their retention and drops ended sessions (upstream reconcile). */
    @Scheduled(fixedDelayString = "${loopforge.preview.sweep-interval:PT1M}", initialDelayString = "PT30S")
    public void sweep() {
        try {
            Instant now = clock.instant();
            int expired = store.expire(now);
            int sessions = store.pruneSessions(now.minus(Duration.ofDays(1)));
            if (expired > 0 || sessions > 0) {
                log.info("previews: {} expired, {} ended sessions removed", expired, sessions);
            }
        } catch (RuntimeException e) {
            log.warn("preview sweep failed: {}", e.toString());
        }
    }

    /** An empty value or a credential-free http(s) URL without fragment (upstream rule). */
    static String normalizeBackend(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (Exception e) {
            throw new PreviewException(400, "preview backendUrl must be an http(s) URL without credentials");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (uri.getHost() == null
                || uri.getRawUserInfo() != null
                || !(scheme.equals("http") || scheme.equals("https"))
                || raw.length() > 1000) {
            throw new PreviewException(400, "preview backendUrl must be an http(s) URL without credentials");
        }
        try {
            return new URI(scheme, null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception e) {
            throw new PreviewException(400, "preview backendUrl must be an http(s) URL without credentials");
        }
    }

    /** The origin a CSP may name for the backend, or null. */
    static String backendOrigin(String backend) {
        if (backend == null) {
            return null;
        }
        URI uri = URI.create(backend);
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    static boolean validToken(String token) {
        if (token == null || token.length() != 32) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trimSlash(String value) {
        String out = value == null ? "" : value.strip();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
