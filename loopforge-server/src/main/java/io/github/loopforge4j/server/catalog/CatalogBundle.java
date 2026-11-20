package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import java.util.List;

/** Portable set of definitions: the roles an SOP needs plus the SOPs themselves. */
public record CatalogBundle(
        List<AgentDefinition> agents, List<WorkflowDefinition> workflows, List<RepositoryDefinition> repositories) {

    public CatalogBundle(List<AgentDefinition> agents, List<WorkflowDefinition> workflows) {
        this(agents, workflows, List.of());
    }

    public CatalogBundle {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        agents = agents == null ? List.of() : List.copyOf(agents);
        workflows = workflows == null ? List.of() : List.copyOf(workflows);
    }
}
