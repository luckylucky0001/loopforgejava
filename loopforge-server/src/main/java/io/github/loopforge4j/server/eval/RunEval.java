package io.github.loopforge4j.server.eval;

import io.github.loopforge4j.core.eval.RunEvaluation;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One evaluation of a settled run (upstream run eval).
 *
 * @param status {@code running}, {@code done} or {@code failed}
 * @param recommendations proposed changes with their apply status
 */
public record RunEval(
        String id,
        String runId,
        String status,
        String requestedBy,
        int score,
        boolean passed,
        int threshold,
        Map<String, Integer> dimensions,
        List<RunEvaluation.Finding> findings,
        String summary,
        List<RunEvaluation.Recommendation> recommendations,
        String error,
        List<String> activity,
        long totalTokens,
        double costUsd,
        Instant createdAt,
        Instant finishedAt) {

    public RunEval {
        // the policy's order, for display
        dimensions = dimensions == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(dimensions));
        findings = findings == null ? List.of() : List.copyOf(findings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        activity = activity == null ? List.of() : List.copyOf(activity);
    }

    RunEval finished(RunEvaluation.Result r, List<String> log, long tokens, double cost, Instant at) {
        return new RunEval(
                id,
                runId,
                "done",
                requestedBy,
                r.score(),
                r.passed(),
                threshold,
                r.dimensions(),
                r.findings(),
                r.summary(),
                r.recommendations(),
                null,
                log,
                tokens,
                cost,
                createdAt,
                at);
    }

    RunEval failed(String message, List<String> log, long tokens, double cost, Instant at) {
        return new RunEval(
                id,
                runId,
                "failed",
                requestedBy,
                0,
                false,
                threshold,
                Map.of(),
                List.of(),
                null,
                List.of(),
                message,
                log,
                tokens,
                cost,
                createdAt,
                at);
    }

    RunEval withRecommendations(List<RunEvaluation.Recommendation> recs) {
        return new RunEval(
                id,
                runId,
                status,
                requestedBy,
                score,
                passed,
                threshold,
                dimensions,
                findings,
                summary,
                recs,
                error,
                activity,
                totalTokens,
                costUsd,
                createdAt,
                finishedAt);
    }
}
