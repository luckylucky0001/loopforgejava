package io.github.loopforge4j.core.domain;

/**
 * What a {@code preview} node archives (upstream preview contract).
 *
 * @param path directory of the built site, relative to the run workspace; {@code dist} by default
 * @param index entry page inside it; {@code index.html} by default
 * @param spa unknown paths serve the entry page (single-page applications)
 * @param screenshot capture the entry page once the site is ready (upstream {@code captureScreenshot};
 *     needs a headless browser on the server)
 */
public record PreviewSettings(String path, String index, boolean spa, boolean screenshot) {

    public static final PreviewSettings DEFAULT = new PreviewSettings(null, null, false);

    public PreviewSettings {
        path = path == null || path.isBlank() ? "dist" : path.strip();
        index = index == null || index.isBlank() ? "index.html" : index.strip();
    }

    public PreviewSettings(String path, String index, boolean spa) {
        this(path, index, spa, false);
    }
}
