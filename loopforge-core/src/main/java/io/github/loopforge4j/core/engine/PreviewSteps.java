package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.PreviewContract;
import io.github.loopforge4j.core.domain.PreviewSettings;
import io.github.loopforge4j.core.preview.FrontendDetector;
import io.github.loopforge4j.core.preview.PreviewArchive;
import io.github.loopforge4j.core.runtime.Telemetry;
import java.io.IOException;
import java.nio.file.Path;

/** Archiving the site of an SOP's frontend preview (after its build step, or when the run succeeds). */
final class PreviewSteps {

    static final String SKIPPED = "工作区没有前端工程，已跳过预览";

    private PreviewSteps() {}

    /** @return a line for the node output or the run's events */
    static String archiveBuilt(
            RunEngine engine, String runId, String nodeId, PreviewContract contract, Path workspace) {
        PreviewSettings settings = contract.settings();
        try {
            PreviewArchive.Site site = PreviewArchive.collect(workspace, settings, engine.previewLimits());
            boolean kept = engine.previews().save(runId, nodeId, settings, site, contract.retention());
            String summary = kept
                    ? "预览已归档：" + site.files().size() + " 个文件 · " + Math.max(1, site.bytes() / 1024) + " KB · 入口 "
                            + site.index()
                    : "预览已构建（" + site.files().size() + " 个文件），此执行环境不保存预览";
            engine.emit(
                    runId,
                    nodeId,
                    Telemetry.of("preview", kept ? "preview.archive.ready" : "preview.archived", "finish", summary));
            return summary;
        } catch (PreviewArchive.Failure e) {
            if (e.missingIndex() && !FrontendDetector.detect(workspace).frontend()) {
                engine.previews().skip(runId, nodeId, settings, SKIPPED);
                engine.emit(runId, nodeId, Telemetry.of("preview", "preview.skipped", "finish", SKIPPED));
                return SKIPPED;
            }
            return failed(engine, runId, nodeId, settings, e.getMessage());
        } catch (IOException | RuntimeException e) {
            return failed(engine, runId, nodeId, settings, e.toString());
        }
    }

    private static String failed(
            RunEngine engine, String runId, String nodeId, PreviewSettings settings, String message) {
        String clipped = message.length() > 800 ? message.substring(0, 800) : message;
        engine.previews().fail(runId, nodeId, settings, clipped);
        String reason = "预览归档失败：" + clipped;
        engine.emit(
                runId,
                nodeId,
                Telemetry.of("preview", "preview.archive.failed", "finish", reason)
                        .withLevel("warn"));
        return reason;
    }
}
