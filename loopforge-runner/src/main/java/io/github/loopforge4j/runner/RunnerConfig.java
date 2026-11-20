package io.github.loopforge4j.runner;

import io.github.loopforge4j.core.runtime.git.GitCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings of an execution machine, from environment variables:
 *
 * <ul>
 *   <li>{@code LOOPFORGE_SERVER_URL}, {@code LOOPFORGE_RUNNER_TOKEN} (required)
 *   <li>{@code LOOPFORGE_RUNNER_WORKSPACES} (default {@code ~/.loopforge-runner/workspaces})
 *   <li>{@code LOOPFORGE_RUNNER_CAPACITY} concurrent runs (default 2)
 *   <li>{@code LOOPFORGE_RUNNER_DEFAULT_EXECUTOR} (default {@code claude})
 *   <li>{@code LOOPFORGE_RUNNER_PRIVATE_HOME} {@code true} to isolate CLI logins per run; by default
 *       CLIs use the machine owner's own logins, which is what a personal machine is for
 *   <li>{@code LOOPFORGE_RUNNER_GIT_HTTPS} {@code host=token,host=user:token}; {@code
 *       LOOPFORGE_RUNNER_GIT_SSH_KEY} path of a private key; {@code LOOPFORGE_RUNNER_GIT_AUTHOR}
 *       {@code Name <email>}
 * </ul>
 */
public record RunnerConfig(
        String serverUrl,
        String token,
        Path workspaces,
        int capacity,
        String defaultExecutor,
        boolean privateHome,
        GitCredentials git,
        Duration pollInterval,
        Duration heartbeatInterval,
        Duration nodeTimeout) {

    static final List<String> PASSTHROUGH =
            List.of("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TERM", "USER", "LOGNAME", "SHELL");

    public static RunnerConfig fromEnvironment(Map<String, String> env) throws IOException {
        String server = required(env, "LOOPFORGE_SERVER_URL");
        String token = required(env, "LOOPFORGE_RUNNER_TOKEN");
        Path workspaces = env.containsKey("LOOPFORGE_RUNNER_WORKSPACES")
                ? Path.of(env.get("LOOPFORGE_RUNNER_WORKSPACES"))
                : Path.of(System.getProperty("user.home"), ".loopforge-runner", "workspaces");
        Map<String, GitCredentials.HttpsCredential> https = new LinkedHashMap<>();
        for (String entry : env.getOrDefault("LOOPFORGE_RUNNER_GIT_HTTPS", "").split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String host = entry.substring(0, eq).strip().toLowerCase(java.util.Locale.ROOT);
            String value = entry.substring(eq + 1).strip();
            int colon = value.indexOf(':');
            https.put(
                    host,
                    colon > 0
                            ? new GitCredentials.HttpsCredential(value.substring(0, colon), value.substring(colon + 1))
                            : new GitCredentials.HttpsCredential(null, value));
        }
        String key = env.containsKey("LOOPFORGE_RUNNER_GIT_SSH_KEY")
                ? Files.readString(Path.of(env.get("LOOPFORGE_RUNNER_GIT_SSH_KEY")))
                : null;
        String author =
                env.getOrDefault("LOOPFORGE_RUNNER_GIT_AUTHOR", "LoopForge4J Runner <loopforge4j@users.noreply.local>");
        String name =
                author.contains("<") ? author.substring(0, author.indexOf('<')).strip() : author.strip();
        String email = author.contains("<") && author.contains(">")
                ? author.substring(author.indexOf('<') + 1, author.indexOf('>')).strip()
                : "loopforge4j@users.noreply.local";
        return new RunnerConfig(
                server,
                token,
                workspaces,
                Integer.parseInt(env.getOrDefault("LOOPFORGE_RUNNER_CAPACITY", "2")),
                env.getOrDefault("LOOPFORGE_RUNNER_DEFAULT_EXECUTOR", "claude"),
                Boolean.parseBoolean(env.getOrDefault("LOOPFORGE_RUNNER_PRIVATE_HOME", "false")),
                new GitCredentials(https, key, name, email),
                Duration.ofSeconds(3),
                Duration.ofSeconds(30),
                Duration.ofHours(2));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "RunnerConfig[server=" + serverUrl + ", workspaces=" + workspaces + ", capacity=" + capacity + "]";
    }
}
