package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Unit of work over one run: the private copy being mutated plus the events and artifacts that are
 * committed atomically with it.
 */
public final class RunChange {

    private final Run run;
    private final Clock clock;
    private final Supplier<List<Artifact>> existingArtifacts;
    private final List<RunEvent> events = new ArrayList<>();
    private final List<Artifact> artifacts = new ArrayList<>();

    public RunChange(Run run, Clock clock, Supplier<List<Artifact>> existingArtifacts) {
        this.run = run;
        this.clock = clock;
        this.existingArtifacts = existingArtifacts;
    }

    public Run run() {
        return run;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Records a lifecycle event in the same transaction as the state change. */
    public void event(String nodeId, String type, String message) {
        events.add(RunEvent.lifecycle(run.id(), nodeId, type, message, now()));
    }

    public void event(String nodeId, String type, String level, String message, Map<String, Object> attributes) {
        events.add(new RunEvent(0, run.id(), nodeId, RunEvent.LIFECYCLE, type, level, message, attributes, now()));
    }

    public void artifact(Artifact artifact) {
        artifacts.add(artifact);
    }

    /** Artifacts already committed for this run plus the ones staged in this change. */
    public List<Artifact> allArtifacts() {
        List<Artifact> all = new ArrayList<>(existingArtifacts.get());
        all.addAll(artifacts);
        return all;
    }

    public List<RunEvent> pendingEvents() {
        return Collections.unmodifiableList(events);
    }

    public List<Artifact> pendingArtifacts() {
        return Collections.unmodifiableList(artifacts);
    }
}
