package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunStreamBroadcasterTest {

    /** Streams of a live run stay open (streams of a missing or finished run complete on their own). */
    private static InMemoryRunStore storeWithLiveRun(String runId) {
        InMemoryRunStore store = new InMemoryRunStore();
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", NodeType.APPROVAL).build())
                .build();
        Run run = Run.create(runId, wf, "", ExecutionContext.EMPTY, Instant.now());
        run.status(RunStatus.WAITING);
        store.insert(run, List.of());
        return store;
    }

    @Test
    void capsSubscribersPerRun() {
        RunStreamBroadcaster broadcaster = new RunStreamBroadcaster(storeWithLiveRun("run-1"));
        broadcaster.start();
        for (int i = 0; i < RunStreamBroadcaster.MAX_PER_RUN; i++) {
            broadcaster.subscribe("run-1", 0);
        }
        assertThat(broadcaster.subscriberCount("run-1")).isEqualTo(RunStreamBroadcaster.MAX_PER_RUN);
        assertThatThrownBy(() -> broadcaster.subscribe("run-1", 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
        broadcaster.stop();
    }

    @Test
    void stopCompletesStreamsAndRefusesNewOnes() {
        RunStreamBroadcaster broadcaster = new RunStreamBroadcaster(storeWithLiveRun("run-2"));
        broadcaster.start();
        broadcaster.subscribe("run-2", 0);
        broadcaster.subscribe("run-2", 0);
        assertThat(broadcaster.subscriberCount("run-2")).isEqualTo(2);
        broadcaster.stop();
        await().atMost(Duration.ofSeconds(5)).until(() -> broadcaster.subscriberCount("run-2") == 0);
        assertThat(broadcaster.isRunning()).isFalse();
        assertThatThrownBy(() -> broadcaster.subscribe("run-2", 0)).hasMessageContaining("503");
    }

    @Test
    void streamsOfMissingRunsCloseThemselves() {
        RunStreamBroadcaster broadcaster = new RunStreamBroadcaster(new InMemoryRunStore());
        broadcaster.start();
        broadcaster.subscribe("ghost", 0);
        await().atMost(Duration.ofSeconds(5)).until(() -> broadcaster.subscriberCount("ghost") == 0);
        broadcaster.stop();
    }
}
