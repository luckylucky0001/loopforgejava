package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.RepositoryDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory {@link CatalogStore}. Definitions are immutable records, so no copies are needed. */
public final class InMemoryCatalogStore implements CatalogStore {

    private final Clock clock;
    private final Map<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();
    private final Map<String, AgentDefinition> agents = new ConcurrentHashMap<>();
    private final Map<String, RepositoryDefinition> repositories = new ConcurrentHashMap<>();
    private final Map<String, Map<String, io.github.loopforge4j.core.domain.CatalogAsset>> assets =
            new ConcurrentHashMap<>();

    public InMemoryCatalogStore() {
        this(Clock.systemUTC());
    }

    public InMemoryCatalogStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<WorkflowDefinition> workflow(String id) {
        return Optional.ofNullable(workflows.get(id));
    }

    @Override
    public List<WorkflowDefinition> workflows() {
        return workflows.values().stream()
                .sorted(Comparator.comparing(WorkflowDefinition::id))
                .toList();
    }

    @Override
    public WorkflowDefinition saveWorkflow(WorkflowDefinition definition) {
        return workflows.compute(definition.id(), (id, previous) -> {
            WorkflowDefinition base = previous == null
                    ? definition
                    : new WorkflowDefinition(
                            definition.id(),
                            definition.name(),
                            definition.version(),
                            previous.revision(),
                            definition.status(),
                            definition.projectId(),
                            definition.owner(),
                            definition.tags(),
                            definition.description(),
                            definition.sandboxProfileId(),
                            definition.nodes(),
                            definition.edges(),
                            previous.createdAt(),
                            definition.updatedAt(),
                            definition.preview(),
                            definition.sopLane());
            return base.nextRevision(clock.instant());
        });
    }

    @Override
    public boolean deleteWorkflow(String id) {
        return workflows.remove(id) != null;
    }

    @Override
    public Optional<AgentDefinition> agent(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    @Override
    public List<AgentDefinition> agents() {
        return agents.values().stream()
                .sorted(Comparator.comparing(AgentDefinition::id))
                .toList();
    }

    @Override
    public AgentDefinition saveAgent(AgentDefinition definition) {
        AgentDefinition stamped = definition.withUpdatedAt(clock.instant());
        agents.put(stamped.id(), stamped);
        return stamped;
    }

    @Override
    public boolean deleteAgent(String id) {
        return agents.remove(id) != null;
    }

    @Override
    public Optional<RepositoryDefinition> repository(String id) {
        return Optional.ofNullable(repositories.get(id));
    }

    @Override
    public List<RepositoryDefinition> repositories() {
        return repositories.values().stream()
                .sorted(Comparator.comparing(RepositoryDefinition::id))
                .toList();
    }

    @Override
    public RepositoryDefinition saveRepository(RepositoryDefinition definition) {
        RepositoryDefinition stamped = definition.withUpdatedAt(clock.instant());
        repositories.put(stamped.id(), stamped);
        return stamped;
    }

    @Override
    public boolean deleteRepository(String id) {
        return repositories.remove(id) != null;
    }

    @Override
    public <T extends io.github.loopforge4j.core.domain.CatalogAsset> Optional<T> asset(
            io.github.loopforge4j.core.domain.AssetKind<T> kind, String id) {
        return Optional.ofNullable(bucket(kind).get(id)).map(kind::cast);
    }

    @Override
    public <T extends io.github.loopforge4j.core.domain.CatalogAsset> List<T> assets(
            io.github.loopforge4j.core.domain.AssetKind<T> kind) {
        return bucket(kind).values().stream()
                .map(kind::cast)
                .sorted(java.util.Comparator.comparing(io.github.loopforge4j.core.domain.CatalogAsset::id))
                .toList();
    }

    @Override
    public <T extends io.github.loopforge4j.core.domain.CatalogAsset> T saveAsset(
            io.github.loopforge4j.core.domain.AssetKind<T> kind, T asset) {
        T stamped = kind.cast(asset.withUpdatedAt(clock.instant()));
        bucket(kind).put(stamped.id(), stamped);
        return stamped;
    }

    @Override
    public boolean deleteAsset(io.github.loopforge4j.core.domain.AssetKind<?> kind, String id) {
        return bucket(kind).remove(id) != null;
    }

    private Map<String, io.github.loopforge4j.core.domain.CatalogAsset> bucket(
            io.github.loopforge4j.core.domain.AssetKind<?> kind) {
        return assets.computeIfAbsent(kind.wire(), k -> new ConcurrentHashMap<>());
    }
}
