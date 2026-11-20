package io.github.loopforge4j.core.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Registry of live engine instances (upstream {@code engine_instances}). Instances heartbeat; an
 * instance whose heartbeat is older than the lease TTL is considered gone.
 */
public interface InstanceRegistry {

    /**
     * One engine process.
     *
     * @param host workspace host the instance serves
     * @param pid operating system process id, used to detect a dead process on the same host at once
     */
    record Instance(String id, String host, long pid, Instant startedAt, Instant heartbeatAt) {}

    /** Inserts or refreshes the instance. */
    void heartbeat(Instance instance);

    Optional<Instance> find(String id);

    List<Instance> instances();

    void remove(String id);
}
