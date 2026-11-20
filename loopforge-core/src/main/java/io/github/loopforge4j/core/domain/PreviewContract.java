package io.github.loopforge4j.core.domain;

import java.time.Duration;

/**
 * The SOP's frontend delivery contract (upstream {@code PreviewContract}), frozen into the run
 * snapshot. Enabling it adds an agent step that builds a static site; the site is archived as soon
 * as that step succeeds, so a later approval can open the preview while the run still waits.
 *
 * @param kind only {@code static} for now
 * @param executorId the CLI the build step runs with (default {@code codebuddy})
 * @param model the build step's model (default {@code default})
 * @param sourcePath the built site, relative to the run workspace (default {@code dist})
 * @param indexFile the entry page inside it (default {@code index.html})
 * @param spaFallback client-side routes fall back to the entry page
 * @param required recorded for parity; the build step's artifact contract already requires the entry
 * @param captureScreenshot capture the entry page once the site is ready (needs a headless browser)
 * @param retentionSec how long the archived site is kept (at most 30 days)
 */
public record PreviewContract(
        boolean enabled,
        String kind,
        String executorId,
        String model,
        String sourcePath,
        String indexFile,
        boolean spaFallback,
        boolean required,
        boolean captureScreenshot,
        long retentionSec) {

    public static final String STATIC = "static";
    public static final String DEFAULT_EXECUTOR = "codebuddy";
    public static final String DEFAULT_MODEL = "default";
    public static final String DEFAULT_SOURCE = "dist";
    public static final String DEFAULT_INDEX = "index.html";
    public static final long MAX_RETENTION_SEC = 30L * 24 * 3600;

    public PreviewContract {
        kind = blank(kind) ? STATIC : kind.strip();
        executorId = blank(executorId) ? DEFAULT_EXECUTOR : executorId.strip();
        model = blank(model) ? DEFAULT_MODEL : model.strip();
        sourcePath = blank(sourcePath)
                ? DEFAULT_SOURCE
                : trimSlashes(sourcePath.strip().replace('\\', '/'));
        indexFile = blank(indexFile) ? DEFAULT_INDEX : indexFile.strip();
        retentionSec = retentionSec <= 0 || retentionSec > MAX_RETENTION_SEC ? MAX_RETENTION_SEC : retentionSec;
    }

    /** An enabled static-site contract: the build step is attached and its site archived. */
    public boolean isStatic() {
        return enabled && STATIC.equalsIgnoreCase(kind);
    }

    /** The entry page relative to the workspace, e.g. {@code dist/index.html}. */
    public String entry() {
        return trimSlashes(sourcePath + "/" + indexFile);
    }

    public Duration retention() {
        return Duration.ofSeconds(retentionSec);
    }

    /** The archive settings this contract implies. */
    public PreviewSettings settings() {
        return new PreviewSettings(sourcePath, indexFile, spaFallback, captureScreenshot);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }
}
