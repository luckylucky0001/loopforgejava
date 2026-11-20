package io.github.loopforge4j.core.runtime.git;

import java.util.Map;

/**
 * Credentials available to one run. They live only in memory and in the environment of the
 * processes the run starts; they are never written to run state, events or workspace files (the SSH
 * key is the exception: OpenSSH needs a key file, which is created owner-only in the control
 * directory).
 *
 * @param https token per host ({@code github.com} → token); username defaults to {@code x-access-token}
 * @param sshPrivateKey OpenSSH private key used for {@code ssh://} and scp-style remotes
 * @param authorName commit author for run commits
 */
public record GitCredentials(
        Map<String, HttpsCredential> https, String sshPrivateKey, String authorName, String authorEmail) {

    public static final GitCredentials NONE = new GitCredentials(Map.of(), null, null, null);

    public GitCredentials {
        https = https == null ? Map.of() : Map.copyOf(https);
    }

    /** Token-based HTTPS credential for one host. */
    public record HttpsCredential(String username, String token) {
        public HttpsCredential {
            username = username == null || username.isBlank() ? "x-access-token" : username;
        }

        @Override
        public String toString() {
            return "HttpsCredential[username=" + username + ", token=***]";
        }
    }

    @Override
    public String toString() {
        return "GitCredentials[hosts=" + https.keySet() + ", ssh=" + (sshPrivateKey != null) + "]";
    }
}
