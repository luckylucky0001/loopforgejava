package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.store.InstanceRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-instance execution ownership (upstream {@code acquireLease} / {@code renewRunLease} /
 * {@code ResumeInterruptedRuns}): every run with a scheduler carries an owner and a lease that this
 * instance renews; an instance that stops renewing loses its runs to the claim loop of another one.
 * Liveness of an owner comes from the instance registry; on the same host a dead process is detected
 * immediately, so a restarted single instance resumes its runs without waiting for the TTL.
 */
final class LeaseKeeper {

    private static final Logger log = LoggerFactory.getLogger(LeaseKeeper.class);

    private final RunEngine engine;
    private final InstanceRegistry registry;
    private final InstanceRegistry.Instance self;
    private volatile boolean claiming;
    private volatile boolean stopped;
    private Thread thread;

    LeaseKeeper(RunEngine engine, InstanceRegistry registry) {
        this.engine = engine;
        this.registry = registry;
        EngineSettings settings = engine.settings();
        Instant processStart = ProcessHandle.current()
                .info()
                .startInstant()
                .orElse(engine.clock().instant());
        this.self = new InstanceRegistry.Instance(
                settings.instanceId(),
                settings.workspaceHost(),
                ProcessHandle.current().pid(),
                processStart,
                null);
    }

    void start(boolean claimOrphans) {
        this.claiming = claimOrphans;
        heartbeat();
        thread = Thread.ofVirtual().name("lease-keeper-" + self.id()).start(this::loop);
    }

    void stop() {
        stopped = true;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        safely(() -> registry.remove(self.id()));
    }

    private void loop() {
        Duration heartbeat = engine.settings().heartbeatInterval();
        Duration claim = engine.settings().claimInterval();
        long nextClaim = System.nanoTime() + claim.toNanos();
        while (!stopped) {
            try {
                Thread.sleep(Math.max(50, Math.min(heartbeat.toMillis(), claim.toMillis())));
            } catch (InterruptedException e) {
                return;
            }
            if (stopped) {
                return;
            }
            heartbeat();
            renewLeases();
            if (System.nanoTime() >= nextClaim) {
                nextClaim = System.nanoTime() + claim.toNanos();
                if (claiming) {
                    safely(engine::claimOrphanedRuns);
                    safely(engine::promoteQueuedRuns);
                }
            }
        }
    }

    void heartbeat() {
        safely(() -> registry.heartbeat(new InstanceRegistry.Instance(
                self.id(),
                self.host(),
                self.pid(),
                self.startedAt(),
                engine.clock().instant())));
    }

    /** Extends the leases of every run this instance schedules; abandons runs taken over elsewhere. */
    void renewLeases() {
        for (Map.Entry<String, RunScheduler> entry : engine.localSchedulers().entrySet()) {
            RunScheduler scheduler = entry.getValue();
            boolean[] lost = {false};
            try {
                engine.runs().update(entry.getKey(), c -> {
                    Run r = c.run();
                    lost[0] = false;
                    if (r.generation() != scheduler.generation() || !self.id().equals(r.ownerInstanceId())) {
                        lost[0] = true;
                        return false;
                    }
                    r.leaseUntil(c.now().plus(engine.settings().leaseTtl()));
                    return true;
                });
            } catch (RuntimeException e) {
                log.warn("cannot renew the lease of run {}: {}", entry.getKey(), e.toString());
                continue;
            }
            if (lost[0]) {
                log.warn("run {} was taken over by another instance; stopping its local execution", entry.getKey());
                scheduler.abandon();
            }
        }
    }

    /** The lease is held by another instance that is still alive. */
    boolean heldElsewhere(Run run, Instant now) {
        String owner = run.ownerInstanceId();
        if (owner == null || owner.equals(self.id())) {
            return false;
        }
        if (run.leaseUntil() == null || !run.leaseUntil().isAfter(now)) {
            return false;
        }
        return instanceAlive(owner, now);
    }

    boolean instanceAlive(String id, Instant now) {
        Optional<InstanceRegistry.Instance> found;
        try {
            found = registry.find(id);
        } catch (RuntimeException e) {
            return true;
        }
        if (found.isEmpty()) {
            // unknown (e.g. registry pruned): the lease decides
            return true;
        }
        InstanceRegistry.Instance instance = found.get();
        if (instance.host().equals(self.host()) && !processAlive(instance.pid(), instance.startedAt())) {
            return false;
        }
        return instance.heartbeatAt() != null
                && instance.heartbeatAt().isAfter(now.minus(engine.settings().leaseTtl()));
    }

    /** Some live instance serves workspaces of {@code host}. */
    boolean hostAlive(String host, Instant now) {
        if (host.equals(self.host())) {
            return true;
        }
        try {
            return registry.instances().stream().anyMatch(i -> i.host().equals(host) && instanceAlive(i.id(), now));
        } catch (RuntimeException e) {
            return true;
        }
    }

    String instanceId() {
        return self.id();
    }

    String host() {
        return self.host();
    }

    static boolean processAlive(long pid, Instant startedAt) {
        if (pid <= 0) {
            return true;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive()) {
            return false;
        }
        // a reused pid belongs to a process that started at another time
        Optional<Instant> start = handle.get().info().startInstant();
        return start.isEmpty()
                || startedAt == null
                || Math.abs(Duration.between(start.get(), startedAt).toSeconds()) <= 2;
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("lease maintenance failed: {}", e.toString());
        }
    }
}
