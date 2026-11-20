package io.github.loopforge4j.core.runtime.materialize;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.store.CatalogStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The catalog assets a role binds (upstream {@code AgentAssetBundle}). A role's {@code rules} entry
 * that names no catalog rule is inline rule text and stays in the task book.
 */
public record AgentAssets(
        List<RuleDefinition> rules, List<SkillDefinition> skills, List<McpServerDefinition> mcpServers) {

    public static final AgentAssets NONE = new AgentAssets(List.of(), List.of(), List.of());

    public AgentAssets {
        rules = rules == null ? List.of() : List.copyOf(rules);
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    }

    /**
     * Resolves the role's bindings.
     *
     * @throws IllegalArgumentException naming the first skill or MCP server that does not exist
     */
    public static AgentAssets resolve(AgentDefinition agent, CatalogStore catalog) {
        List<RuleDefinition> rules = new ArrayList<>();
        for (String ref : agent.rules()) {
            catalog.asset(AssetKind.RULE, ref.strip()).ifPresent(rules::add);
        }
        rules.sort(Comparator.comparingInt(RuleDefinition::priority).reversed());
        List<SkillDefinition> skills = new ArrayList<>();
        for (String id : agent.skills()) {
            skills.add(catalog.asset(AssetKind.SKILL, id)
                    .orElseThrow(() -> new IllegalArgumentException("skill '" + id + "' not found")));
        }
        List<McpServerDefinition> servers = new ArrayList<>();
        for (String id : agent.mcpServers()) {
            servers.add(catalog.asset(AssetKind.MCP_SERVER, id)
                    .orElseThrow(() -> new IllegalArgumentException("MCP server '" + id + "' not found")));
        }
        return new AgentAssets(rules, skills, servers);
    }

    /** These assets plus platform-served servers and their usage rules. */
    public AgentAssets with(List<McpServerDefinition> extraServers, List<RuleDefinition> extraRules) {
        if (extraServers.isEmpty() && extraRules.isEmpty()) {
            return this;
        }
        List<RuleDefinition> allRules = new ArrayList<>(rules);
        allRules.addAll(extraRules);
        allRules.sort(Comparator.comparingInt(RuleDefinition::priority).reversed());
        List<McpServerDefinition> allServers = new ArrayList<>(mcpServers);
        allServers.addAll(extraServers);
        return new AgentAssets(allRules, skills, allServers);
    }

    /** Rule entries of the role that are inline text rather than catalog references. */
    public static List<String> inlineRules(AgentDefinition agent, CatalogStore catalog) {
        return agent.rules().stream()
                .filter(r -> catalog.asset(AssetKind.RULE, r.strip()).isEmpty())
                .toList();
    }
}
