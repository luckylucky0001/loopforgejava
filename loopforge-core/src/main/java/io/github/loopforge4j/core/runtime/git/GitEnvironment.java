package io.github.loopforge4j.core.runtime.git;

import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * The environment of one command (or one batch of platform Git operations) and the SSH agent
     * that serves it: close it when the command ends, which removes the agent's socket and files.
     */
    public static final class Binding implements AutoCloseable {
        private final Map<String, String> environment;
        private final SshAgent agent;
        private final Path files;
        private final Path workspace;

        Binding(Map<String, String> environment, SshAgent agent, Path files, Path workspace) {
            this.environment = java.util.Collections.unmodifiableMap(environment);
            this.agent = agent;
            this.files = files;
            this.workspace = workspace;
        }

        /** An environment without an SSH agent. */
        public static Binding of(Map<String, String> environment) {
            return new Binding(new LinkedHashMap<>(environment), null, null, null);
        }

        public Map<String, String> environment() {
            return environment;
        }

        @Override
        public void close() {
            if (agent != null) {
                agent.close();
            }
            if (files != null) {
                try {
                    new io.github.loopforge4j.core.runtime.ManagedFiles(workspace).delete(files);
                } catch (IOException e) {
                    // the workspace is released with the run
                }
            }
        }
    }

    /**
     * Builds the environment and writes the (secret-free) helper script and SSH configuration into
     * the workspace control directory; SSH keys are served by an agent until the binding is closed.
     */
    public static Binding bind(Path workspace, GitCredentials credentials, List<RepositoryContext> repositories)
            throws IOException {
        Map<String, String> env = build(workspace, credentials, repositories);
        if (!credentials.hasSsh()) {
            return new Binding(env, null, null, null);
        }
        return bindSsh(workspace, credentials, env);
    }

    /** The environment without SSH keys (tokens, identity and manifest only). */
    public static Map<String, String> build(
            Path workspace, GitCredentials credentials, List<RepositoryContext> repositories) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        Path control = workspace.resolve(LocalSandbox.CONTROL_DIR);
        if (!credentials.https().isEmpty()) {
            Path helper = control.resolve("bin").resolve(HELPER);
            writeHelper(workspace, helper);
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
            // the GitLab CLI (glab) of the first GitLab repository with a configured token
            for (RepositoryContext rc : repositories) {
                if (rc.provider() != io.github.loopforge4j.core.domain.RepositoryProvider.GITLAB) {
                    continue;
                }
                String host = RepositoryRemote.parse(rc.cloneUrl())
                        .map(RepositoryRemote::host)
                        .orElse(null);
                GitCredentials.HttpsCredential gitlab =
                        host == null ? null : credentials.https().get(host);
                if (gitlab != null) {
                    env.put("GITLAB_TOKEN", gitlab.token());
                    env.put("GITLAB_HOST", host);
                    break;
                }
            }
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

    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /**
     * Starts an agent with the personal keys (each only for its host, whose host key must be pinned)
     * and the deployment key (every other host, first contact accepted as before) and points ssh at a
     * generated configuration that offers each host only its own key.
     */
    private static Binding bindSsh(Path workspace, GitCredentials credentials, Map<String, String> env)
            throws IOException {
        io.github.loopforge4j.core.runtime.ManagedFiles files =
                new io.github.loopforge4j.core.runtime.ManagedFiles(workspace);
        // paths handed to commands use the workspace path as configured: a container mounts it at
        // that path, not at its resolved one
        Path base = workspace.toAbsolutePath().normalize();
        Path control = base.resolve(LocalSandbox.CONTROL_DIR);
        // a key file written by earlier versions must not outlive them
        files.delete(control.resolve("ssh").resolve("id_loopforge"));
        String id = java.util.HexFormat.of().formatHex(next());
        Path dir = control.resolve("ssh").resolve(id);
        files.directory(dir);
        // a Unix socket path has at most 107 bytes: deep workspaces use a short name at their root
        Path socket = control.resolve("s").resolve(id);
        if (socket.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > SshAgent.MAX_SOCKET_PATH) {
            socket = base.resolve(".lf" + id.substring(0, 6));
        } else {
            files.directory(control.resolve("s"));
        }
        List<SshKeys.Identity> identities = new java.util.ArrayList<>();
        StringBuilder config = new StringBuilder();
        java.util.List<String> personalHosts = new java.util.ArrayList<>();
        Path pinned = dir.resolve("known_hosts");
        files.write(
                pinned,
                SshKnownHosts.BUILT_IN + "\n" + credentials.sshKnownHosts().strip() + "\n",
                false);
        for (Map.Entry<String, String> entry : new java.util.TreeMap<>(credentials.sshKeys()).entrySet()) {
            String host = entry.getKey().toLowerCase(Locale.ROOT);
            if (!SAFE_HOST.matcher(host).matches()) {
                continue;
            }
            SshKeys.Identity identity;
            try {
                identity = SshKeys.parse(entry.getValue());
            } catch (IllegalArgumentException e) {
                continue;
            }
            identities.add(identity);
            personalHosts.add(host);
            Path pub = dir.resolve(host + ".pub");
            files.write(pub, identity.publicKeyLine() + "\n", false);
            config.append("Host ")
                    .append(host)
                    .append('\n')
                    .append("  IdentityFile ")
                    .append(quoted(pub))
                    .append('\n')
                    .append("  StrictHostKeyChecking yes\n")
                    .append("  UserKnownHostsFile ")
                    .append(quoted(pinned))
                    .append('\n');
        }
        config.append("Host *");
        personalHosts.forEach(h -> config.append(" !").append(h));
        config.append('\n');
        SshKeys.Identity deployment = null;
        if (credentials.sshPrivateKey() != null && !credentials.sshPrivateKey().isBlank()) {
            try {
                deployment = SshKeys.parse(credentials.sshPrivateKey());
            } catch (IllegalArgumentException e) {
                deployment = null;
            }
        }
        if (deployment != null) {
            identities.add(deployment);
            Path pub = dir.resolve("deployment.pub");
            files.write(pub, deployment.publicKeyLine() + "\n", false);
            Path learned = control.resolve("ssh").resolve("known_hosts");
            if (!Files.isRegularFile(learned, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                files.write(learned, "", false);
            }
            config.append("  IdentityFile ")
                    .append(quoted(pub))
                    .append('\n')
                    .append("  StrictHostKeyChecking accept-new\n")
                    .append("  UserKnownHostsFile ")
                    .append(quoted(learned))
                    .append(' ')
                    .append(quoted(pinned))
                    .append('\n');
        } else {
            config.append("  IdentityFile none\n")
                    .append("  StrictHostKeyChecking yes\n")
                    .append("  UserKnownHostsFile ")
                    .append(quoted(pinned))
                    .append('\n');
        }
        config.append("Host *\n")
                .append("  IdentityAgent ")
                .append(quoted(socket))
                .append('\n')
                .append("  IdentitiesOnly yes\n")
                .append("  BatchMode yes\n")
                .append("  GlobalKnownHostsFile /dev/null\n")
                .append("  PasswordAuthentication no\n")
                .append("  KbdInteractiveAuthentication no\n");
        Path configFile = dir.resolve("config");
        files.write(configFile, config.toString(), false);
        SshAgent agent;
        try {
            agent = SshAgent.start(socket, identities);
        } catch (IOException e) {
            env.put("GIT_SSH_VARIANT", "ssh");
            env.put(
                    "GIT_SSH_COMMAND",
                    "sh -c 'echo \"LoopForge: 无法启动 SSH agent（工作区路径过长？）；请改用 HTTPS 地址\" >&2; exit 1' --");
            return new Binding(env, null, dir, base);
        }
        env.put("GIT_SSH_VARIANT", "ssh");
        env.put("GIT_SSH_COMMAND", "ssh -F " + shellQuoted(configFile.toString()));
        return new Binding(env, agent, dir, base);
    }

    private static byte[] next() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static final Pattern SAFE_HOST = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?");

    /** An ssh_config argument (paths of a workspace never contain quotes). */
    private static String quoted(Path path) {
        return "\"" + path.toString().replace("\"", "") + "\"";
    }

    private static String shellQuoted(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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

    private static void writeHelper(Path workspace, Path helper) throws IOException {
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
        new io.github.loopforge4j.core.runtime.ManagedFiles(workspace).write(helper, script, true);
    }
}
