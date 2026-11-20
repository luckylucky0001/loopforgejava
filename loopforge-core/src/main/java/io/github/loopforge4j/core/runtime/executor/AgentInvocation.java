package io.github.loopforge4j.core.runtime.executor;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ArtifactContract;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Everything an executor needs for one agent call.
 *
 * @param invocationId unique per call; used for prompt files and telemetry correlation
 * @param resumeSessionId native CLI session to continue, or {@code null} for a fresh session
 * @param gateResultPath workspace-relative file the agent must write its terminal decision to
 * @param artifacts contracts the node must satisfy (used by the mock executor)
 * @param environment run environment (git credentials, repository manifest) for the CLI process
 */
public record AgentInvocation(
        String runId,
        String nodeId,
        int attempt,
        String invocationId,
        ExecutorDefinition executor,
        AgentDefinition agent,
        String prompt,
        String resumeSessionId,
        String gateResultPath,
        List<ArtifactContract> artifacts,
        Duration timeout,
        java.util.Map<String, String> environment) {

    public AgentInvocation {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(prompt, "prompt");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        environment = environment == null ? java.util.Map.of() : java.util.Map.copyOf(environment);
    }
}
