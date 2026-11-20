package io.github.loopforge4j.core.store;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link RunStore} adapter must provide. Adapters extend this class and supply a
 * fresh store; see {@code InMemoryRunStoreTest} and the server's {@code JdbcRunStoreTest}.
 */
public abstract class RunStoreContract {

    protected abstract RunStore store();

    protected static Run newRun(String workflowId) {
        WorkflowDefinition wf = WorkflowDefinition.builder(workflowId)
                .node(WorkflowNode.builder("a", NodeType.SCRIPT).script("true").build())
                .build();
        Run run = Run.create(
                "run-" + UUID.randomUUID(),
                wf,
                "input",
                ExecutionContext.triggeredBy("t"),
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
        run.status(RunStatus.RUNNING);
        return run;
    }

    @Test
    void insertAndFindReturnPrivateCopies() {
        Run run = newRun("wf");
        store().insert(run, List.of(RunEvent.lifecycle(run.id(), null, "run.started", "go", Instant.now())));
        Run first = store().find(run.id()).orElseThrow();
        first.requireNode("a").status(NodeStatus.FAILED);
        Run second = store().find(run.id()).orElseThrow();
        assertThat(second.requireNode("a").status()).isEqualTo(NodeStatus.PENDING);
        assertThat(second.revision()).isEqualTo(1);
        assertThat(store().events(run.id(), 0, 10)).extracting(RunEvent::type).containsExactly("run.started");
        assertThat(store().find("missing")).isEmpty();
    }

    @Test
    void updateCommitsStateEventsAndArtifactsTogether() {
        Run run = newRun("wf");
        store().insert(run, List.of());
        Run updated = store().update(run.id(), c -> {
                    c.run().requireNode("a").status(NodeStatus.SUCCEEDED);
                    c.event("a", "node.succeeded", "done");
                    c.artifact(new Artifact(
                            "art-" + UUID.randomUUID(),
                            run.id(),
                            "a",
                            1,
                            "result",
                            "result.md",
                            "text/markdown",
                            2,
                            "0".repeat(64),
                            "ok",
                            Instant.now().truncatedTo(ChronoUnit.MILLIS)));
                    return true;
                })
                .orElseThrow();
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(store().find(run.id()).orElseThrow().requireNode("a").status())
                .isEqualTo(NodeStatus.SUCCEEDED);
        assertThat(store().events(run.id(), 0, 10)).extracting(RunEvent::type).containsExactly("node.succeeded");
        List<Artifact> artifacts = store().artifacts(run.id());
        assertThat(artifacts).singleElement().extracting(Artifact::content).isEqualTo("ok");
        assertThat(store().artifact(run.id(), artifacts.getFirst().id())).isPresent();
    }

    @Test
    void rejectedMutationWritesNothing() {
        Run run = newRun("wf");
        store().insert(run, List.of());
        Run result = store().update(run.id(), c -> {
                    c.run().status(RunStatus.FAILED);
                    c.event(null, "ignored", "x");
                    return false;
                })
                .orElseThrow();
        assertThat(result.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(result.revision()).isEqualTo(1);
        assertThat(store().events(run.id(), 0, 10)).isEmpty();
        assertThat(store().update("missing", c -> true)).isEmpty();
    }

    @Test
    void mutatorExceptionsPropagateWithoutWriting() {
        Run run = newRun("wf");
        store().insert(run, List.of());
        try {
            store().update(run.id(), c -> {
                c.run().status(RunStatus.FAILED);
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // expected
        }
        assertThat(store().find(run.id()).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void concurrentUpdatesNeverLoseWrites() throws Exception {
        Run run = newRun("wf");
        store().insert(run, List.of());
        int writers = 8;
        int perWriter = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        for (int w = 0; w < writers; w++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        store().update(run.id(), c -> {
                            c.run()
                                    .requireNode("a")
                                    .attempt(c.run().requireNode("a").attempt() + 1);
                            c.event("a", "tick", "t");
                            return true;
                        });
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        assertThat(failures).hasValue(0);
        Run stored = store().find(run.id()).orElseThrow();
        assertThat(stored.requireNode("a").attempt()).isEqualTo(writers * perWriter);
        assertThat(stored.revision()).isEqualTo(1 + writers * perWriter);
        List<RunEvent> events = store().events(run.id(), 0, 0);
        assertThat(events).hasSize(writers * perWriter);
        assertThat(events).extracting(RunEvent::sequence).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void eventsArePagedBySequence() {
        Run run = newRun("wf");
        store().insert(run, List.of());
        List<RunEvent> appended = store().appendEvents(
                        run.id(),
                        List.of(
                                RunEvent.lifecycle(run.id(), null, "one", "1", Instant.now()),
                                RunEvent.lifecycle(run.id(), null, "two", "2", Instant.now()),
                                RunEvent.lifecycle(run.id(), null, "three", "3", Instant.now())));
        assertThat(appended).extracting(RunEvent::sequence).isSorted().allMatch(s -> s > 0);
        long cursor = appended.getFirst().sequence();
        assertThat(store().events(run.id(), cursor, 1))
                .extracting(RunEvent::type)
                .containsExactly("two");
        assertThat(store().events(run.id(), cursor, 10))
                .extracting(RunEvent::type)
                .containsExactly("two", "three");
    }

    @Test
    void listFiltersByStatusWorkflowAndParent() {
        String suffix = UUID.randomUUID().toString();
        Run a = newRun("wf-a-" + suffix);
        Run b = newRun("wf-b-" + suffix);
        store().insert(a, List.of());
        store().insert(b, List.of());
        store().update(b.id(), c -> {
            c.run().status(RunStatus.SUCCEEDED);
            return true;
        });
        assertThat(store().list(new RunQuery(Set.of(RunStatus.SUCCEEDED), null, null, 10)))
                .extracting(Run::id)
                .contains(b.id())
                .doesNotContain(a.id());
        assertThat(store().list(new RunQuery(Set.of(), "wf-a-" + suffix, null, 10)))
                .extracting(Run::id)
                .containsExactly(a.id());
        assertThat(store().list(new RunQuery(Set.of(), null, "nobody", 10))).isEmpty();
    }
}
