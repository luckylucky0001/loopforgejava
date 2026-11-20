package io.github.loopforge4j.runner;

import io.github.loopforge4j.core.engine.AdmissionPolicy;
import io.github.loopforge4j.core.engine.EngineSettings;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.InMemoryInstanceRegistry;
import java.net.InetAddress;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * An execution machine: the engine itself, with the server's store of this machine's runs, the
 * server's catalog, local sandboxes and the CLIs installed here. It only ever opens outbound
 * connections; it claims the runs assigned to it and executes them like a server instance would.
 */
public class RunnerAgent implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RunnerAgent.class);

    private final RunnerConfig config;
    private final RunnerClient client;
    private final ExecutorRegistry executors;
    private final ScheduledExecutorService loops =
            Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());
    private volatile RunEngine engine;
    private volatile String runnerId;

    public RunnerAgent(RunnerConfig config) {
        this(config, ExecutorRegistry.withBuiltIns(CredentialProvider.NONE));
    }

    /** With an explicit executor registry (tests, embedding). */
    public RunnerAgent(RunnerConfig config, ExecutorRegistry executors) {
        this.config = config;
        this.client = new RunnerClient(config.serverUrl(), config.token());
        this.executors = executors;
    }

    /** Registers with the server and starts claiming runs. */
    public RunnerAgent start() throws Exception {
        JsonNode me = heartbeat();
        runnerId = me.path("runnerId").asString();
        Files.createDirectories(config.workspaces());
        String host = InetAddress.getLocalHost().getHostName();
        String instance = "runner-" + runnerId + "-" + host + "-"
                + ProcessHandle.current().pid();
        engine = RunEngine.builder()
                .runStore(new RemoteRunStore(client, Clock.systemUTC()))
                .catalogStore(new RemoteCatalogStore(client))
                .executors(executors)
                .sandbox(new LocalSandboxProvider(new LocalSandboxOptions(
                        config.workspaces(), RunnerConfig.PASSTHROUGH, config.privateHome(), config.nodeTimeout())))
                .instances(new InMemoryInstanceRegistry())
                .claimOrphans(true)
                .runnerId(runnerId)
                .admission(new AdmissionPolicy(config.capacity(), 0))
                .gitCredentials(run -> config.git())
                .delivery(RemoteServices.delivery(client))
                .platformMcp(RemoteServices.platformMcp(client))
                .previews(new RemotePreviewStore(client))
                .settings(new EngineSettings(
                        instance,
                        config.defaultExecutor(),
                        config.nodeTimeout(),
                        Duration.ofMillis(500),
                        true,
                        Duration.ofSeconds(60),
                        config.pollInterval(),
                        host,
                        false))
                .build();
        long poll = config.pollInterval().toMillis();
        loops.scheduleWithFixedDelay(this::claim, 0, poll, TimeUnit.MILLISECONDS);
        long beat = config.heartbeatInterval().toMillis();
        loops.scheduleWithFixedDelay(this::beat, beat, beat, TimeUnit.MILLISECONDS);
        log.info("runner {} ({}) connected to {}", me.path("name").asString(), runnerId, config.serverUrl());
        return this;
    }

    public String runnerId() {
        return runnerId;
    }

    public RunEngine engine() {
        return engine;
    }

    private void claim() {
        try {
            engine.recoverInterruptedRuns();
        } catch (RuntimeException e) {
            log.warn("cannot claim runs: {}", e.getMessage());
        }
    }

    private void beat() {
        try {
            heartbeat();
        } catch (Exception e) {
            log.warn("heartbeat failed: {}", e.getMessage());
        }
    }

    private JsonNode heartbeat() throws java.net.UnknownHostException {
        String version = RunnerAgent.class.getPackage().getImplementationVersion();
        return client.post(
                "/heartbeat",
                Map.of(
                        "version", version == null ? "dev" : version,
                        "host", InetAddress.getLocalHost().getHostName(),
                        "executors",
                                executors.definitions().stream()
                                        .filter(RunnerAgent::installed)
                                        .map(d -> d.id())
                                        .toList(),
                        "capacity", config.capacity()));
    }

    /** Whether the executor's CLI is on this machine's PATH (built-in executors always are). */
    static boolean installed(io.github.loopforge4j.core.domain.ExecutorDefinition executor) {
        if (executor.binary() == null) {
            return true;
        }
        java.nio.file.Path binary = java.nio.file.Path.of(executor.binary());
        if (binary.isAbsolute()) {
            return Files.isExecutable(binary);
        }
        String path = System.getenv("PATH");
        return path != null
                && java.util.Arrays.stream(path.split(java.io.File.pathSeparator))
                        .anyMatch(dir -> !dir.isBlank()
                                && Files.isExecutable(java.nio.file.Path.of(dir).resolve(executor.binary())));
    }

    /** Parks running work so the next start of this machine resumes it, then disconnects. */
    @Override
    public void close() {
        loops.shutdownNow();
        if (engine != null) {
            engine.close();
        }
    }
}
