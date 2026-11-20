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
        @DefaultValue Map<String, Map<String, String>> credentials) {

    /**
     * @param defaultExecutor executor id used when neither role nor node chooses one
     * @param nodeTimeout limit for one agent invocation or script
     * @param releaseWorkspaceOnFinish delete workspaces of succeeded and cancelled runs
     */
    public record Engine(
            @NotBlank @DefaultValue("claude") String defaultExecutor,
            @NotNull @DefaultValue("2h") Duration nodeTimeout,
            @NotNull @DefaultValue("500ms") Duration schedulerTick,
            @DefaultValue("true") boolean releaseWorkspaceOnFinish,
            @DefaultValue("true") boolean recoverOnStartup) {}

    /**
     * @param passthroughEnv host variables forwarded to processes; never list secrets here
     * @param privateHome isolate CLI config and logins per run instead of using the server's HOME
     */
    public record Sandbox(
            @DefaultValue({"PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TERM", "USER", "LOGNAME", "SHELL"})
            List<String> passthroughEnv,

            @DefaultValue("true") boolean privateHome) {}

    /**
     * @param apiTokens accepted bearer tokens (at least 32 characters each)
     * @param tokenFile when no token is configured, a random token is generated once and stored here
     *     with owner-only permissions; it is reused across restarts
     * @param publicDocs expose the OpenAPI document and Swagger UI without a token
     * @param maxRequestSize largest accepted request body; larger ones get 413 before parsing
     */
    public record Security(
            @DefaultValue List<String> apiTokens,
            @NotNull @DefaultValue("./data/api-token") Path tokenFile,
            @DefaultValue("true") boolean publicDocs,
            @NotNull @DefaultValue("8MB") org.springframework.util.unit.DataSize maxRequestSize) {}

    /** Replaces the launch command of a built-in executor (e.g. an absolute binary path). */
    public record ExecutorOverride(String binary, List<String> args, String model, String effort) {}
}
