package io.github.loopforge4j.server.persistence;

import io.github.loopforge4j.core.json.Json;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Describes what a save changed, for the revision history ("节点 +review −test ~implement；状态 draft→published"). */
final class RevisionSummary {

    private static final Set<String> STAMPS = Set.of("revision", "createdAt", "updatedAt");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("name", "名称"),
            Map.entry("status", "状态"),
            Map.entry("version", "版本"),
            Map.entry("projectId", "项目"),
            Map.entry("owner", "负责人"),
            Map.entry("tags", "标签"),
            Map.entry("description", "描述"),
            Map.entry("sandboxProfileId", "沙箱"),
            Map.entry("prompt", "系统提示词"),
            Map.entry("rules", "规则"),
            Map.entry("skills", "Skills"),
            Map.entry("mcpServers", "MCP"),
            Map.entry("hooks", "Hooks"),
            Map.entry("executorId", "执行器"),
            Map.entry("model", "模型"),
            Map.entry("effort", "推理强度"),
            Map.entry("loop", "自修正"),
            Map.entry("interventions", "人工介入"),
            Map.entry("artifactOutputs", "产物"),
            Map.entry("verification", "验证"),
            Map.entry("platformMcp", "平台 MCP"),
            Map.entry("knowledgeBase", "知识库"));

    private RevisionSummary() {}

    static String of(Object previous, Object current) {
        if (previous == null) {
            return "创建";
        }
        JsonNode before = Json.mapper().valueToTree(previous);
        JsonNode after = Json.mapper().valueToTree(current);
        List<String> parts = new ArrayList<>();
        if (after.has("nodes") || before.has("nodes")) {
            String nodes = byId(before.path("nodes"), after.path("nodes"));
            if (!nodes.isEmpty()) {
                parts.add("节点 " + nodes);
            }
            int edgesBefore = before.path("edges").size();
            int edgesAfter = after.path("edges").size();
            if (!before.path("edges").equals(after.path("edges"))) {
                parts.add(edgesBefore == edgesAfter ? "连线调整" : "连线 " + edgesBefore + "→" + edgesAfter);
            }
        }
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        after.properties().forEach(e -> fields.put(e.getKey(), e.getValue()));
        for (Iterator<String> it = before.propertyNames().iterator(); it.hasNext(); ) {
            fields.putIfAbsent(it.next(), null);
        }
        for (String field : fields.keySet()) {
            if (STAMPS.contains(field) || field.equals("nodes") || field.equals("edges") || field.equals("id")) {
                continue;
            }
            JsonNode a = before.path(field);
            JsonNode b = after.path(field);
            if (a.equals(b)) {
                continue;
            }
            String label = LABELS.getOrDefault(field, field);
            if ((a.isString() || a.isMissingNode() || a.isNull())
                    && (b.isString() || b.isMissingNode() || b.isNull())
                    && Set.of("status", "executorId", "model", "projectId", "sandboxProfileId", "effort")
                            .contains(field)) {
                parts.add(label + " " + text(a) + "→" + text(b));
            } else {
                parts.add(label);
            }
        }
        return parts.isEmpty() ? "无变化" : clip(String.join("；", parts));
    }

    private static String byId(JsonNode before, JsonNode after) {
        Map<String, JsonNode> old = new LinkedHashMap<>();
        before.forEach(n -> old.put(n.path("id").asString(""), stripPosition(n)));
        List<String> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        boolean moved = false;
        for (JsonNode n : after) {
            String id = n.path("id").asString("");
            seen.add(id);
            JsonNode was = old.get(id);
            if (was == null) {
                out.add("+" + id);
            } else if (!was.equals(stripPosition(n))) {
                out.add("~" + id);
            } else if (!before.isEmpty() && !position(before, id).equals(n.path("position"))) {
                moved = true;
            }
        }
        old.keySet().stream().filter(id -> !seen.contains(id)).forEach(id -> out.add("−" + id));
        if (out.isEmpty() && moved) {
            return "位置调整";
        }
        return String.join(" ", out);
    }

    private static JsonNode position(JsonNode nodes, String id) {
        for (JsonNode n : nodes) {
            if (id.equals(n.path("id").asString(""))) {
                return n.path("position");
            }
        }
        return Json.mapper().missingNode();
    }

    private static JsonNode stripPosition(JsonNode node) {
        if (!node.isObject()) {
            return node;
        }
        tools.jackson.databind.node.ObjectNode copy = (tools.jackson.databind.node.ObjectNode) node.deepCopy();
        copy.remove("position");
        return copy;
    }

    private static String text(JsonNode n) {
        return n.isMissingNode() || n.isNull() ? "—" : n.asString();
    }

    private static String clip(String s) {
        return s.length() <= 900 ? s : s.substring(0, 900) + "…";
    }
}
