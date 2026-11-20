package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.runtime.browser.CdpBrowser;
import io.github.loopforge4j.core.runtime.browser.CdpPage;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Screenshots of ready previews (upstream {@code captureScreenshot}): a headless browser opens the
 * entry page through a short-lived preview session of its own; every request outside that session's
 * site is blocked, so the page cannot reach anything else from the server.
 */
@Service
public class PreviewScreenshots {

    private static final Logger log = LoggerFactory.getLogger(PreviewScreenshots.class);
    static final int WIDTH = 1280;
    static final int HEIGHT = 800;

    private final JdbcPreviewStore store;
    private final PreviewService previews;
    private final LoopForgeProperties.Browser config;
    /** At most two browsers at a time. */
    private final Semaphore slots = new Semaphore(2);

    private volatile int port;

    public PreviewScreenshots(JdbcPreviewStore store, PreviewService previews, LoopForgeProperties properties) {
        this.store = store;
        this.previews = previews;
        this.config = properties.browser();
    }

    @EventListener
    void started(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
    }

    @EventListener
    void wanted(JdbcPreviewStore.ScreenshotWanted event) {
        Thread.ofVirtual().name("preview-screenshot-" + event.runId()).start(() -> capture(event.runId()));
    }

    /** Takes the screenshot of {@code runId}'s preview now; failures are recorded on the preview. */
    void capture(String runId) {
        Optional<CdpBrowser.Options> options = config.options();
        if (options.isEmpty()) {
            store.screenshotFailed(runId, "服务器上没有可用的无头浏览器（loopforge.browser.binary）");
            return;
        }
        if (port == 0) {
            store.screenshotFailed(runId, "服务尚未就绪");
            return;
        }
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.screenshotFailed(runId, "截图被中断");
            return;
        }
        PreviewService.Opened opened = null;
        try {
            opened = previews.open(runId, null, "system:screenshot", "http://127.0.0.1:" + port);
            String site = opened.url();
            try (CdpBrowser browser = CdpBrowser.launch(options.get(), url -> allowed(url, site));
                    CdpPage page = CdpPage.open(browser, WIDTH, HEIGHT)) {
                page.navigate(site, options.get().timeout());
                // let a single-page application render after its load event
                Thread.sleep(800);
                store.saveScreenshot(runId, page.screenshot());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.screenshotFailed(runId, "截图被中断");
        } catch (Exception e) {
            log.warn("run {}: preview screenshot failed: {}", runId, e.toString());
            store.screenshotFailed(runId, "截图失败：" + e.getMessage());
        } finally {
            if (opened != null) {
                previews.revoke(runId, opened.session().id());
            }
            slots.release();
        }
    }

    /** Captures the screenshot again (for example after the backend address changed); false when not ready. */
    public boolean retake(String runId) {
        if (!store.screenshotPending(runId)) {
            return false;
        }
        wanted(new JdbcPreviewStore.ScreenshotWanted(runId));
        return true;
    }

    /** The session's own site, and nothing but browser-internal pages besides. */
    static boolean allowed(String url, String site) {
        return url.startsWith(site) || url.equals("about:blank") || url.startsWith("data:") || url.startsWith("blob:");
    }
}
