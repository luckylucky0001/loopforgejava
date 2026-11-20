package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.store.InMemoryRunStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RunStreamBroadcasterTest {

    @Test
    void capsSubscribersPerRun() {
        RunStreamBroadcaster broadcaster = new RunStreamBroadcaster(new InMemoryRunStore());
        broadcaster.start();
        for (int i = 0; i < RunStreamBroadcaster.MAX_PER_RUN; i++) {
            broadcaster.subscribe("run-1", 0);
        }
        assertThatThrownBy(() -> broadcaster.subscribe("run-1", 0))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("429");
        broadcaster.stop();
    }

    @Test
    void stopCompletesStreamsAndRefusesNewOnes() {
        RunStreamBroadcaster broadcaster = new RunStreamBroadcaster(new InMemoryRunStore());
        broadcaster.start();
        broadcaster.subscribe("run-2", 0);
        broadcaster.subscribe("run-2", 0);
        broadcaster.stop();
        await().atMost(Duration.ofSeconds(5)).until(() -> broadcaster.subscriberCount("run-2") == 0);
        assertThat(broadcaster.isRunning()).isFalse();
        assertThatThrownBy(() -> broadcaster.subscribe("run-2", 0)).hasMessageContaining("503");
    }
}
