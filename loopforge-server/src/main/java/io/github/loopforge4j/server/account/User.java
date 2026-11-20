package io.github.loopforge4j.server.account;

import java.time.Instant;

/** An account; the password hash never leaves {@link UserStore}. */
public record User(
        String id,
        String username,
        String displayName,
        String email,
        Role role,
        boolean disabled,
        Instant createdAt,
        Instant updatedAt) {

    public boolean admin() {
        return role == Role.ADMIN;
    }
}
