package io.github.loopforge4j.core.runtime.browser;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** One tab of a {@link CdpBrowser}, under the browser's request policy. */
public final class CdpPage implements AutoCloseable {

    private final CdpBrowser browser;
    private final String targetId;
    private final String sessionId;

    private CdpPage(CdpBrowser browser, String targetId, String sessionId) {
        this.browser = browser;
        this.targetId = targetId;
        this.sessionId = sessionId;
    }

    /** Opens a blank tab of {@code width}×{@code height}. */
    public static CdpPage open(CdpBrowser browser, int width, int height) throws IOException {
        String targetId = browser.send("Target.createTarget", Map.of("url", "about:blank"), null)
                .path("targetId")
                .asString();
        return attach(browser, targetId, width, height);
    }

    /** Takes over an existing tab (for example a popup) of {@code width}×{@code height}. */
    public static CdpPage attach(CdpBrowser browser, String targetId, int width, int height) throws IOException {
        CdpPage page = new CdpPage(browser, targetId, browser.session(targetId));
        try {
            browser.send("Page.enable", Map.of(), page.sessionId);
            browser.send(
                    "Emulation.setDeviceMetricsOverride",
                    Map.of("width", width, "height", height, "deviceScaleFactor", 1, "mobile", false),
                    page.sessionId);
            return page;
        } catch (IOException e) {
            page.close();
            throw e;
        }
    }

    /** Opens {@code url} and waits for its load event (at most {@code wait}). */
    public void navigate(String url, Duration wait) throws IOException {
        CountDownLatch loaded = new CountDownLatch(1);
        Consumer<JsonNode> onLoad = event -> {
            if ("Page.loadEventFired".equals(event.path("method").asString(""))
                    && sessionId.equals(event.path("sessionId").asString(""))) {
                loaded.countDown();
            }
        };
        browser.listen(onLoad);
        try {
            JsonNode result = browser.send("Page.navigate", Map.of("url", url), sessionId);
            String error = result.path("errorText").asString("");
            if (!error.isBlank()) {
                throw new IOException("cannot open " + url + ": " + error);
            }
            loaded.await(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            browser.unlisten(onLoad);
        }
    }

    /** The value of a JavaScript expression (promises are awaited). */
    public JsonNode evaluate(String expression) throws IOException {
        JsonNode result = browser.send(
                "Runtime.evaluate",
                Map.of("expression", expression, "returnByValue", true, "awaitPromise", true),
                sessionId);
        if (result.has("exceptionDetails")) {
            throw new IOException("script failed: "
                    + result.path("exceptionDetails")
                            .path("exception")
                            .path("description")
                            .asString("error"));
        }
        return result.path("result").path("value");
    }

    /** The visible part of the page as PNG. */
    public byte[] screenshot() throws IOException {
        String data = browser.send("Page.captureScreenshot", Map.of("format", "png"), sessionId)
                .path("data")
                .asString("");
        return Base64.getDecoder().decode(data);
    }

    /** Requests the browser's policy refused. */
    public List<String> blocked() {
        return browser.blocked();
    }

    public String sessionId() {
        return sessionId;
    }

    public String targetId() {
        return targetId;
    }

    @Override
    public void close() {
        try {
            browser.send("Target.closeTarget", Map.of("targetId", targetId), null);
        } catch (IOException e) {
            // already gone with the browser
        }
    }
}
