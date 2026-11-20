package io.github.loopforge4j.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RevisionSummaryTest {

    static WorkflowDefinition workflow(
            String status, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return Json.mapper()
                .convertValue(
                        Map.of("id", "w", "name", "W", "status", status, "nodes", nodes, "edges", edges),
                        WorkflowDefinition.class);
    }

    static Map<String, Object> node(String id, String script, int x) {
        return Map.of("id", id, "type", "script", "script", script, "position", Map.of("x", x, "y", 0));
    }

    @Test
    void describesWorkflowChanges() {
        var a = workflow(
                "draft",
                List.of(node("a", "echo", 0), node("b", "echo", 200)),
                List.of(Map.of("source", "a", "target", "b")));
        assertThat(RevisionSummary.of(null, a)).isEqualTo("创建");
        assertThat(RevisionSummary.of(a, a)).isEqualTo("无变化");
        var moved = workflow(
                "draft",
                List.of(node("a", "echo", 40), node("b", "echo", 200)),
                List.of(Map.of("source", "a", "target", "b")));
        assertThat(RevisionSummary.of(a, moved)).isEqualTo("节点 位置调整");
        var changed = workflow(
                "published",
                List.of(node("a", "echo 2", 0), node("c", "echo", 400)),
                List.of(Map.of("source", "a", "target", "c"), Map.of("source", "c", "target", "a", "when", "failure")));
        assertThat(RevisionSummary.of(a, changed)).isEqualTo("节点 ~a +c −b；连线 1→2；状态 draft→published");
    }

    @Test
    void describesRoleChanges() {
        var v1 = AgentDefinition.builder("dev")
                .name("开发")
                .prompt("p1")
                .executor("claude")
                .build();
        var v2 = AgentDefinition.builder("dev")
                .name("开发")
                .prompt("p2")
                .executor("codex")
                .build();
        assertThat(RevisionSummary.of(v1, v2)).isEqualTo("系统提示词；执行器 claude→codex");
    }
}
