package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.CatalogAsset;
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

    public <T extends CatalogAsset> T saveAsset(AssetKind<T> kind, T asset) {
        validateAsset(asset);
        return store.saveAsset(kind, asset);
    }

    private static void validateAsset(CatalogAsset asset) {
        List<String> problems = WorkflowValidator.assetProblems(asset);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(problems);
        }
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
        // the frontend preview's build step is stored with the SOP, so the designer can place it
        workflow = io.github.loopforge4j.core.preview.PreviewBuild.attach(workflow);
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
    private <T extends CatalogAsset> void importAssets(
            AssetKind<T> kind, List<T> assets, boolean overwrite, List<String> written) {
        for (T asset : assets) {
            if (overwrite || store.asset(kind, asset.id()).isEmpty()) {
                store.saveAsset(kind, asset);
                written.add(kind.wire() + ":" + asset.id());
            }
        }
    }

    @Transactional
    public List<String> importBundle(CatalogBundle bundle, boolean overwrite) {
        // validate everything first: an invalid item must not leave a half-imported bundle behind
        bundle.agents().forEach(WorkflowValidator::validateAgent);
        bundle.workflows().forEach(WorkflowValidator::validate);
        bundle.repositories().forEach(this::validateRepository);
        bundle.rules().forEach(CatalogService::validateAsset);
        bundle.skills().forEach(CatalogService::validateAsset);
        bundle.mcpServers().forEach(CatalogService::validateAsset);
        List<String> written = new ArrayList<>();
        importAssets(AssetKind.RULE, bundle.rules(), overwrite, written);
        importAssets(AssetKind.SKILL, bundle.skills(), overwrite, written);
        importAssets(AssetKind.MCP_SERVER, bundle.mcpServers(), overwrite, written);
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
                store.saveWorkflow(io.github.loopforge4j.core.preview.PreviewBuild.attach(workflow));
                written.add("workflow:" + workflow.id());
            }
        }
        return written;
    }
}
