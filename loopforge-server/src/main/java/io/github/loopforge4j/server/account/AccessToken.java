package io.github.loopforge4j.server.account;

import java.time.Instant;

/**
 * Metadata of an account token; the secret is shown once at creation and stored as SHA-256.
 *
 * @param kind {@code session} (login) or {@code personal} (API access)
 * @param prefix first characters of the token, to recognize it in lists
 */
public record AccessToken(
        String id, String name, String kind, String prefix, Instant createdAt, Instant lastUsedAt, Instant expiresAt) {}
