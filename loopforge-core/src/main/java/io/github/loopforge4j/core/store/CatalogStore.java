package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.CatalogAsset;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
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

    Optional<RepositoryDefinition> repository(String id);

    List<RepositoryDefinition> repositories();

    RepositoryDefinition saveRepository(RepositoryDefinition definition);

    boolean deleteRepository(String id);

    /** Rules, skills and MCP servers, keyed by kind. */
    <T extends CatalogAsset> Optional<T> asset(AssetKind<T> kind, String id);

    <T extends CatalogAsset> List<T> assets(AssetKind<T> kind);

    /** Upserts and returns the stored asset with a fresh {@code updatedAt}. */
    <T extends CatalogAsset> T saveAsset(AssetKind<T> kind, T asset);

    boolean deleteAsset(AssetKind<?> kind, String id);
}
