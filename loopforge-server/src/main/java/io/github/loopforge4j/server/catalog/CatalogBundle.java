package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import java.util.List;

/**
 * Portable set of definitions: SOPs, the roles they use, the roles' rules, skills and MCP servers,
 * and repositories. MCP secrets are never part of a bundle; definitions only reference them.
 */
public record CatalogBundle(
        List<AgentDefinition> agents,
        List<WorkflowDefinition> workflows,
        List<RepositoryDefinition> repositories,
        List<RuleDefinition> rules,
        List<SkillDefinition> skills,
        List<McpServerDefinition> mcpServers) {

    public CatalogBundle(List<AgentDefinition> agents, List<WorkflowDefinition> workflows) {
        this(agents, workflows, List.of(), List.of(), List.of(), List.of());
    }

    public CatalogBundle {
        agents = agents == null ? List.of() : List.copyOf(agents);
        workflows = workflows == null ? List.of() : List.copyOf(workflows);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        rules = rules == null ? List.of() : List.copyOf(rules);
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    }
}
