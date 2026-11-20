package io.github.loopforge4j.core.store;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Registry for one JVM; share one instance between engines to simulate a cluster. */
public final class InMemoryInstanceRegistry implements InstanceRegistry {

    private final Map<String, Instance> instances = new ConcurrentHashMap<>();

    @Override
    public void heartbeat(Instance instance) {
        instances.put(instance.id(), instance);
    }

    @Override
    public Optional<Instance> find(String id) {
        return Optional.ofNullable(instances.get(id));
    }

    @Override
    public List<Instance> instances() {
        return instances.values().stream()
                .sorted(Comparator.comparing(Instance::id))
                .toList();
    }

    @Override
    public void remove(String id) {
        instances.remove(id);
    }
}
