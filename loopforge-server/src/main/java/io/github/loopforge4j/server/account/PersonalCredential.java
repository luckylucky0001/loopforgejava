package io.github.loopforge4j.server.account;

import java.time.Instant;
import java.util.List;

/**
 * A person's own credential as the API shows it: never the secret, only a hint.
 *
 * @param kind {@code executor:<executor kind>} or {@code git:<host>}
 * @param baseUrl model gateway or API base URL (executor credentials)
 * @param username account name on the Git host (Git credentials)
 * @param hint the secret's last characters, e.g. {@code …a1b2}
 * @param variables names of the extra environment variables that are set
 */
public record PersonalCredential(
        String kind, String baseUrl, String username, String hint, List<String> variables, Instant updatedAt) {

    public PersonalCredential {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
