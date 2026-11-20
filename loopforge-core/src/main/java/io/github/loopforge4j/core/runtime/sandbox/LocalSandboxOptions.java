package io.github.loopforge4j.core.runtime.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Configuration of {@link LocalSandboxProvider}.
 *
 * @param root directory that holds one workspace per run
 * @param passthroughEnv host variables forwarded to every process (never secrets)
 * @param privateHome give each provider label its own HOME inside the workspace control directory,
 *     isolating CLI configuration and logins from the operator account
 * @param defaultTimeout wall-clock limit when a command does not set one
 */
public record LocalSandboxOptions(
        Path root, List<String> passthroughEnv, boolean privateHome, Duration defaultTimeout) {

    public static final List<String> DEFAULT_PASSTHROUGH =
            List.of("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TERM", "USER", "LOGNAME", "SHELL");

    public LocalSandboxOptions {
        Objects.requireNonNull(root, "root");
        passthroughEnv = passthroughEnv == null ? DEFAULT_PASSTHROUGH : List.copyOf(passthroughEnv);
        defaultTimeout = defaultTimeout == null ? Duration.ofHours(2) : defaultTimeout;
    }

    public static LocalSandboxOptions defaults(Path root) {
        return new LocalSandboxOptions(root, DEFAULT_PASSTHROUGH, true, Duration.ofHours(2));
    }
}
