package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.GateDecision;
import io.github.loopforge4j.core.domain.GateFinding;
import io.github.loopforge4j.core.domain.GateResult;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.core.JacksonException;

/**
 * File channel for the agent terminal decision. Upstream exposes an MCP tool ({@code
 * submit_gate_result}) that writes the same JSON document; this port asks the agent to write the
 * file directly, which every coding CLI can do.
 */
public final class GateResults {

    private static final int MAX_TEXT = 4000;
    private static final int MAX_FINDINGS = 50;

    private GateResults() {}

    /** Workspace-relative path of the decision file for one node attempt. */
    public static String path(String nodeId, int attempt) {
        String safe = nodeId.replaceAll("[^A-Za-z0-9._-]", "_");
        return LocalSandbox.CONTROL_DIR + "/gate-results/" + safe + "/attempt-" + attempt + ".json";
    }

    public static void clear(Path workspace, String relative) throws IOException {
        Files.deleteIfExists(workspace.resolve(relative));
    }

    /**
     * Reads and validates the decision file.
     *
     * @return empty when the agent did not write it
     * @throws IllegalArgumentException when the payload is malformed
     */
    public static Optional<GateResult> read(Path workspace, String relative) throws IOException {
        Path file = workspace.resolve(relative).normalize();
        if (!file.startsWith(workspace) || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        GateResult result;
        try {
            result = Json.read(raw, GateResult.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalArgumentException("gate result payload is invalid: " + e.getMessage(), e);
        }
        return Optional.of(validate(result));
    }

    static GateResult validate(GateResult result) {
        if (result == null || result.decision() == null) {
            throw new IllegalArgumentException("gate result decision is required (pass, rework, fail, human)");
        }
        String reason = trim(result.reason());
        String action = trim(result.actionRequired());
        String summary = trim(result.summary());
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("gate result reason is required");
        }
        if (result.decision() == GateDecision.HUMAN && action.isEmpty()) {
            throw new IllegalArgumentException("gate result actionRequired is required for human decisions");
        }
        if (reason.length() > MAX_TEXT
                || action.length() > MAX_TEXT
                || summary.length() > MAX_TEXT
                || result.findings().size() > MAX_FINDINGS) {
            throw new IllegalArgumentException("gate result exceeds size limits");
        }
        for (GateFinding finding : result.findings()) {
            String title = trim(finding.title());
            if (title.isEmpty()
                    || title.length() > 500
                    || trim(finding.severity()).length() > 64
                    || trim(finding.evidence()).length() > MAX_TEXT) {
                throw new IllegalArgumentException("gate result contains an invalid finding");
            }
        }
        return new GateResult(
                result.decision(),
                reason,
                action.isEmpty() ? null : action,
                summary.isEmpty() ? null : summary,
                result.findings());
    }

    /**
     * Maps a decision onto the failure pipeline (upstream {@code applyGateResult}). A rework without a
     * rework target cannot be routed upstream and degrades to a terminal failure.
     */
    static Optional<NodeFailure> toFailure(GateResult result, String rejectTarget) {
        return switch (result.decision()) {
            case PASS -> Optional.empty();
            case REWORK ->
                rejectTarget == null
                        ? Optional.of(new NodeFailure(
                                FailureKind.GATE_DECISION,
                                "agent requested rework but this node has no rework target (rejectTo); treated as fail: "
                                        + result.reason(),
                                FailurePolicy.FAIL))
                        : Optional.of(new NodeFailure(
                                FailureKind.GATE_DECISION,
                                "agent requested upstream rework: " + result.reason(),
                                FailurePolicy.REJECT));
            case FAIL ->
                Optional.of(new NodeFailure(
                        FailureKind.GATE_DECISION,
                        "agent submitted terminal failure: " + result.reason(),
                        FailurePolicy.FAIL));
            case HUMAN ->
                Optional.of(new NodeFailure(
                        FailureKind.GATE_DECISION,
                        "agent requested human confirmation: " + result.reason(),
                        FailurePolicy.HUMAN));
        };
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
