package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for runs. Implementations must guarantee:
 *
 * <ul>
 *   <li>readers get private copies (mutating a returned run never changes stored state);
 *   <li>{@link #update} is atomic: run state, events and artifacts commit together or not at all,
 *       and a concurrent writer never overwrites a newer revision;
 *   <li>event sequences are strictly increasing in commit order.
 * </ul>
 */
public interface RunStore {

    /** Inserts a new run (revision 1) together with its initial events. */
    void insert(Run run, List<RunEvent> initialEvents);

    Optional<Run> find(String runId);

    /** Newest first. */
    List<Run> list(RunQuery query);

    /**
     * Loads the run, applies the mutator and persists the result. Retries on optimistic conflicts.
     *
     * @return the stored state after the call; empty when the run does not exist
     */
    Optional<Run> update(String runId, RunMutator mutator);

    /** Appends telemetry that does not change run state. */
    List<RunEvent> appendEvents(String runId, List<RunEvent> events);

    /** Events with a sequence greater than {@code afterSequence}, oldest first. */
    List<RunEvent> events(String runId, long afterSequence, int limit);

    /** All artifacts of a run including content, in creation order. */
    List<Artifact> artifacts(String runId);

    Optional<Artifact> artifact(String runId, String artifactId);

    /**
     * Ids of terminal runs last updated before {@code before}, oldest first (retention). Stores
     * without retention support return nothing.
     */
    default List<String> terminalRunsBefore(java.time.Instant before, int limit) {
        return List.of();
    }

    /**
     * A page of events, oldest first: after {@code after} when given, else before {@code before} when
     * given, else the newest page; only {@code nodeId}'s events when given.
     */
    default List<RunEvent> eventPage(String runId, String nodeId, Long after, Long before, int limit) {
        List<RunEvent> all = events(runId, 0, 0).stream()
                .filter(e -> nodeId == null || nodeId.equals(e.nodeId()))
                .toList();
        if (after != null) {
            return all.stream().filter(e -> e.sequence() > after).limit(limit).toList();
        }
        List<RunEvent> older = before == null
                ? all
                : all.stream().filter(e -> e.sequence() < before).toList();
        return older.subList(Math.max(0, older.size() - limit), older.size());
    }

    /** Deletes a run together with its events and artifacts; returns whether it existed. */
    default boolean delete(String runId) {
        return false;
    }

    /**
     * Deletes events of the given categories created before {@code before} (telemetry retention);
     * returns the number of deleted events.
     */
    default int pruneEvents(java.time.Instant before, java.util.Set<String> categories) {
        return 0;
    }
}
