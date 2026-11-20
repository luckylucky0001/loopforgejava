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
                            definition.updatedAt());
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
}
