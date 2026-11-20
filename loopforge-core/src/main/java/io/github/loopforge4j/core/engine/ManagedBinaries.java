package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.ExecutorDefinition;

/**
 * Chooses the executable an agent invocation runs (upstream managed CLIs): a deployment that
 * manages its agent CLIs swaps in the version the run (or conversation) pinned.
 */
@FunctionalInterface
public interface ManagedBinaries {

    ManagedBinaries NONE = (runKey, sandboxProfileId, executor) -> executor;

    /**
     * @param runKey what the versions are pinned to: the run's workspace owner, or the conversation
     * @param sandboxProfileId where the agent runs (a managed binary lives on this host)
     */
    ExecutorDefinition resolve(String runKey, String sandboxProfileId, ExecutorDefinition executor);
}
