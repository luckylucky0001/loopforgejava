package io.github.loopforge4j.server.work;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A webhook that receives run events of a project (or of every project for a global channel).
 * Deliveries are JSON with an {@code X-LoopForge-Signature: sha256=<hmac>} header when a secret is
 * set (upstream notification workflows, webhook transport).
 *
 * @param events subset of {@link #EVENTS}; empty means all
 * @param secret HMAC key; write-only, never returned by the API
 */
public record NotificationChannel(
        String id,
        String projectId,
        String name,
        boolean enabled,
        String url,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String secret,

        List<String> events,
        Instant lastDeliveryAt,
        String lastStatus,
        Instant createdAt,
        Instant updatedAt) {

    public static final Set<String> EVENTS =
            Set.of("run.started", "run.waiting", "run.succeeded", "run.failed", "run.cancelled", "delivery.merged");

    public NotificationChannel {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    boolean wants(String event) {
        return enabled && (events.isEmpty() || events.contains(event));
    }

    NotificationChannel delivered(Instant at, String status) {
        return new NotificationChannel(
                id, projectId, name, enabled, url, secret, events, at, status, createdAt, updatedAt);
    }
}
