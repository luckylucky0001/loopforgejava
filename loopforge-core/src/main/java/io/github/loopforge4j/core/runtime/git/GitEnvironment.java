package io.github.loopforge4j.core.runtime.git;

import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Environment that gives every process of a run (scripts, gates, agent CLIs) access to the run's
 * repositories: a credential helper selected through {@code GIT_CONFIG_*} variables (no config file
 * is written), tokens as environment variables keyed by host, {@code GH_TOKEN} for the GitHub CLI,
 * an SSH command with the run's key, the commit identity, and the work-branch manifest.
 */
public final class GitEnvironment {

    static final String HELPER = "git-credential-loopforge";
    public static final String DEFAULT_AUTHOR_NAME = "LoopForge4J";
    public static final String DEFAULT_AUTHOR_EMAIL = "loopforge4j@users.noreply.local";
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");

    private GitEnvironment() {}

    /** Variable name holding the token of {@code host}. */
    static String tokenVariable(String host) {
        return "LOOPFORGE_GIT_TOKEN__" + host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    static String userVariable(String host) {
        return "LOOPFORGE_GIT_USER__" + host.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    /**
     * Builds the environment and writes the (secret-free) helper script and, when configured, the SSH
     * key into the workspace control directory.
     */
    public static Map<String, String> build(
            Path workspace, GitCredentials credentials, List<RepositoryContext> repositories) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        Path control = workspace.resolve(LocalSandbox.CONTROL_DIR);
        if (!credentials.https().isEmpty()) {
            Path helper = control.resolve("bin").resolve(HELPER);
            writeHelper(helper);
            int i = 0;
            env.put("GIT_CONFIG_KEY_" + i, "credential.helper");
            env.put("GIT_CONFIG_VALUE_" + i++, "");
            env.put("GIT_CONFIG_KEY_" + i, "credential.helper");
            env.put("GIT_CONFIG_VALUE_" + i++, helper.toString());
            env.put("GIT_CONFIG_COUNT", Integer.toString(i));
            credentials.https().forEach((host, credential) -> {
                env.put(tokenVariable(host), credential.token());
                env.put(userVariable(host), credential.username());
            });
            GitCredentials.HttpsCredential github = credentials.https().get("github.com");
            if (github != null) {
                env.put("GH_TOKEN", github.token());
            }
        }
        if (credentials.sshPrivateKey() != null && !credentials.sshPrivateKey().isBlank()) {
            Path ssh = control.resolve("ssh");
            Files.createDirectories(ssh);
            Path key = ssh.resolve("id_loopforge");
            writePrivate(key, credentials.sshPrivateKey().strip() + "\n");
            Path knownHosts = ssh.resolve("known_hosts");
            if (!Files.exists(knownHosts)) {
                writePrivate(knownHosts, "");
            }
            env.put(
                    "GIT_SSH_COMMAND",
                    "ssh -i " + key + " -o IdentitiesOnly=yes -o BatchMode=yes"
                            + " -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=" + knownHosts);
        }
        // Like upstream: an explicit account name and a non-deliverable address, never an inferred identity.
        String name = blank(credentials.authorName()) ? DEFAULT_AUTHOR_NAME : credentials.authorName();
        String email = blank(credentials.authorEmail()) ? DEFAULT_AUTHOR_EMAIL : credentials.authorEmail();
        env.put("GIT_AUTHOR_NAME", name);
        env.put("GIT_COMMITTER_NAME", name);
        env.put("GIT_AUTHOR_EMAIL", email);
        env.put("GIT_COMMITTER_EMAIL", email);
        env.put("LOOPFORGE_WORK_BRANCHES", manifest(repositories));
        return env;
    }

    /** Per-repository variables for commands that run inside one checkout. */
    public static Map<String, String> forRepository(RepositoryContext repository) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LOOPFORGE_REPOSITORY_ID", repository.repositoryId());
        env.put("LOOPFORGE_REPOSITORY_NAME", repository.name());
        env.put("LOOPFORGE_REPOSITORY_DIR", repository.workspaceDir());
        env.put(
                "LOOPFORGE_WORK_BRANCH",
                repository.writableWorkBranch() == null ? "" : repository.writableWorkBranch());
        env.put("LOOPFORGE_BASE_BRANCH", repository.baseBranch());
        return env;
    }

    /** {@code dir=workBranch} lines for run-scoped commands. */
    static String manifest(List<RepositoryContext> repositories) {
        StringBuilder b = new StringBuilder();
        for (RepositoryContext repository : repositories) {
            if (!b.isEmpty()) {
                b.append('\n');
            }
            b.append(repository.workspaceDir())
                    .append('=')
                    .append(repository.writableWorkBranch() == null ? "" : repository.writableWorkBranch());
        }
        return b.toString();
    }

    /** Removes userinfo from URLs so command output never echoes embedded credentials. */
    public static String redact(String text) {
        return text == null ? "" : URL_CREDENTIALS.matcher(text).replaceAll("$1***@");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void writeHelper(Path helper) throws IOException {
        if (Files.exists(helper)) {
            return;
        }
        Files.createDirectories(helper.getParent());
        // Reads host=... from git, answers with the token of that host from the environment.
        String script = """
                #!/bin/sh
                [ "$1" = get ] || exit 0
                host=
                while IFS= read -r line && [ -n "$line" ]; do
                  case "$line" in host=*) host="${line#host=}" ;; esac
                done
                key=$(printf '%s' "$host" | tr 'A-Z' 'a-z' | tr -c 'a-z0-9' '_')
                eval "token=\\${LOOPFORGE_GIT_TOKEN__$key:-}"
                eval "user=\\${LOOPFORGE_GIT_USER__$key:-x-access-token}"
                [ -n "$token" ] || exit 0
                printf 'username=%s\\npassword=%s\\n' "$user" "$token"
                """;
        Files.writeString(helper, script, StandardCharsets.UTF_8);
        helper.toFile().setExecutable(true, true);
    }

    private static void writePrivate(Path file, String content) throws IOException {
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
