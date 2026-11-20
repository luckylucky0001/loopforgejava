package io.github.loopforge4j.core.domain;

/**
 * Self-correction policy inside one agent invocation: when a gate with {@code onFailure=loop} fails,
 * the agent gets the failure as feedback and tries again in the same workspace.
 */
public record AgentLoopPolicy(
        boolean enabled, int maxIterations, LoopExhaustedPolicy onExhausted, String feedbackPrompt) {

    public static final AgentLoopPolicy DISABLED = new AgentLoopPolicy(false, 1, LoopExhaustedPolicy.FAIL, null);

    public AgentLoopPolicy {
        maxIterations = Math.max(1, maxIterations);
        onExhausted = onExhausted == null ? LoopExhaustedPolicy.FAIL : onExhausted;
    }

    public int effectiveIterations() {
        return enabled ? maxIterations : 1;
    }
}
