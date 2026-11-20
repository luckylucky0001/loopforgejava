package io.github.loopforge4j.core.runtime.git;

/**
 * Host keys trusted without configuration. Like upstream, only GitHub's published SSH host keys
 * (api.github.com/meta) are built in, for {@code github.com:22} and the {@code ssh.github.com:443}
 * fallback; an administrator adds other hosts with {@code loopforge.git.ssh-known-hosts-file}.
 */
public final class SshKnownHosts {

    public static final String BUILT_IN = String.join(
            "\n",
            "github.com,[ssh.github.com]:443 ssh-ed25519"
                    + " AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
            "github.com,[ssh.github.com]:443 ecdsa-sha2-nistp256"
                    + " AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
            "github.com,[ssh.github.com]:443 ssh-rsa"
                    + " AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=");

    private SshKnownHosts() {}

    /** Whether {@code knownHosts} (or the built-in keys) has an entry for {@code host} on port 22. */
    public static boolean pinned(String host, String knownHosts) {
        String wanted = host.toLowerCase(java.util.Locale.ROOT);
        for (String line : (BUILT_IN + "\n" + (knownHosts == null ? "" : knownHosts)).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("@revoked")) {
                continue;
            }
            String hosts = trimmed.startsWith("@") ? trimmed.split("\\s+")[1] : trimmed.split("\\s+")[0];
            for (String h : hosts.split(",")) {
                if (h.equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }
}
