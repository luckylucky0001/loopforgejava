package io.github.loopforge4j.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration under {@code loopforge.*}.
 *
 * @param workspaceRoot directory holding one workspace per run
 * @param instanceId identifies this process in run ownership records; defaults to host + pid
 * @param demoCatalog import the bundled demo SOPs on startup (insert-if-absent, never overwrites)
 */
@Validated
@ConfigurationProperties("loopforge")
public record LoopForgeProperties(
        @NotNull @DefaultValue("./data/workspaces") Path workspaceRoot,
        String instanceId,
        @DefaultValue("true") boolean demoCatalog,
        @Valid @DefaultValue Engine engine,
        @Valid @DefaultValue Sandbox sandbox,
        @Valid @DefaultValue Security security,
        @DefaultValue Map<String, ExecutorOverride> executors,
        @DefaultValue Map<String, Map<String, String>> credentials,
        @Valid @DefaultValue Git git,
        @Valid @DefaultValue GitHub github,
        @Valid @DefaultValue Mcp mcp,
        @Valid @DefaultValue Retention retention) {

    /**
     * @param defaultExecutor executor id used when neither role nor node chooses one
     * @param nodeTimeout limit for one agent invocation or script
     * @param releaseWorkspaceOnFinish delete workspaces of succeeded and cancelled runs
     * @param leaseTtl how long a run's execution lease survives without heartbeat; another instance
     *     takes over a run at most this long after its owner died
     * @param claimInterval how often orphaned runs are looked for
     * @param workspaceHost name of the file system holding workspaces (host name by default)
     * @param sharedWorkspaces every instance mounts the workspace root at the same path, so runs may
     *     move between hosts
     */
    public record Engine(
            @NotBlank @DefaultValue("claude") String defaultExecutor,
            @NotNull @DefaultValue("2h") Duration nodeTimeout,
            @NotNull @DefaultValue("500ms") Duration schedulerTick,
            @DefaultValue("true") boolean releaseWorkspaceOnFinish,
            @DefaultValue("true") boolean recoverOnStartup,
            @NotNull @DefaultValue("60s") Duration leaseTtl,
            @NotNull @DefaultValue("10s") Duration claimInterval,
            String workspaceHost,
            @DefaultValue("false") boolean sharedWorkspaces) {}

    /**
     * @param passthroughEnv host variables forwarded to processes; never list secrets here
     * @param privateHome isolate CLI config and logins per run instead of using the server's HOME
     */
    public record Sandbox(
            @DefaultValue({"PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TERM", "USER", "LOGNAME", "SHELL"})
            List<String> passthroughEnv,

            @DefaultValue("true") boolean privateHome,
            @Valid @DefaultValue Docker docker) {}

    /**
     * Docker sandbox profile; workflows select it with {@code sandboxProfileId}.
     *
     * @param enabled register the profile (needs a reachable docker daemon)
     * @param image image with sh, sleep and the agent CLIs the SOPs use
     * @param mountJavaHome mount the server JDK read-only so the gate MCP server runs in the
     *     container (glibc-based images)
     */
    public record Docker(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("docker") String profile,
            @DefaultValue("debian:bookworm-slim") String image,
            @DefaultValue("docker") String binary,
            @DefaultValue("bridge") String network,
            String memory,
            String cpus,
            @DefaultValue("0") int pidsLimit,
            @DefaultValue("false") boolean readOnlyRoot,
            String user,
            @DefaultValue("true") boolean mountJavaHome) {}

    /**
     * @param apiTokens accepted bearer tokens (at least 32 characters each)
     * @param tokenFile when no token is configured, a random token is generated once and stored here
     *     with owner-only permissions; it is reused across restarts
     * @param publicDocs expose the OpenAPI document and Swagger UI without a token
     * @param maxRequestSize largest accepted request body; larger ones get 413 before parsing
     * @param sessionTtl lifetime of a login session token
     */
    public record Security(
            @DefaultValue List<String> apiTokens,
            @NotNull @DefaultValue("./data/api-token") Path tokenFile,
            @DefaultValue("true") boolean publicDocs,
            @NotNull @DefaultValue("8MB") org.springframework.util.unit.DataSize maxRequestSize,
            @NotNull @DefaultValue("12h") Duration sessionTtl) {}

    /**
     * Git access of runs. Tokens are injected through a credential helper into the processes of a
     * run only; they never appear in clone URLs, run state or logs.
     *
     * @param https token per host, e.g. {@code [{host: github.com, token: ${GITHUB_TOKEN}}]} (a list,
     *     so it binds from environment variables such as {@code LOOPFORGE_GIT_HTTPS_0_TOKEN})
     * @param sshPrivateKeyFile OpenSSH key for ssh:// and scp-style remotes
     * @param allowLocalRemotes accept file:// and local-path repositories (development and tests only)
     */
    public record Git(
            @Valid @DefaultValue List<HttpsCredential> https,
            Path sshPrivateKeyFile,
            @DefaultValue("LoopForge4J") String authorName,
            @DefaultValue("loopforge4j@users.noreply.local") String authorEmail,
            @DefaultValue("false") boolean allowLocalRemotes) {}

    /** Token-based credential of one Git host; username defaults to {@code x-access-token}. */
    public record HttpsCredential(@NotBlank String host, String username, String token) {
        @Override
        public String toString() {
            return "HttpsCredential[host=" + host + ", username=" + username + ", token=***]";
        }
    }

    /**
     * Pull request lookup for delivery tracking and MR gates.
     *
     * @param apiUrl REST API root; {@code https://<host>/api/v3} for GitHub Enterprise
     * @param refreshInterval how often open pull requests of succeeded runs are re-checked for merges
     */
    public record GitHub(
            @NotNull @DefaultValue("https://api.github.com") java.net.URI apiUrl,
            @NotNull @DefaultValue("5m") Duration refreshInterval) {}

    /**
     * @param secrets values for {@code ${secret:NAME}} placeholders in MCP server definitions; they
     *     reach only the MCP server processes of an invocation, never the catalog or run data
     */
    public record Mcp(@DefaultValue Map<String, String> secrets) {
        @Override
        public String toString() {
            return "Mcp[secrets=" + secrets.keySet() + "]";
        }
    }

    /**
     * @param telemetry age after which executor telemetry (output, tool, process, hook events) is
     *     deleted; the lifecycle and gate audit trail is kept. Zero keeps everything.
     * @param runs age after which terminal runs are deleted with their events, artifacts and
     *     workspace. Zero (default) keeps runs forever.
     * @param interval how often the policy is applied
     */
    public record Retention(
            @NotNull @DefaultValue("30d") Duration telemetry,
            @NotNull @DefaultValue("0s") Duration runs,
            @NotNull @DefaultValue("1h") Duration interval) {}

    /** Replaces the launch command of a built-in executor (e.g. an absolute binary path). */
    public record ExecutorOverride(String binary, List<String> args, String model, String effort) {}
}
