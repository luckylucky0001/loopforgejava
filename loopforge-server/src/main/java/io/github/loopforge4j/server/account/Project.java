package io.github.loopforge4j.server.account;

import java.time.Instant;

/** A project groups runs, repositories, requirements, schedules and notifications for a team. */
public record Project(
        String id,
        String name,
        String description,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        String businessDomainId) {}
