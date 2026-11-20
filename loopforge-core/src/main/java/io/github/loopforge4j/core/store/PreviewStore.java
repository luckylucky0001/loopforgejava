package io.github.loopforge4j.core.store;

import io.github.loopforge4j.core.domain.PreviewSettings;
import java.util.List;

/** Keeps the static sites preview nodes archive (one per run, the latest wins). */
@FunctionalInterface
public interface PreviewStore {

    /** Nothing is kept (execution machines, tests). */
    PreviewStore NONE = (runId, nodeId, settings, files) -> false;

    /** A file of the site, by path relative to its root. */
    record File(String path, byte[] content) {}

    /** @return whether the preview was kept */
    boolean save(String runId, String nodeId, PreviewSettings settings, List<File> files);
}
