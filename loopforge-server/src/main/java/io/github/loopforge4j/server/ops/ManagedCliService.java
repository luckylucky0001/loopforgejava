package io.github.loopforge4j.server.ops;

import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.engine.ManagedBinaries;
import io.github.loopforge4j.core.runtime.managed.ManagedClis;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The deployment's managed agent CLIs: the daily check, on-demand updates and rollbacks, and the
 * executables runs use. A run started before an update keeps the versions it pinned; agents in a
 * sandbox that cannot see this host's files (docker, a bwrap profile without the directory) keep
 * the CLIs of their own image.
 */
@Service
public class ManagedCliService implements ManagedBinaries {

    private static final Logger log = LoggerFactory.getLogger(ManagedCliService.class);

    private final LoopForgeProperties.ManagedCli config;
    private final ManagedClis clis;
    private final Set<String> hostProfiles;
    private final AtomicBoolean busy = new AtomicBoolean();

    public ManagedCliService(LoopForgeProperties properties, Clock clock) {
        this.config = properties.managedCli();
        Path root = config.root().toAbsolutePath().normalize();
        this.clis = config.enabled() ? new ManagedClis(root, clock) : null;
        LoopForgeProperties.Bwrap bwrap = properties.sandbox().bwrap();
        boolean bwrapSees = bwrap.enabled()
                && bwrap.readOnlyPaths().stream()
                        .map(p -> p.toAbsolutePath().normalize())
                        .anyMatch(root::startsWith);
        this.hostProfiles = bwrapSees
                ? Set.of(LocalSandboxProvider.PROFILE, bwrap.profile())
                : Set.of(LocalSandboxProvider.PROFILE);
    }

    public boolean enabled() {
        return clis != null;
    }

    public Optional<ManagedClis> clis() {
        return Optional.ofNullable(clis);
    }

    @Override
    public ExecutorDefinition resolve(String runKey, String sandboxProfileId, ExecutorDefinition executor) {
        if (clis == null || runKey == null || !hostProfiles.contains(sandboxProfileId)) {
            return executor;
        }
        Optional<String> provider = ManagedClis.providerFor(executor.kind());
        if (provider.isEmpty()) {
            return executor;
        }
        // an executable the deployment configured explicitly wins over the managed one
        String plain = ManagedClis.PROVIDERS.get(provider.get()).binary();
        if (executor.binary() != null
                && !executor.binary().isBlank()
                && !executor.binary().equals(plain)) {
            return executor;
        }
        try {
            return clis.binaryFor(runKey, provider.get())
                    .map(executor::withBinary)
                    .orElse(executor);
        } catch (IOException | RuntimeException e) {
            log.warn("managed CLI {} unavailable for {}: {}", provider.get(), runKey, e.toString());
            return executor;
        }
    }

    /** Starts an update in the background; false when one is already running or none is managed. */
    public boolean startUpdate(String provider) {
        if (clis == null || !busy.compareAndSet(false, true)) {
            return false;
        }
        if (provider != null && !ManagedClis.PROVIDERS.containsKey(provider)) {
            busy.set(false);
            throw new IllegalArgumentException("unsupported managed CLI " + provider);
        }
        Thread.ofVirtual().name("managed-cli-update").start(() -> {
            try {
                ManagedClis.Report report = clis.update(provider);
                log.info("managed CLI update: {}", report.results());
            } catch (IOException | RuntimeException e) {
                log.warn("managed CLI update failed: {}", e.toString());
            } finally {
                busy.set(false);
            }
        });
        return true;
    }

    public boolean updating() {
        return busy.get();
    }

    public ManagedClis.Report rollback(String provider) throws IOException {
        if (clis == null) {
            throw new IOException("本部署没有托管 CLI（loopforge.managed-cli.enabled）");
        }
        if (!busy.compareAndSet(false, true)) {
            throw new IOException("正在更新，稍后再回滚");
        }
        try {
            return clis.rollback(provider);
        } finally {
            busy.set(false);
        }
    }

    /** The daily window comes round once a day; checking more often costs nothing outside it. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void daily() {
        if (clis == null || !config.dailyCheck() || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            clis.check().ifPresent(report -> log.info("managed CLI daily check: {}", report.results()));
        } catch (IOException | RuntimeException e) {
            log.warn("managed CLI daily check failed: {}", e.toString());
        } finally {
            busy.set(false);
        }
    }

    public Map<String, Object> view() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", clis != null);
        out.put(
                "providers",
                ManagedClis.PROVIDERS.values().stream()
                        .map(p -> Map.of("name", p.name(), "package", p.pkg(), "binary", p.binary()))
                        .toList());
        if (clis == null) {
            return out;
        }
        ManagedClis.State state = clis.state();
        out.put("updating", busy.get());
        out.put("dailyCheck", config.dailyCheck());
        out.put("current", state.current());
        out.put("previous", state.previous());
        out.put("rejected", state.rejected());
        out.put("lastCheck", state.lastCheck());
        out.put("updatedAt", state.updatedAt());
        out.put("nextCheckAt", ManagedClis.nextScheduledAt(clis.root(), java.time.Instant.now()));
        clis.lastReport().ifPresent(r -> out.put("lastReport", r));
        out.values().removeIf(java.util.Objects::isNull);
        return out;
    }
}
