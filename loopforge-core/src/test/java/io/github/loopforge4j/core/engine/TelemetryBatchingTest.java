package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.Telemetry;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TelemetryBatchingTest {

    /** Counts write transactions for events. */
    static final class CountingStore implements io.github.loopforge4j.core.store.RunStore {
        final InMemoryRunStore delegate = new InMemoryRunStore();
        final AtomicInteger appends = new AtomicInteger();

        @Override
        public List<RunEvent> appendEvents(String runId, List<RunEvent> events) {
            appends.incrementAndGet();
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
    }

    @Test
    void telemetryIsWrittenInBatchesAndKeepsItsOrderAgainstStateChanges() {
        CountingStore store = new CountingStore();
        Run run = Run.create(
                "run-b",
                WorkflowDefinition.builder("wf")
                        .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                                .script("true")
                                .build())
                        .build(),
                "",
                ExecutionContext.EMPTY,
                Instant.now());
        store.insert(run, List.of());
        List<List<RunEvent>> notified = new java.util.concurrent.CopyOnWriteArrayList<>();
        TelemetryBuffer buffer =
                new TelemetryBuffer(store, Duration.ofMinutes(5), (id, events) -> notified.add(events));
        for (int i = 0; i < 450; i++) {
            buffer.add("run-b", event("run-b", "t" + i));
        }
        // two full batches were written synchronously, the rest waits
        assertThat(store.appends.get()).isEqualTo(2);
        assertThat(store.events("run-b", 0, 1000)).hasSize(400);
        buffer.flush("run-b");
        store.update("run-b", c -> {
            c.event(null, "run.paused", "state change");
            return true;
        });
        buffer.add("run-b", event("run-b", "after"));
        buffer.close();
        List<String> types =
                store.events("run-b", 0, 1000).stream().map(RunEvent::type).toList();
        assertThat(types).hasSize(452);
        assertThat(types.get(449)).isEqualTo("t449");
        assertThat(types.get(450)).isEqualTo("run.paused");
        assertThat(types.get(451)).isEqualTo("after");
        assertThat(store.appends.get()).isEqualTo(4);
        assertThat(notified.stream().mapToInt(List::size).sum()).isEqualTo(451);
    }

    @Test
    void engineEventsAreFlushedBeforeStateChangesAndReads() throws Exception {
        CountingStore store = new CountingStore();
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("lf-batch");
        try (EngineFixture f = new EngineFixture(root, store, true)) {
            f.workflow(WorkflowDefinition.builder("wf")
                    .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                            .script("for i in $(seq 1 50); do echo line$i; done")
                            .build())
                    .build());
            Run run = f.start("wf");
            f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            List<String> types = f.eventTypes(run.id());
            assertThat(types.indexOf("script.completed")).isLessThan(types.indexOf("node.succeeded"));
            assertThat(types).contains("run.succeeded", "sandbox.closed");
            for (int i = 0; i < 100; i++) {
                f.engine.emit(run.id(), "a", Telemetry.of("output", "agent.output", "progress", "x" + i));
            }
            int before = store.appends.get();
            assertThat(f.engine.events(run.id(), 0, 0)).hasSizeGreaterThan(100);
            assertThat(store.appends.get() - before).isEqualTo(1);
        }
    }

    @Test
    void concurrentProducersNeverLoseEventsToTheFlusher() throws Exception {
        InMemoryRunStore store = new InMemoryRunStore();
        List<String> ids = List.of("r0", "r1", "r2", "r3");
        for (String id : ids) {
            store.insert(
                    Run.create(
                            id,
                            WorkflowDefinition.builder("wf")
                                    .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                                            .script("true")
                                            .build())
                                    .build(),
                            "",
                            ExecutionContext.EMPTY,
                            Instant.now()),
                    List.of());
        }
        TelemetryBuffer buffer = new TelemetryBuffer(store, Duration.ofMillis(1), (id, events) -> {});
        int threads = 8;
        int perThread = 6_000;
        List<Thread> producers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int n = t;
            producers.add(Thread.ofPlatform().start(() -> {
                for (int i = 0; i < perThread; i++) {
                    buffer.add(ids.get((n + i) % ids.size()), event(ids.get((n + i) % ids.size()), "e"));
                    if (i % 97 == 0) {
                        Thread.yield();
                    }
                }
            }));
        }
        for (Thread producer : producers) {
            producer.join();
        }
        buffer.close();
        long stored = ids.stream()
                .mapToLong(id -> store.events(id, 0, 1_000_000).size())
                .sum();
        assertThat(stored).isEqualTo((long) threads * perThread);
    }

    private static RunEvent event(String runId, String type) {
        return new RunEvent(0, runId, null, "output", type, "info", type, java.util.Map.of(), Instant.now());
    }
}
