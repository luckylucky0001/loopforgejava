package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import java.util.List;
import java.util.Optional;

/** Persistence port for reusable definitions (SOPs and roles). */
public interface CatalogStore {

    Optional<WorkflowDefinition> workflow(String id);

    List<WorkflowDefinition> workflows();

    /** Upserts and returns the stored definition (revision and timestamps stamped). */
    WorkflowDefinition saveWorkflow(WorkflowDefinition definition);

    boolean deleteWorkflow(String id);

    Optional<AgentDefinition> agent(String id);

    List<AgentDefinition> agents();

    AgentDefinition saveAgent(AgentDefinition definition);

    boolean deleteAgent(String id);
}
