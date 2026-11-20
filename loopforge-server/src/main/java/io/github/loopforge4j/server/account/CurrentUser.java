package io.github.loopforge4j.server.account;

import java.security.Principal;

/**
 * Authenticated caller. Configured API tokens authenticate as a service principal with
 * administrator rights; account tokens as the account.
 *
 * @param id account id, {@code null} for the service principal
 */
public record CurrentUser(String id, String username, Role role, boolean service) implements Principal {

    public static final CurrentUser SERVICE = new CurrentUser(null, "operator", Role.ADMIN, true);

    public static CurrentUser of(User user) {
        return new CurrentUser(user.id(), user.username(), user.role(), false);
    }

    public boolean admin() {
        return role == Role.ADMIN;
    }

    @Override
    public String getName() {
        return username;
    }
}
