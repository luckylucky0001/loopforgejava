package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.json.Json;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory {@link RunStore}. Runs are kept as JSON so every read is a private copy, which
 * mirrors the isolation of the JDBC adapter.
 */
public final class InMemoryRunStore implements RunStore {

    private final Clock clock;
    private final Map<String, String> runs = new ConcurrentHashMap<>();
    private final Map<String, List<RunEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, List<Artifact>> artifacts = new ConcurrentHashMap<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private long sequence;

    public InMemoryRunStore() {
        this(Clock.systemUTC());
    }

    public InMemoryRunStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void insert(Run run, List<RunEvent> initialEvents) {
        writeLock.lock();
        try {
            if (runs.containsKey(run.id())) {
                throw new IllegalStateException("run " + run.id() + " already exists");
            }
            Run copy = Json.copy(run, Run.class);
            copy.revision(1);
            runs.put(copy.id(), Json.write(copy));
            artifacts.put(copy.id(), new CopyOnWriteArrayList<>());
            events.put(copy.id(), new CopyOnWriteArrayList<>());
            appendLocked(copy.id(), initialEvents);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Optional<Run> find(String runId) {
        return Optional.ofNullable(runs.get(runId)).map(json -> Json.read(json, Run.class));
    }

    @Override
    public List<Run> list(RunQuery query) {
        return runs.values().stream()
                .map(json -> Json.read(json, Run.class))
                .filter(query::matches)
                .sorted(Comparator.comparing(Run::createdAt)
                        .thenComparing(Run::id)
                        .reversed())
                .limit(query.limit())
                .toList();
    }

    @Override
    public Optional<Run> update(String runId, RunMutator mutator) {
        writeLock.lock();
        try {
            String json = runs.get(runId);
            if (json == null) {
                return Optional.empty();
            }
            Run run = Json.read(json, Run.class);
            RunChange change = new RunChange(run, clock, () -> artifacts.getOrDefault(runId, List.of()));
            if (!mutator.apply(change)) {
                return Optional.of(Json.read(json, Run.class));
            }
            run.revision(run.revision() + 1);
            run.updatedAt(clock.instant());
            runs.put(runId, Json.write(run));
            artifacts.get(runId).addAll(change.pendingArtifacts());
            appendLocked(runId, change.pendingEvents());
            return Optional.of(Json.read(runs.get(runId), Run.class));
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public List<RunEvent> appendEvents(String runId, List<RunEvent> newEvents) {
        writeLock.lock();
        try {
            if (!runs.containsKey(runId)) {
                return List.of();
            }
            return appendLocked(runId, newEvents);
        } finally {
            writeLock.unlock();
        }
    }

    private List<RunEvent> appendLocked(String runId, List<RunEvent> newEvents) {
        List<RunEvent> stored = new ArrayList<>(newEvents.size());
        for (RunEvent event : newEvents) {
            stored.add(event.withSequence(++sequence));
        }
        events.get(runId).addAll(stored);
        return stored;
    }

    @Override
    public List<RunEvent> events(String runId, long afterSequence, int limit) {
        return events.getOrDefault(runId, List.of()).stream()
                .filter(e -> e.sequence() > afterSequence)
                .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                .toList();
    }

    @Override
    public List<Artifact> artifacts(String runId) {
        return List.copyOf(artifacts.getOrDefault(runId, List.of()));
    }

    @Override
    public Optional<Artifact> artifact(String runId, String artifactId) {
        return artifacts.getOrDefault(runId, List.of()).stream()
                .filter(a -> a.id().equals(artifactId))
                .findFirst();
    }

    @Override
    public List<String> terminalRunsBefore(java.time.Instant before, int limit) {
        return runs.values().stream()
                .map(raw -> Json.read(raw, Run.class))
                .filter(r -> r.status().terminal()
                        && r.updatedAt() != null
                        && r.updatedAt().isBefore(before))
                .sorted(java.util.Comparator.comparing(Run::updatedAt))
                .limit(limit)
                .map(Run::id)
                .toList();
    }

    @Override
    public boolean delete(String runId) {
        writeLock.lock();
        try {
            events.remove(runId);
            artifacts.remove(runId);
            return runs.remove(runId) != null;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int pruneEvents(java.time.Instant before, java.util.Set<String> categories) {
        writeLock.lock();
        try {
            int removed = 0;
            for (List<RunEvent> list : events.values()) {
                int size = list.size();
                list.removeIf(
                        e -> categories.contains(e.category()) && e.timestamp().isBefore(before));
                removed += size - list.size();
            }
            return removed;
        } finally {
            writeLock.unlock();
        }
    }
}
