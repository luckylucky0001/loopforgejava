package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.PreviewSettings;
import io.github.loopforge4j.core.preview.PreviewArchive;
import java.time.Duration;
import java.util.Optional;

/**
 * Keeps the static site a run's preview archives (one per run, the newest build wins) and the
 * preview's state, as upstream's preview service records it.
 */
public interface PreviewStore {

    String PENDING = "pending";
    String ARCHIVING = "archiving";
    String READY = "ready";
    String SKIPPED = "skipped";
    String FAILED = "failed";
    String EXPIRED = "expired";

    /** Nothing is kept (tests, an engine without a database). */
    PreviewStore NONE = new PreviewStore() {};

    /**
     * Archives {@code site} (files are read from disk one at a time) and makes it ready.
     *
     * @return whether it was kept
     */
    default boolean save(
            String runId, String nodeId, PreviewSettings settings, PreviewArchive.Site site, Duration retention)
            throws java.io.IOException {
        return false;
    }

    /** The preview was intentionally not produced (no frontend project); the run is unaffected. */
    default void skip(String runId, String nodeId, PreviewSettings settings, String reason) {}

    /** Archiving failed; the build step itself is unaffected. */
    default void fail(String runId, String nodeId, PreviewSettings settings, String reason) {}

    /** The state of the run's preview, if it has one. */
    default Optional<String> status(String runId) {
        return Optional.empty();
    }
}
