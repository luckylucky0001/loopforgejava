package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.materialize.AgentAssets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A project's own roles, rules and skills serve only that project; templates are adopted, not run. */
class ProjectScopedRoleTest {

    @TempDir
    Path root;

    static AgentDefinition role(String id, String project) {
        AgentDefinition shared = AgentDefinition.builder(id)
                .name(id)
                .prompt("写代码")
                .executor("mock")
                .build();
        return shared.copyFor(id, project, List.of());
    }

    static WorkflowDefinition sop(String id, String agent) {
        return WorkflowDefinition.builder(id)
                .node(WorkflowNode.builder("a", NodeType.AGENT).agent(agent).build())
                .build();
    }

    @Test
    void aRoleOfAnotherProjectNeverRuns() {
        try (EngineFixture f = new EngineFixture(root)) {
            f.agent(role("dev-p1", "p1")).workflow(sop("wf", "dev-p1"));
            Run elsewhere = f.engine.start("wf", "x", new ExecutionContext("p2", "bob", null, null, null));
            Run failed = f.awaitStatus(elsewhere.id(), RunStatus.FAILED);
            assertThat(failed.requireNode("a").error()).contains("agent 'dev-p1' not found");
            Run own = f.engine.start("wf", "x", new ExecutionContext("p1", "mia", null, null, null));
            f.awaitStatus(own.id(), RunStatus.SUCCEEDED);
        }
    }

    @Test
    void genericTemplatesAreAdoptedNotRun() {
        try (EngineFixture f = new EngineFixture(root)) {
            f.agent(AgentDefinition.builder("dev").prompt("p").executor("mock").build());
            f.workflow(WorkflowDefinition.builder("tpl")
                    .generic(true)
                    .node(WorkflowNode.builder("a", NodeType.AGENT).agent("dev").build())
                    .build());
            assertThatThrownBy(() -> f.start("tpl"))
                    .isInstanceOf(WorkflowValidationException.class)
                    .hasMessageContaining("通用模板");
        }
    }

    @Test
    void rulesAndSkillsOfAnotherProjectAreNotResolved() {
        var catalog = new io.github.loopforge4j.core.store.InMemoryCatalogStore();
        catalog.saveAsset(AssetKind.RULE, new RuleDefinition("shared", "s", null, "共享规则", 1, null));
        catalog.saveAsset(AssetKind.RULE, new RuleDefinition("own", "o", null, "本项目规则", 1, null, "p1"));
        catalog.saveAsset(AssetKind.SKILL, new SkillDefinition("tdd", "t", null, "测试先行", Map.of(), null, "p1"));
        AgentDefinition agent = AgentDefinition.builder("dev")
                .prompt("p")
                .rules(List.of("shared", "own"))
                .skills(List.of("tdd"))
                .build();
        AgentAssets inP1 = AgentAssets.resolve(agent, catalog, "p1", null);
        assertThat(inP1.rules()).extracting(RuleDefinition::id).containsExactlyInAnyOrder("shared", "own");
        assertThat(inP1.skills()).extracting(SkillDefinition::id).containsExactly("tdd");
        assertThatThrownBy(() -> AgentAssets.resolve(agent, catalog, "p2", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill 'tdd' not found");
    }

    @Test
    void theOwningProjectSurvivesJson() {
        AgentDefinition agent = Json.read(
                "{\"id\":\"dev\",\"prompt\":\"p\",\"projectId\":\"p1\",\"executorId\":\"mock\"}",
                AgentDefinition.class);
        assertThat(agent.projectId()).isEqualTo("p1");
        assertThat(agent.withUpdatedAt(java.time.Instant.EPOCH).projectId()).isEqualTo("p1");
        assertThat(Json.read(Json.write(agent), AgentDefinition.class)).isEqualTo(agent);
        WorkflowDefinition template = Json.read("{\"id\":\"tpl\",\"generic\":true}", WorkflowDefinition.class);
        assertThat(template.generic()).isTrue();
        assertThat(template.nextRevision(java.time.Instant.EPOCH).generic()).isTrue();
        assertThat(Json.read("{\"id\":\"r\",\"projectId\":\" \"}", RuleDefinition.class)
                        .projectId())
                .isNull();
    }
}
