package io.github.loopforge4j.server.config;

import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.engine.EngineSettings;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.engine.RunListener;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.git.GitCredentialProvider;
import io.github.loopforge4j.core.runtime.git.GitCredentials;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.server.delivery.GitHubDeliveryProvider;
import io.github.loopforge4j.server.delivery.GitLabDeliveryProvider;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-independent engine from {@code loopforge-core} into the Spring context. */
@Configuration(proxyBeanMethods = false)
class EngineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngineConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Credentials come from configuration (typically environment variables), never from run data. */
    @Bean
    CredentialProvider credentialProvider(LoopForgeProperties properties) {
        Map<String, Map<String, String>> credentials = properties.credentials();
        return kind -> credentials.getOrDefault(kind, Map.of());
    }

    @Bean
    ExecutorRegistry executorRegistry(LoopForgeProperties properties, CredentialProvider credentials) {
        ExecutorRegistry registry = ExecutorRegistry.withBuiltIns(credentials);
        properties.executors().forEach((id, override) -> {
            ExecutorDefinition base = registry.definition(id)
                    .orElseThrow(() -> new IllegalStateException("loopforge.executors." + id + ": unknown executor"));
            registry.define(new ExecutorDefinition(
                    base.id(),
                    base.name(),
                    base.kind(),
                    override.binary() == null ? base.binary() : override.binary(),
                    override.args() == null ? base.args() : override.args(),
                    base.protocol(),
                    override.model() == null ? base.model() : override.model(),
                    override.effort() == null ? base.effort() : override.effort()));
        });
        return registry;
    }

    @Bean
    LocalSandboxProvider localSandboxProvider(LoopForgeProperties properties) {
        LoopForgeProperties.Sandbox sandbox = properties.sandbox();
        return new LocalSandboxProvider(new LocalSandboxOptions(
                properties.workspaceRoot(),
                sandbox.passthroughEnv(),
                sandbox.privateHome(),
                properties.engine().nodeTimeout()));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("loopforge.sandbox.docker.enabled")
    io.github.loopforge4j.core.runtime.sandbox.DockerSandboxProvider dockerSandboxProvider(
            LoopForgeProperties properties) {
        LoopForgeProperties.Docker docker = properties.sandbox().docker();
        return new io.github.loopforge4j.core.runtime.sandbox.DockerSandboxProvider(
                new io.github.loopforge4j.core.runtime.sandbox.DockerSandboxOptions(
                        docker.profile(),
                        properties.workspaceRoot(),
                        docker.image(),
                        docker.binary(),
                        docker.network(),
                        docker.memory(),
                        docker.cpus(),
                        docker.pidsLimit(),
                        docker.readOnlyRoot(),
                        docker.user(),
                        docker.mountJavaHome(),
                        properties.sandbox().privateHome(),
                        properties.engine().nodeTimeout()));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty("loopforge.sandbox.bwrap.enabled")
    io.github.loopforge4j.core.runtime.sandbox.BwrapSandboxProvider bwrapSandboxProvider(
            LoopForgeProperties properties) {
        LoopForgeProperties.Bwrap bwrap = properties.sandbox().bwrap();
        if (!io.github.loopforge4j.core.runtime.sandbox.BwrapSandboxProvider.available(bwrap.binary())) {
            throw new IllegalStateException("loopforge.sandbox.bwrap is enabled but '" + bwrap.binary()
                    + "' cannot create namespaces on this host (install bubblewrap and allow unprivileged user"
                    + " namespaces)");
        }
        java.util.List<java.nio.file.Path> hidden = new java.util.ArrayList<>(bwrap.hiddenPaths());
        if (bwrap.hideServerHome()) {
            hidden.add(java.nio.file.Path.of(System.getProperty("user.home")));
        }
        LoopForgeProperties.Sandbox sandbox = properties.sandbox();
        return new io.github.loopforge4j.core.runtime.sandbox.BwrapSandboxProvider(
                new LocalSandboxOptions(
                        properties.workspaceRoot(),
                        sandbox.passthroughEnv(),
                        sandbox.privateHome(),
                        properties.engine().nodeTimeout()),
                new io.github.loopforge4j.core.runtime.sandbox.BwrapSandboxProvider.Options(
                        bwrap.profile(), bwrap.binary(), hidden, bwrap.shareNetwork()));
    }

    /** Deployment-wide Git credentials (upstream resolves them per responsible user). */
    @Bean
    GitCredentialProvider gitCredentialProvider(LoopForgeProperties properties) {
        LoopForgeProperties.Git git = properties.git();
        Map<String, GitCredentials.HttpsCredential> https = new java.util.LinkedHashMap<>();
        git.https().forEach(credential -> {
            if (credential.token() != null && !credential.token().isBlank()) {
                https.put(
                        credential.host().toLowerCase(java.util.Locale.ROOT),
                        new GitCredentials.HttpsCredential(credential.username(), credential.token()));
            }
        });
        String sshKey = null;
        if (git.sshPrivateKeyFile() != null) {
            try {
                sshKey = java.nio.file.Files.readString(git.sshPrivateKeyFile());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot read loopforge.git.ssh-private-key-file", e);
            }
        }
        GitCredentials credentials = new GitCredentials(https, sshKey, git.authorName(), git.authorEmail());
        return run -> credentials;
    }

    @Bean
    DeliveryProvider deliveryProvider(LoopForgeProperties properties) {
        java.net.URI api = properties.github().apiUrl();
        String host = "api.github.com".equalsIgnoreCase(api.getHost()) ? "github.com" : api.getHost();
        LoopForgeProperties.HttpsCredential credential = properties.git().https().stream()
                .filter(c -> c.host().equalsIgnoreCase(host))
                .findFirst()
                .orElse(null);
        GitHubDeliveryProvider github =
                new GitHubDeliveryProvider(api, () -> credential == null ? null : credential.token());
        Map<String, java.net.URI> gitlabHosts = new java.util.LinkedHashMap<>();
        for (LoopForgeProperties.GitLabHost gitlabHost : properties.gitlab()) {
            String name = gitlabHost.host().strip().toLowerCase(java.util.Locale.ROOT);
            gitlabHosts.put(
                    name,
                    gitlabHost.apiUrl() != null
                            ? gitlabHost.apiUrl()
                            : java.net.URI.create("https://" + name + "/api/v4"));
        }
        if (gitlabHosts.isEmpty()) {
            return github;
        }
        GitLabDeliveryProvider gitlab = new GitLabDeliveryProvider(
                gitlabHosts,
                name -> properties.git().https().stream()
                        .filter(c -> c.host().equalsIgnoreCase(name))
                        .map(LoopForgeProperties.HttpsCredential::token)
                        .findFirst()
                        .orElse(null));
        return DeliveryProvider.composite(List.of(github, gitlab));
    }

    @Bean(destroyMethod = "close")
    RunEngine runEngine(
            RunStore runStore,
            CatalogStore catalogStore,
            ExecutorRegistry executors,
            LocalSandboxProvider sandbox,
            GitCredentialProvider gitCredentials,
            DeliveryProvider delivery,
            java.util.Optional<io.github.loopforge4j.core.runtime.sandbox.DockerSandboxProvider> docker,
            java.util.Optional<io.github.loopforge4j.core.runtime.sandbox.BwrapSandboxProvider> bwrap,
            org.springframework.beans.factory.ObjectProvider<io.github.loopforge4j.core.mcp.PlatformMcp> platformMcp,
            io.github.loopforge4j.core.store.InstanceRegistry instances,
            LoopForgeProperties properties,
            Clock clock,
            List<RunListener> listeners) {
        LoopForgeProperties.Engine engine = properties.engine();
        EngineSettings settings = new EngineSettings(
                instanceId(properties),
                engine.defaultExecutor(),
                engine.nodeTimeout(),
                engine.schedulerTick(),
                engine.releaseWorkspaceOnFinish(),
                engine.leaseTtl(),
                engine.claimInterval(),
                engine.workspaceHost(),
                engine.sharedWorkspaces());
        RunEngine.Builder builder = RunEngine.builder()
                .runStore(runStore)
                .catalogStore(catalogStore)
                .executors(executors)
                .sandbox(sandbox);
        docker.ifPresent(builder::sandbox);
        bwrap.ifPresent(builder::sandbox);
        RunEngine runEngine = builder.instances(instances)
                .pricing(io.github.loopforge4j.core.pricing.TokenPricing.withOverrides(properties.pricing().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                LoopForgeProperties.ModelPrice::model,
                                p -> new io.github.loopforge4j.core.pricing.TokenPricing.Price(
                                        p.currency(), p.input(), p.output(), p.cacheRead(), p.cacheWrite()),
                                (a, b) -> b))))
                .admission(new io.github.loopforge4j.core.engine.AdmissionPolicy(
                        engine.maxActiveRuns(), engine.maxActiveRunsPerProject()))
                .claimOrphans(engine.recoverOnStartup())
                .gitCredentials(gitCredentials)
                .mcpSecrets(properties.mcp().secrets()::get)
                .platformMcp(platformMcp.getIfAvailable())
                .delivery(delivery)
                .settings(settings)
                .clock(clock)
                .build();
        listeners.forEach(runEngine::addListener);
        docker.ifPresent(provider -> log.info(
                "docker sandbox profile '{}' enabled",
                properties.sandbox().docker().profile()));
        warnOnNonUtf8Locale();
        return runEngine;
    }

    private static String instanceId(LoopForgeProperties properties) {
        if (properties.instanceId() != null && !properties.instanceId().isBlank()) {
            return properties.instanceId();
        }
        try {
            return InetAddress.getLocalHost().getHostName() + "-"
                    + ProcessHandle.current().pid();
        } catch (UnknownHostException e) {
            return "loopforge-" + ProcessHandle.current().pid();
        }
    }

    private static void warnOnNonUtf8Locale() {
        String jnu = System.getProperty("sun.jnu.encoding", "");
        if (!jnu.toUpperCase(java.util.Locale.ROOT).contains("UTF")) {
            log.warn(
                    "JVM native encoding is {}; file names with non-ASCII characters may be mangled."
                            + " Start the server with LANG=C.UTF-8.",
                    jnu);
        }
    }
}
