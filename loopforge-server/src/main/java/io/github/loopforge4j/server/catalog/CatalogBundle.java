package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import java.util.List;

/** Portable set of definitions: the roles an SOP needs plus the SOPs themselves. */
public record CatalogBundle(List<AgentDefinition> agents, List<WorkflowDefinition> workflows) {

    public CatalogBundle {
        agents = agents == null ? List.of() : List.copyOf(agents);
        workflows = workflows == null ? List.of() : List.copyOf(workflows);
    }
}
