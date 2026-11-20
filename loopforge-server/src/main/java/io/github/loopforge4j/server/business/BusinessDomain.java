package io.github.loopforge4j.server.business;

import java.time.Instant;
import java.util.List;

/**
 * A business domain groups projects for delivery reports (upstream {@code BusinessDomain}).
 * Managers maintain it, viewers read its report; administrators manage everything.
 *
 * @param managers user ids
 * @param viewers user ids
 */
public record BusinessDomain(
        String id,
        String name,
        String description,
        boolean active,
        List<String> managers,
        List<String> viewers,
        Instant createdAt,
        Instant updatedAt) {

    /** Report pseudo-domains: every visible project, and projects without a domain. */
    public static final String ALL = "all";

    public static final String UNCLASSIFIED = "unclassified";

    public BusinessDomain {
        managers = managers == null ? List.of() : List.copyOf(managers);
        viewers = viewers == null ? List.of() : List.copyOf(viewers);
    }

    public boolean manages(String userId) {
        return userId != null && managers.contains(userId);
    }

    public boolean views(String userId) {
        return userId != null && (managers.contains(userId) || viewers.contains(userId));
    }
}
