package io.github.loopforge4j.server.account;

import java.time.Instant;
import java.util.List;

/**
 * A person's own credential as the API shows it: never the secret, only a hint.
 *
 * @param kind {@code executor:<executor kind>}, {@code git:<host>} or {@code ssh:<host>}
 * @param baseUrl model gateway or API base URL (executor credentials)
 * @param username account name on the Git host (Git credentials)
 * @param hint the secret's last characters, e.g. {@code …a1b2}
 * @param variables names of the extra environment variables that are set
 * @param publicKey the public half of an SSH key ({@code ssh:<host>}), to add to the Git host
 * @param trusted for an SSH key: whether the platform trusts the host's key (otherwise SSH is refused)
 */
public record PersonalCredential(
        String kind,
        String baseUrl,
        String username,
        String hint,
        List<String> variables,
        Instant updatedAt,
        String publicKey,
        Boolean trusted) {

    public PersonalCredential {
        variables = variables == null ? List.of() : List.copyOf(variables);
    }
}
