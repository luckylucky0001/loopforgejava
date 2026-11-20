package io.github.loopforge4j.server.runner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * An execution machine (upstream personal / pool runner): it connects out to the server, claims the
 * runs assigned to it and executes them with its own CLI logins and credentials.
 *
 * @param ownerId the account a personal runner belongs to; {@code null} for a pool runner
 * @param shared a pool runner every project member may start runs on
 * @param executors executor kinds the machine reported as installed
 */
public record Runner(
        String id,
        String name,
        String ownerId,
        String ownerName,
        boolean shared,
        String tokenPrefix,
        String version,
        String host,
        List<String> executors,
        int capacity,
        Instant createdAt,
        Instant lastSeenAt) {

    static final Duration ONLINE = Duration.ofSeconds(90);

    public Runner {
        executors = executors == null ? List.of() : List.copyOf(executors);
    }

    public boolean online(Instant now) {
        return lastSeenAt != null && lastSeenAt.isAfter(now.minus(ONLINE));
    }
}
