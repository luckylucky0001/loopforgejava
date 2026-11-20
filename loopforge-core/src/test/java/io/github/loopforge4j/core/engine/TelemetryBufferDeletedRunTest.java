package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import io.github.loopforge4j.core.store.StoreException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Telemetry buffered for a run that is deleted meanwhile is dropped, not retried forever. */
class TelemetryBufferDeletedRunTest {

    /** Like a database with a foreign key: events of a missing run cannot be written. */
    static final class StrictStore implements io.github.loopforge4j.core.store.RunStore {
        final InMemoryRunStore delegate = new InMemoryRunStore();
        final AtomicInteger failures = new AtomicInteger();

        @Override
        public List<RunEvent> appendEvents(String runId, List<RunEvent> events) {
            if (delegate.find(runId).isEmpty()) {
                failures.incrementAndGet();
                throw new StoreException("run store write failed", null);
            }
            return delegate.appendEvents(runId, events);
        }

        @Override
        public void insert(Run run, List<RunEvent> initialEvents) {
            delegate.insert(run, initialEvents);
        }

        @Override
        public java.util.Optional<Run> find(String runId) {
            return delegate.find(runId);
        }

        @Override
        public List<Run> list(io.github.loopforge4j.core.store.RunQuery query) {
            return delegate.list(query);
        }

        @Override
        public java.util.Optional<Run> update(String runId, io.github.loopforge4j.core.store.RunMutator mutator) {
            return delegate.update(runId, mutator);
        }

        @Override
        public List<RunEvent> events(String runId, long afterSequence, int limit) {
            return delegate.events(runId, afterSequence, limit);
        }

        @Override
        public List<io.github.loopforge4j.core.domain.Artifact> artifacts(String runId) {
            return delegate.artifacts(runId);
        }

        @Override
        public java.util.Optional<io.github.loopforge4j.core.domain.Artifact> artifact(
                String runId, String artifactId) {
            return delegate.artifact(runId, artifactId);
        }

        @Override
        public boolean delete(String runId) {
            return delegate.delete(runId);
        }
    }

    static RunEvent event(String runId) {
        return new RunEvent(0, runId, "a", "output", "agent.output", "info", "chunk", Map.of(), Instant.EPOCH);
    }

    @Test
    void telemetryOfADeletedRunIsDiscarded() {
        StrictStore store = new StrictStore();
        Run run = Run.create(
                "r1",
                WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("a", NodeType.APPROVAL).build())
                        .build(),
                "x",
                ExecutionContext.EMPTY,
                Instant.EPOCH);
        store.insert(run, List.of());
        TelemetryBuffer buffer = new TelemetryBuffer(store, Duration.ofHours(1), (id, events) -> {});
        try {
            buffer.add("r1", event("r1"));
            store.delete("r1");
            // a late chunk arrives after the deletion as well
            buffer.add("r1", event("r1"));
            buffer.flushAll();
            assertThat(store.failures).hasValue(1);
            assertThat(buffer.pendingRuns()).isZero();
            buffer.flushAll();
            assertThat(store.failures).hasValue(1);
        } finally {
            buffer.close();
        }
    }
}
