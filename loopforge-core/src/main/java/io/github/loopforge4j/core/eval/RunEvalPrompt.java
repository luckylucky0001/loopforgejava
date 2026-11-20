package io.github.loopforge4j.core.eval;

import io.github.loopforge4j.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** The evaluator's instructions and result contract (upstream {@code BuildPrompt}/{@code ParseModelResult}). */
public final class RunEvalPrompt {

    static final Set<String> ACTIONS = Set.of("update_node", "upsert_gate", "remove_gate", "append_task_prompt");
    static final Set<String> SEVERITIES = Set.of("info", "warn", "error");
    static final int MAX_FINDINGS = 30;
    static final int MAX_RECOMMENDATIONS = 10;

    private RunEvalPrompt() {}

    /** The evaluation is invalid; the message says why. */
    public static final class InvalidResult extends Exception {
        public InvalidResult(String message) {
            super(message);
        }
    }

    public static String build(RunEvaluation.Policy policy, RunEvaluation.Facts facts, RunEvaluation.Catalog catalog) {
        StringBuilder b = new StringBuilder();
        b.append("你是 LoopForge 的 SOP 运行评测员。根据提供的结构化 facts 和压缩的 trace digest，按平台规则给这次运行打分，并给出可落地的改进建议。\n");
        b.append("不要读写工作区里的文件，也不要执行命令；只根据下面的数据作答。\n");
        b.append("只输出一个 JSON 对象（可以放在 ```json 代码块里），字段：\n");
        b.append("{\"dimensions\":{");
        for (int i = 0; i < policy.dimensions().size(); i++) {
            b.append(i > 0 ? "," : "")
                    .append('"')
                    .append(policy.dimensions().get(i).id())
                    .append("\":0");
        }
        b.append("},\"findings\":[{\"severity\":\"warn\",\"dimension\":\"control\",\"nodeId\":\"\",\"ruleId\":\"\","
                + "\"message\":\"\",\"evidence\":\"\"}],\"summary\":\"\",\"recommendations\":[]}\n");
        b.append("规则：\n");
        b.append("- 准入线 ").append(policy.threshold()).append(" 分。dimensions 每维 0-100，必须覆盖全部维度；总分由服务端按权重计算。\n");
        b.append("- findings 用中文，指向具体 nodeId 和 digest 的 seq / 事件。severity 只能是 info/warn/error。\n");
        b.append("- summary 必须包含这次运行的质量结论，以及没有可应用补丁时应该怎么改。\n");
        b.append("- recommendations 可为空。每条有 id、title、rationale、target（workflow 或 agent）、action，action 只能是：\n");
        b.append(
                "  update_node（target=workflow，nodeId，set 里只能有 maxAttempts、maxReworks、onFailure[fail|reject|human|loop]、retryStrategy[targeted_fix|full_redo]、timeoutSeconds），\n");
        b.append(
                "  upsert_gate / remove_gate（target=agent，targetId=角色 id，gate={id,name,mode:\"command\",commands:[...],required,onFailure}），\n");
        b.append("  append_task_prompt（target=agent，targetId=角色 id，text=追加到角色提示词的一段话）。\n");
        b.append("- 门禁只属于角色。不要整份重写 SOP 或角色，不要改连线或删节点。\n");
        b.append("评分维度：\n");
        for (RunEvaluation.Dimension d : policy.dimensions()) {
            b.append("- ")
                    .append(d.id())
                    .append("（")
                    .append(d.name())
                    .append("，权重 ")
                    .append(d.weight())
                    .append("）：")
                    .append(d.question())
                    .append('\n');
        }
        b.append("\n## 当前 SOP 与角色（建议只能改这些）\n").append(Json.write(catalog)).append('\n');
        b.append("\n## 这次运行的 facts\n").append(Json.write(facts)).append('\n');
        return b.toString();
    }

    public static RunEvaluation.Result parse(String raw, RunEvaluation.Policy policy) throws InvalidResult {
        JsonNode node = locate(raw);
        if (node == null) {
            throw new InvalidResult("评测没有返回 JSON");
        }
        String summary = text(node.path("summary"));
        if (summary == null) {
            throw new InvalidResult("评测缺少 summary");
        }
        Map<String, Integer> dimensions = new LinkedHashMap<>();
        for (RunEvaluation.Dimension d : policy.dimensions()) {
            JsonNode v = node.path("dimensions").path(d.id());
            if (!v.isNumber()) {
                throw new InvalidResult("评测缺少维度 " + d.id());
            }
            dimensions.put(d.id(), Math.max(0, Math.min(100, v.asInt())));
        }
        List<RunEvaluation.Finding> findings = new ArrayList<>();
        for (JsonNode f : node.path("findings")) {
            if (findings.size() == MAX_FINDINGS) {
                break;
            }
            String message = text(f.path("message"));
            if (message == null) {
                continue;
            }
            String severity = text(f.path("severity"));
            findings.add(new RunEvaluation.Finding(
                    severity != null && SEVERITIES.contains(severity) ? severity : "info",
                    text(f.path("dimension")),
                    text(f.path("nodeId")),
                    text(f.path("ruleId")),
                    RunEvalFacts.clip(message, 1000),
                    RunEvalFacts.clip(text(f.path("evidence")), 1000)));
        }
        List<RunEvaluation.Recommendation> recommendations = new ArrayList<>();
        for (JsonNode r : node.path("recommendations")) {
            if (recommendations.size() == MAX_RECOMMENDATIONS) {
                break;
            }
            String action = text(r.path("action"));
            if (action == null || !ACTIONS.contains(action)) {
                continue;
            }
            String id = text(r.path("id"));
            Map<String, Object> set =
                    r.path("set").isObject() ? Json.mapper().convertValue(r.path("set"), Map.class) : Map.of();
            io.github.loopforge4j.core.domain.VerificationGate gate = null;
            if (r.path("gate").isObject()) {
                try {
                    gate = Json.mapper()
                            .treeToValue(r.path("gate"), io.github.loopforge4j.core.domain.VerificationGate.class);
                } catch (RuntimeException e) {
                    continue;
                }
            }
            String target = text(r.path("target"));
            if (target == null) {
                target = action.equals("update_node") ? "workflow" : "agent";
            }
            recommendations.add(new RunEvaluation.Recommendation(
                    id == null || recommendations.stream().anyMatch(x -> x.id().equals(id))
                            ? "rec-" + (recommendations.size() + 1)
                            : RunEvalFacts.clip(id, 40),
                    RunEvalFacts.clip(text(r.path("title")), 300),
                    RunEvalFacts.clip(text(r.path("rationale")), 1000),
                    target.toLowerCase(java.util.Locale.ROOT),
                    text(r.path("targetId")),
                    action,
                    text(r.path("nodeId")),
                    set,
                    gate,
                    RunEvalFacts.clip(text(r.path("text")), 4000),
                    "pending",
                    null,
                    null));
        }
        int score = policy.score(dimensions);
        return new RunEvaluation.Result(
                score,
                score >= policy.threshold(),
                dimensions,
                findings,
                RunEvalFacts.clip(summary, 4000),
                recommendations);
    }

    private static JsonNode locate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.regex.Matcher fence = java.util.regex.Pattern.compile(
                        "```(?:json)?\\s*\\n(.*?)\\n?```", java.util.regex.Pattern.DOTALL)
                .matcher(raw);
        JsonNode found = null;
        while (fence.find()) {
            JsonNode n = tryParse(fence.group(1));
            if (n != null && n.isObject() && n.has("dimensions")) {
                found = n;
            }
        }
        if (found != null) {
            return found;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            JsonNode n = tryParse(raw.substring(start, end + 1));
            if (n != null && n.isObject()) {
                return n;
            }
        }
        return null;
    }

    private static JsonNode tryParse(String text) {
        try {
            return Json.mapper().readTree(text.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = (n.isString() ? n.asString() : n.toString()).strip();
        return s.isEmpty() ? null : s;
    }
}
