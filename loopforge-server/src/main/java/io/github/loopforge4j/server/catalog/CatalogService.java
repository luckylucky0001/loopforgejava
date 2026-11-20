package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.engine.WorkflowValidator;
import io.github.loopforge4j.core.store.CatalogStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catalog use cases: validated writes and bundle import. */
@Service
public class CatalogService {

    private final CatalogStore store;

    public CatalogService(CatalogStore store) {
        this.store = store;
    }

    public WorkflowDefinition saveWorkflow(WorkflowDefinition workflow) {
        WorkflowValidator.validate(workflow);
        return store.saveWorkflow(workflow);
    }

    public AgentDefinition saveAgent(AgentDefinition agent) {
        WorkflowValidator.validateAgent(agent);
        return store.saveAgent(agent);
    }

    /**
     * Imports a bundle. With {@code overwrite=false} existing definitions are kept (like upstream's
     * {@code INSERT IGNORE}), so operator edits survive re-imports.
     *
     * @return ids that were written
     */
    @Transactional
    public List<String> importBundle(CatalogBundle bundle, boolean overwrite) {
        // validate everything first: an invalid item must not leave a half-imported bundle behind
        bundle.agents().forEach(WorkflowValidator::validateAgent);
        bundle.workflows().forEach(WorkflowValidator::validate);
        List<String> written = new ArrayList<>();
        for (AgentDefinition agent : bundle.agents()) {
            if (overwrite || store.agent(agent.id()).isEmpty()) {
                store.saveAgent(agent);
                written.add("agent:" + agent.id());
            }
        }
        for (WorkflowDefinition workflow : bundle.workflows()) {
            if (overwrite || store.workflow(workflow.id()).isEmpty()) {
                store.saveWorkflow(workflow);
                written.add("workflow:" + workflow.id());
            }
        }
        return written;
    }
}
