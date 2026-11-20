package io.github.loopforge4j.core.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * Engine tuning.
 *
 * @param instanceId identifies this engine process in run ownership records
 * @param defaultExecutorId executor used when neither the role nor the node names one
 * @param nodeTimeout wall-clock limit for one agent invocation or script, unless the node overrides it
 * @param schedulerTick upper bound for how long the scheduler sleeps between state checks
 * @param releaseWorkspaceOnFinish delete the workspace of succeeded and cancelled runs (failed runs
 *     keep theirs so they can be retried)
 */
public record EngineSettings(
        String instanceId,
        String defaultExecutorId,
        Duration nodeTimeout,
        Duration schedulerTick,
        boolean releaseWorkspaceOnFinish) {

    public EngineSettings {
        Objects.requireNonNull(instanceId, "instanceId");
        defaultExecutorId = defaultExecutorId == null || defaultExecutorId.isBlank() ? "claude" : defaultExecutorId;
        nodeTimeout = nodeTimeout == null ? Duration.ofHours(2) : nodeTimeout;
        schedulerTick = schedulerTick == null ? Duration.ofMillis(500) : schedulerTick;
    }

    public static EngineSettings defaults() {
        return new EngineSettings(
                "local-" + ProcessHandle.current().pid(), "claude", Duration.ofHours(2), Duration.ofMillis(500), true);
    }
}
