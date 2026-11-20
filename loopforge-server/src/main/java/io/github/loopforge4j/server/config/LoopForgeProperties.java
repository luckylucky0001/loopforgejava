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
 * @param publicUrl the console's external base URL; notifications link to runs with it
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
        @Valid @DefaultValue Retention retention,
        @DefaultValue List<GitLabHost> gitlab,
        @DefaultValue List<ModelPrice> pricing,
        @Valid @DefaultValue Notifications notifications,
        @Valid @DefaultValue Knowledge knowledge,
        String publicUrl,
        @Valid @DefaultValue Tapd tapd,
        @Valid @DefaultValue Trackers trackers,
        @Valid @DefaultValue Assistant assistant) {

    /**
     * The conversation assistant (upstream Main Bot).
     *
     * @param sandboxProfile workspace profile of new conversations (members still need an isolated
     *     one)
     * @param executor executor of the assistant, the engine default when empty
     * @param turnTimeout limit for one turn
     * @param history messages replayed when no CLI session can be continued
     * @param clarifyTimeout limit for one round of requirement clarification (same profile and
     *     executor)
     */
    public record Assistant(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("local") String sandboxProfile,
            String executor,
            @NotNull @DefaultValue("30m") Duration turnTimeout,
            @DefaultValue("30") int history,
            @NotNull @DefaultValue("10m") Duration clarifyTimeout) {}

    /**
     * TAPD open API account (https://open.tapd.cn): Basic authentication with the API user.
     *
     * @param webUrl where people open stories; links in requirements point there
     */
    public record Tapd(
            @NotNull @DefaultValue("https://api.tapd.cn") java.net.URI apiUrl,
            @DefaultValue("https://www.tapd.cn") String webUrl,
            String apiUser,
            String apiPassword) {
        @Override
        public String toString() {
            return "Tapd[apiUrl=" + apiUrl + ", apiUser=" + apiUser + "]";
        }
    }

    /**
     * @param autoSync pull configured trackers periodically (one instance at a time)
     * @param syncInterval how often
     */
    public record Trackers(
            @DefaultValue("true") boolean autoSync,
            @NotNull @DefaultValue("10m") Duration syncInterval) {}

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
     * @param maxActiveRuns running top-level runs in the deployment before new ones queue (0: no limit)
     * @param maxActiveRunsPerProject the same per project
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
            @DefaultValue("false") boolean sharedWorkspaces,
            @DefaultValue("0") int maxActiveRuns,
            @DefaultValue("0") int maxActiveRunsPerProject) {}

    /**
     * @param passthroughEnv host variables forwarded to processes; never list secrets here
     * @param privateHome isolate CLI config and logins per run instead of using the server's HOME
     */
    public record Sandbox(
            @DefaultValue({"PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TERM", "USER", "LOGNAME", "SHELL"})
            List<String> passthroughEnv,

            @DefaultValue("true") boolean privateHome,
            @Valid @DefaultValue Docker docker,
            @Valid @DefaultValue Bwrap bwrap) {}

    /**
     * Bubblewrap sandbox profile (Linux): read-only host, writable run workspace only, private
     * /tmp, new user/PID/IPC/UTS namespaces; other workspaces are hidden.
     *
     * @param hiddenPaths directories inside the read-only paths to empty (a secrets directory
     *     under /etc, say)
     * @param hideServerHome kept for compatibility: the server's HOME is never mounted
     * @param shareNetwork keep the host network; {@code false} gives processes only loopback
     * @param readOnlyPaths the only host directories processes can see, read-only (default: /usr,
     *     /bin, /sbin, /lib*, /etc, /opt; the JDK is added); never list /run, /var or a home
     *     directory — sockets there are reachable through a read-only mount
     */
    public record Bwrap(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("bwrap") String profile,
            @DefaultValue("bwrap") String binary,
            @DefaultValue List<Path> hiddenPaths,
            @DefaultValue("true") boolean hideServerHome,
            @DefaultValue("true") boolean shareNetwork,
            @DefaultValue List<Path> readOnlyPaths) {}

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
     * @param membersRequireIsolation non-administrators may only save and start SOPs whose sandbox
     *     profile is isolated: the local sandbox runs as the server's OS user, so anything a script or
     *     agent does there can read the server's token, database and configuration
     * @param isolatedProfiles sandbox profiles that confine runs (containers)
     */
    public record Security(
            @DefaultValue List<String> apiTokens,
            @NotNull @DefaultValue("./data/api-token") Path tokenFile,
            @DefaultValue("true") boolean publicDocs,
            @NotNull @DefaultValue("8MB") org.springframework.util.unit.DataSize maxRequestSize,
            @NotNull @DefaultValue("12h") Duration sessionTtl,
            @DefaultValue("true") boolean membersRequireIsolation,
            @DefaultValue("docker") List<String> isolatedProfiles,
            @Valid @DefaultValue Oidc oidc) {}

    /**
     * OpenID Connect single sign-on (authorization code flow with PKCE).
     *
     * @param issuer provider URL; its {@code /.well-known/openid-configuration} is read
     * @param redirectUri this server's callback, {@code <public-url>/api/v1/auth/oidc/callback}
     *     by default
     * @param usernameClaim claim that names new accounts
     * @param autoProvision create a member account on first sign-in
     * @param allowedEmailDomains when set, only verified e-mail addresses of these domains may sign
     *     in
     * @param linkByEmail let a first sign-in take over an existing account with the same verified
     *     e-mail address (off: a matching account is never linked implicitly)
     */
    public record Oidc(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("SSO") String displayName,
            String issuer,
            String clientId,
            String clientSecret,
            String redirectUri,
            @DefaultValue("openid profile email") String scopes,
            @DefaultValue("preferred_username") String usernameClaim,
            @DefaultValue("true") boolean autoProvision,
            @DefaultValue List<String> allowedEmailDomains,
            @DefaultValue("false") boolean linkByEmail) {
        @Override
        public String toString() {
            return "Oidc[enabled=" + enabled + ", issuer=" + issuer + ", clientId=" + clientId + "]";
        }
    }

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
     * @param platformEnabled serve {@code /mcp} and let roles bind the platform MCP
     * @param sandboxUrl base URL sandboxed agents reach this server at (for example {@code
     *     http://host.docker.internal:8080}); without it only local-sandbox agents get platform
     *     servers, at the loopback address
     * @param internalUrl base URL {@code /mcp} forwards tool calls to (default: loopback, the
     *     server's own port)
     * @param agentTokenTtl lifetime of the run tokens agents get; a token also dies with its run
     */
    public record Mcp(
            @DefaultValue Map<String, String> secrets,
            @DefaultValue("true") boolean platformEnabled,
            String sandboxUrl,
            String internalUrl,
            @NotNull @DefaultValue("12h") Duration agentTokenTtl) {
        @Override
        public String toString() {
            return "Mcp[secrets=" + secrets.keySet() + ", platformEnabled=" + platformEnabled + ", sandboxUrl="
                    + sandboxUrl + "]";
        }
    }

    /**
     * @param token upstream knowledge base token; when set it overrides the one saved in settings
     *     and is never stored in the database
     * @param timeout limit for one upstream knowledge base call
     */
    public record Knowledge(
            String token, @NotNull @DefaultValue("30s") Duration timeout) {
        @Override
        public String toString() {
            return "Knowledge[token=" + (token == null || token.isBlank() ? "unset" : "***") + ", timeout=" + timeout
                    + "]";
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

    /**
     * A GitLab or GitLab-compatible host (e.g. 工蜂) for MR delivery; its token comes from the
     * matching {@code loopforge.git.https} entry.
     *
     * @param apiUrl API root; {@code https://<host>/api/v4} when empty
     */
    public record GitLabHost(@NotBlank String host, java.net.URI apiUrl) {}

    /**
     * Price per million tokens of a model, replacing or extending the built-in table used to
     * estimate cost when a CLI reports none.
     */
    public record ModelPrice(
            @NotBlank String model,
            @DefaultValue("USD") String currency,
            double input,
            double output,
            double cacheRead,
            double cacheWrite) {}

    /**
     * @param allowPrivateTargets let webhooks call loopback, private and link-local addresses (only
     *     for trusted maintainers: otherwise a webhook is a probe into the server's network)
     */
    public record Notifications(@DefaultValue("false") boolean allowPrivateTargets) {}

    /** Replaces the launch command of a built-in executor (e.g. an absolute binary path). */
    public record ExecutorOverride(String binary, List<String> args, String model, String effort) {}
}
