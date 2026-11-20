package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import java.util.List;

/**
 * Observer of committed run changes (e.g. the SSE broadcaster). Callbacks run on engine threads and
 * must return quickly; slow consumers should hand off to their own queue.
 */
public interface RunListener {

    default void runChanged(Run run) {}

    default void eventsAppended(String runId, List<RunEvent> events) {}
}
