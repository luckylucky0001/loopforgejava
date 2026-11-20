package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.json.Json;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The analyst's output contract (upstream {@code submit_clarification}): the reply ends with one JSON
 * object {@code {"ready", "summary", "questions": [{"id", "question", "options", "multiSelect",
 * "freeText"}]}}. Parsing is strict about what the console needs (questions to answer, or a
 * conclusion) and lenient about everything else.
 */
final class ClarificationProtocol {

    static final String HEADING = "## LoopForge 澄清结论";
    static final int MAX_QUESTIONS = 5;
    static final int MAX_OPTIONS = 8;

    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*\\n(.*?)\\n?```", Pattern.DOTALL);

    private ClarificationProtocol() {}

    /** A parsed analyst result. */
    record Result(boolean ready, String summary, List<Clarification.Question> questions, String json) {}

    /** The reply did not hold a usable result; the message says what to fix (it is shown to the model). */
    static final class InvalidResult extends Exception {
        InvalidResult(String message) {
            super(message);
        }
    }

    static final String FORMAT = """
            输出格式（必须遵守）：回复的最后给出一个 ```json 代码块，只给一个，结构如下：
            {"ready": false, "summary": "目前的理解（Markdown）", "questions": [{"id": "q1", "question": "具体问题", "options": ["选项一", "选项二"], "multiSelect": false, "freeText": false}]}
            - ready=false 时 questions 至少一个、最多 3 个；选择题 options 至少两个不同选项；需要用户填写时 freeText=true、options=[]
            - ready=true 时 questions 为空，summary 按「本次范围 / 不在本次范围 / 验收标准 / 假设」分段给出结论
            """;

    static final String RETRY = "刚才的回复里没有有效的澄清结果，本轮尚未结束。请修正后在回复最后重新给出 ```json 代码块。"
            + "ready=false 必须提供具体 questions，不能只有 summary；选择题 options 至少两个不同选项，开放填写题设 freeText=true、options=[]。"
            + "如果其实已经没有阻塞开工的缺口，就给出 ready=true 和 summary。问题：";

    static Result parse(String reply) throws InvalidResult {
        JsonNode node = locate(reply);
        if (node == null) {
            throw new InvalidResult("未返回 JSON 结果");
        }
        boolean ready = node.path("ready").asBoolean(false);
        String summary = text(node.path("summary"));
        List<Clarification.Question> questions = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode q : node.path("questions")) {
            if (questions.size() == MAX_QUESTIONS) {
                break;
            }
            String question = q.isString() ? q.asString().strip() : text(q.path("question"));
            if (question == null) {
                throw new InvalidResult("问题文本为空");
            }
            boolean freeText = q.path("freeText").asBoolean(false);
            LinkedHashSet<String> options = new LinkedHashSet<>();
            for (JsonNode o : q.path("options")) {
                String label = o.isString() ? o.asString().strip() : text(o.path("label"));
                if (label != null && !label.isEmpty() && options.size() < MAX_OPTIONS) {
                    options.add(clip(label, 300));
                }
            }
            if (!freeText && options.size() < 2) {
                // a "choice" without two distinct options is an open question
                freeText = true;
                options.clear();
            }
            String id = text(q.path("id"));
            if (id == null || !ids.add(id)) {
                id = "q" + (questions.size() + 1);
                ids.add(id);
            }
            questions.add(new Clarification.Question(
                    clip(id, 40),
                    clip(question, 2_000),
                    freeText ? List.of() : List.copyOf(options),
                    !freeText && q.path("multiSelect").asBoolean(false),
                    freeText));
        }
        if (ready) {
            if (summary == null) {
                throw new InvalidResult("ready=true 但缺少 summary");
            }
            questions.clear();
        } else if (questions.isEmpty()) {
            throw new InvalidResult("ready=false 但既没有 questions");
        }
        String json = Json.write(
                java.util.Map.of("ready", ready, "summary", summary == null ? "" : summary, "questions", questions));
        return new Result(ready, summary == null ? null : clip(summary, 20_000), List.copyOf(questions), json);
    }

    /** The last fenced JSON block, else the last balanced object that looks like a result. */
    private static JsonNode locate(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        JsonNode found = null;
        Matcher m = FENCE.matcher(reply);
        while (m.find()) {
            JsonNode node = tryParse(m.group(1));
            if (protocol(node)) {
                found = node;
            }
        }
        if (found != null) {
            return found;
        }
        for (int end = reply.lastIndexOf('}'); end >= 0; end = reply.lastIndexOf('}', end - 1)) {
            int depth = 0;
            for (int start = end; start >= 0; start--) {
                char c = reply.charAt(start);
                if (c == '}') {
                    depth++;
                } else if (c == '{' && --depth == 0) {
                    JsonNode node = tryParse(reply.substring(start, end + 1));
                    if (protocol(node)) {
                        return node;
                    }
                    break;
                }
            }
        }
        return null;
    }

    private static boolean protocol(JsonNode node) {
        return node != null && node.isObject() && (node.has("ready") || node.has("questions") || node.has("summary"));
    }

    private static JsonNode tryParse(String text) {
        try {
            return Json.mapper().readTree(text.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.isString() ? node.asString() : node.toString();
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    /** The description with the conclusion as its (single) last block. */
    static String withConclusion(String description, String summary) {
        String block = HEADING + "\n\n" + summary.strip();
        String desc = description == null ? "" : description.stripTrailing();
        int i = desc.indexOf(HEADING);
        if (i >= 0) {
            desc = desc.substring(0, i).stripTrailing();
        }
        return desc.isEmpty() ? block : desc + "\n\n" + block;
    }

    static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
