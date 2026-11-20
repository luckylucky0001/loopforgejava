package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.server.mcp.RunTokens;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Expiring links to a run's preview. A browser opens them without a bearer token, so the link
 * itself carries the grant: the run and an expiry signed with the key every instance shares.
 */
@Component
public class PreviewLinks {

    static final Duration TTL = Duration.ofHours(2);
    private static final String PURPOSE = "preview-link:v1";

    /** A token and when it stops working. */
    public record Link(String token, Instant expiresAt) {}

    private final RunTokens tokens;
    private final Clock clock;

    public PreviewLinks(RunTokens tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    public Link issue(String runId) {
        long expires = clock.instant().plus(TTL).getEpochSecond();
        return new Link(
                expires + "." + tokens.signature(PURPOSE, runId + ":" + expires), Instant.ofEpochSecond(expires));
    }

    public boolean valid(String runId, String token) {
        if (token == null || token.length() > 128) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        long expires;
        try {
            expires = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            return false;
        }
        if (expires <= clock.instant().getEpochSecond()) {
            return false;
        }
        byte[] expected = tokens.signature(PURPOSE, runId + ":" + expires).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII));
    }
}
