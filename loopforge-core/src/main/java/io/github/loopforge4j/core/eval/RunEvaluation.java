package io.github.loopforge4j.core.eval;

import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.domain.VerificationGate;
import java.util.List;
import java.util.Map;

/** The data of a run evaluation. */
public final class RunEvaluation {

    private RunEvaluation() {}

    /** A scored aspect of a run. */
    public record Dimension(String id, String name, String question, int weight) {}

    /** How runs are scored: dimensions with weights, and the pass line. */
    public record Policy(int threshold, List<Dimension> dimensions) {

        /** Upstream's default policy. */
        public static final Policy DEFAULT = new Policy(
                70,
                List.of(
                        new Dimension("control", "控制流健康", "节点是否多次循环、返工或 A→B→A 打回？attempt/rework 是否超过节点上限？", 25),
                        new Dimension("latency", "耗时与等待", "单节点是否占了大部分执行时间？墙钟是否远大于实际执行（空转/排队）？", 20),
                        new Dimension("tools", "工具浪费", "是否有同工具连续失败、重复 Read/Grep 无进展、或无产物的空转调用？", 20),
                        new Dimension("tokens", "Token 效率", "Token 是否集中在单一节点？缓存命中是否过低且输入很大？", 15),
                        new Dimension("recovery", "错误恢复", "相同错误是否反复出现？门禁是否多次失败才过？失败后有没有有效变化？", 10),
                        new Dimension("delivery", "交付完整", "终态是否成功？预期产物和 verification/delivery 信号是否齐全？", 10)));

        /** The weighted score of {@code scores} (missing dimensions count as 0). */
        public int score(Map<String, Integer> scores) {
            int total = 0;
            int weights = 0;
            for (Dimension d : dimensions) {
                int s = Math.max(0, Math.min(100, scores.getOrDefault(d.id(), 0)));
                total += s * d.weight();
                weights += d.weight();
            }
            return weights == 0 ? 0 : Math.round((float) total / weights);
        }
    }

    public record NodeFact(
            String nodeId,
            String name,
            String type,
            String agentId,
            String status,
            int attempt,
            int reworkCount,
            int maxAttempts,
            int maxReworks,
            int toolCalls,
            long durationMs,
            TokenUsage usage,
            String error,
            int starts,
            int artifacts,
            int gateEvents) {}

    public record ToolRepeat(String nodeId, String tool, int count, int failed) {}

    public record ErrorFact(String nodeId, String text, int count) {}

    public record DigestEvent(long seq, String nodeId, String category, String type, String level, String message) {}

    /** What the evaluator sees of a run: measured facts and a compressed trace. */
    public record Facts(
            String runId,
            String workflowId,
            String workflowName,
            String status,
            String input,
            String projectId,
            long wallDurationMs,
            long execDurationMs,
            TokenUsage usage,
            int toolCalls,
            int artifactCount,
            int eventCount,
            List<NodeFact> nodes,
            List<ToolRepeat> toolRepeats,
            List<ErrorFact> errors,
            List<String> pingPong,
            Map<String, Integer> eventTypes,
            List<DigestEvent> digest) {}

    /** The current SOP nodes and roles the recommendations may change. */
    public record Catalog(List<CatalogNode> nodes, List<CatalogAgent> agents) {}

    public record CatalogNode(
            String id,
            String name,
            String type,
            String agentId,
            int maxAttempts,
            int maxReworks,
            String onFailure,
            String retryStrategy) {}

    public record CatalogAgent(String id, String name, String prompt, List<VerificationGate> verification) {}

    public record Finding(
            String severity, String dimension, String nodeId, String ruleId, String message, String evidence) {}

    /**
     * A change the evaluator proposes.
     *
     * @param target {@code workflow} or {@code agent}
     * @param action {@code update_node}, {@code upsert_gate}, {@code remove_gate} or {@code
     *     append_task_prompt}
     * @param set node settings for {@code update_node} (maxAttempts, maxReworks, onFailure,
     *     retryStrategy, timeoutSeconds)
     * @param status {@code pending}, {@code applied} or {@code failed}
     */
    public record Recommendation(
            String id,
            String title,
            String rationale,
            String target,
            String targetId,
            String action,
            String nodeId,
            Map<String, Object> set,
            VerificationGate gate,
            String text,
            String status,
            String appliedTo,
            String applyError) {

        public Recommendation {
            set = set == null ? Map.of() : Map.copyOf(set);
            status = status == null || status.isBlank() ? "pending" : status;
        }

        public Recommendation withOutcome(String newStatus, String appliedToId, String error) {
            return new Recommendation(
                    id,
                    title,
                    rationale,
                    target,
                    targetId,
                    action,
                    nodeId,
                    set,
                    gate,
                    text,
                    newStatus,
                    appliedToId,
                    error);
        }
    }

    public record Result(
            int score,
            boolean passed,
            Map<String, Integer> dimensions,
            List<Finding> findings,
            String summary,
            List<Recommendation> recommendations) {}
}
