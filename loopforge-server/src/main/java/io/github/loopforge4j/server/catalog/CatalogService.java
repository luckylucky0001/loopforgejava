package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.github.loopforge4j.core.engine.WorkflowValidator;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catalog use cases: validated writes and bundle import. */
@Service
public class CatalogService {

    private final CatalogStore store;
    private final boolean allowLocalRemotes;

    public CatalogService(CatalogStore store, LoopForgeProperties properties) {
        this.store = store;
        this.allowLocalRemotes = properties.git().allowLocalRemotes();
    }

    public RepositoryDefinition saveRepository(RepositoryDefinition repository) {
        validateRepository(repository);
        return store.saveRepository(repository);
    }

    private void validateRepository(RepositoryDefinition repository) {
        List<String> problems = WorkflowValidator.repositoryProblems(repository, allowLocalRemotes);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
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
        bundle.repositories().forEach(this::validateRepository);
        List<String> written = new ArrayList<>();
        for (RepositoryDefinition repository : bundle.repositories()) {
            if (overwrite || store.repository(repository.id()).isEmpty()) {
                store.saveRepository(repository);
                written.add("repository:" + repository.id());
            }
        }
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
