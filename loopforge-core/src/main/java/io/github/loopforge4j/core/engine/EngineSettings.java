package io.github.loopforge4j.core.engine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Objects;

/**
 * Engine tuning.
 *
 * @param instanceId identifies this engine process in run ownership records; unique per process
 * @param defaultExecutorId executor used when neither the role nor the node names one
 * @param nodeTimeout wall-clock limit for one agent invocation or script, unless the node overrides it
 * @param schedulerTick upper bound for how long the scheduler sleeps between state checks
 * @param releaseWorkspaceOnFinish delete the workspace of succeeded and cancelled runs (failed runs
 *     keep theirs so they can be retried)
 * @param leaseTtl how long a run's execution lease lasts without renewal; heartbeats renew it every
 *     third of it, so another instance takes over a run at most this long after its owner died
 * @param claimInterval how often orphaned runs (expired lease) are looked for
 * @param workspaceHost name of the file system workspaces live on (the host name by default)
 * @param sharedWorkspaces workspaces are on storage every instance mounts at the same path, so a
 *     run may continue on another host
 */
public record EngineSettings(
        String instanceId,
        String defaultExecutorId,
        Duration nodeTimeout,
        Duration schedulerTick,
        boolean releaseWorkspaceOnFinish,
        Duration leaseTtl,
        Duration claimInterval,
        String workspaceHost,
        boolean sharedWorkspaces) {

    public EngineSettings {
        Objects.requireNonNull(instanceId, "instanceId");
        defaultExecutorId = defaultExecutorId == null || defaultExecutorId.isBlank() ? "claude" : defaultExecutorId;
        nodeTimeout = nodeTimeout == null ? Duration.ofHours(2) : nodeTimeout;
        schedulerTick = schedulerTick == null ? Duration.ofMillis(500) : schedulerTick;
        leaseTtl = leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero() ? Duration.ofSeconds(60) : leaseTtl;
        claimInterval = claimInterval == null || claimInterval.isNegative() || claimInterval.isZero()
                ? Duration.ofSeconds(10)
                : claimInterval;
        workspaceHost = workspaceHost == null || workspaceHost.isBlank() ? localHost() : workspaceHost;
    }

    public EngineSettings(
            String instanceId,
            String defaultExecutorId,
            Duration nodeTimeout,
            Duration schedulerTick,
            boolean releaseWorkspaceOnFinish) {
        this(
                instanceId,
                defaultExecutorId,
                nodeTimeout,
                schedulerTick,
                releaseWorkspaceOnFinish,
                null,
                null,
                null,
                false);
    }

    public static EngineSettings defaults() {
        return new EngineSettings(
                "local-" + ProcessHandle.current().pid(), "claude", Duration.ofHours(2), Duration.ofMillis(500), true);
    }

    /** How often leases and the instance heartbeat are renewed. */
    public Duration heartbeatInterval() {
        return leaseTtl.dividedBy(3);
    }

    public EngineSettings withLease(Duration ttl, Duration claim) {
        return new EngineSettings(
                instanceId,
                defaultExecutorId,
                nodeTimeout,
                schedulerTick,
                releaseWorkspaceOnFinish,
                ttl,
                claim,
                workspaceHost,
                sharedWorkspaces);
    }

    public EngineSettings withInstance(String id, String host) {
        return new EngineSettings(
                id,
                defaultExecutorId,
                nodeTimeout,
                schedulerTick,
                releaseWorkspaceOnFinish,
                leaseTtl,
                claimInterval,
                host,
                sharedWorkspaces);
    }

    static String localHost() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
