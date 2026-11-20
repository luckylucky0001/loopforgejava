package io.github.loopforge4j.core.domain;

import java.util.List;

/** Structured terminal decision an agent submits for its node (upstream {@code submit_gate_result}). */
public record GateResult(
        GateDecision decision, String reason, String actionRequired, String summary, List<GateFinding> findings) {

    public GateResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static GateResult pass(String reason) {
        return new GateResult(GateDecision.PASS, reason, null, null, List.of());
    }
}
