package io.github.loopforge4j.core.runtime.git;

import java.util.Map;

/**
 * Credentials available to one run. They live only in memory and in the environment of the
 * processes the run starts; they are never written to run state, events or workspace files. SSH keys
 * stay in the JVM: commands reach them through an {@link SshAgent} socket that exists only while
 * the command runs.
 *
 * @param https token per host ({@code github.com} → token); username defaults to {@code x-access-token}
 * @param sshPrivateKey the deployment's private key for {@code ssh://} and scp-style remotes of hosts
 *     without a personal key
 * @param authorName commit author for run commits
 * @param sshKeys personal private keys per SSH host (lower case); such a host never uses the
 *     deployment key, and its host key must be pinned
 * @param sshKnownHosts {@code known_hosts} lines the administrator trusts, besides the built-in
 *     GitHub keys
 */
public record GitCredentials(
        Map<String, HttpsCredential> https,
        String sshPrivateKey,
        String authorName,
        String authorEmail,
        Map<String, String> sshKeys,
        String sshKnownHosts) {

    public static final GitCredentials NONE = new GitCredentials(Map.of(), null, null, null);

    public GitCredentials {
        https = https == null ? Map.of() : Map.copyOf(https);
        sshKeys = sshKeys == null ? Map.of() : Map.copyOf(sshKeys);
        sshKnownHosts = sshKnownHosts == null ? "" : sshKnownHosts;
    }

    /** HTTPS tokens and a deployment SSH key only. */
    public GitCredentials(
            Map<String, HttpsCredential> https, String sshPrivateKey, String authorName, String authorEmail) {
        this(https, sshPrivateKey, authorName, authorEmail, Map.of(), "");
    }

    /** Whether any SSH identity is configured. */
    public boolean hasSsh() {
        return !sshKeys.isEmpty() || sshPrivateKey != null && !sshPrivateKey.isBlank();
    }

    /** A copy with other HTTPS tokens and personal SSH keys (the deployment's key and pins are kept). */
    public GitCredentials with(Map<String, HttpsCredential> newHttps, Map<String, String> newSshKeys) {
        return new GitCredentials(newHttps, sshPrivateKey, authorName, authorEmail, newSshKeys, sshKnownHosts);
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
        return "GitCredentials[hosts=" + https.keySet() + ", ssh=" + (sshPrivateKey != null) + ", sshHosts="
                + sshKeys.keySet() + "]";
    }
}
