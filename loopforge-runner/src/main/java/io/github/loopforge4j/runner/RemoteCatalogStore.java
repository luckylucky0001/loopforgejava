package io.github.loopforge4j.runner;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.CatalogAsset;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.store.CatalogStore;
import java.util.List;
import java.util.Optional;

/** The server catalog, read-only, as far as the machine's runs may see it. */
public class RemoteCatalogStore implements CatalogStore {

    private final RunnerClient client;

    public RemoteCatalogStore(RunnerClient client) {
        this.client = client;
    }

    @Override
    public Optional<WorkflowDefinition> workflow(String id) {
        return one("workflows", id, WorkflowDefinition.class);
    }

    @Override
    public List<WorkflowDefinition> workflows() {
        return all("workflows", WorkflowDefinition.class);
    }

    @Override
    public Optional<AgentDefinition> agent(String id) {
        return one("agents", id, AgentDefinition.class);
    }

    @Override
    public List<AgentDefinition> agents() {
        return all("agents", AgentDefinition.class);
    }

    @Override
    public Optional<RepositoryDefinition> repository(String id) {
        return one("repositories", id, RepositoryDefinition.class);
    }

    @Override
    public List<RepositoryDefinition> repositories() {
        return all("repositories", RepositoryDefinition.class);
    }

    @Override
    public <T extends CatalogAsset> Optional<T> asset(AssetKind<T> kind, String id) {
        return one(path(kind), id, kind.type());
    }

    @Override
    public <T extends CatalogAsset> List<T> assets(AssetKind<T> kind) {
        return all(path(kind), kind.type());
    }

    @Override
    public WorkflowDefinition saveWorkflow(WorkflowDefinition definition) {
        throw readOnly();
    }

    @Override
    public boolean deleteWorkflow(String id) {
        throw readOnly();
    }

    @Override
    public AgentDefinition saveAgent(AgentDefinition definition) {
        throw readOnly();
    }

    @Override
    public boolean deleteAgent(String id) {
        throw readOnly();
    }

    @Override
    public RepositoryDefinition saveRepository(RepositoryDefinition definition) {
        throw readOnly();
    }

    @Override
    public boolean deleteRepository(String id) {
        throw readOnly();
    }

    @Override
    public <T extends CatalogAsset> T saveAsset(AssetKind<T> kind, T asset) {
        throw readOnly();
    }

    @Override
    public boolean deleteAsset(AssetKind<?> kind, String id) {
        throw readOnly();
    }

    private <T> Optional<T> one(String type, String id, Class<T> cls) {
        return client.find("/catalog/" + type + "/" + RemoteRunStore.encode(id))
                .map(n -> Json.mapper().convertValue(n, cls));
    }

    private <T> List<T> all(String type, Class<T> cls) {
        var node = client.get("/catalog/" + type);
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(n -> Json.mapper().convertValue(n, cls))
                .toList();
    }

    private static String path(AssetKind<?> kind) {
        return switch (kind.wire()) {
            case "rule" -> "rules";
            case "skill" -> "skills";
            default -> "mcp-servers";
        };
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException("the catalog is managed on the server");
    }
}
