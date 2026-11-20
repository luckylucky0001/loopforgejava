package io.github.loopforge4j.core.runtime.browser;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** A real headless Chromium through the DevTools protocol (skipped where none is installed). */
class CdpBrowserTest {

    static CdpBrowser.Options options() {
        Path binary = CdpBrowser.detect().orElse(null);
        Assumptions.assumeTrue(binary != null, "no Chromium or Chrome installed");
        // CI hosts often forbid the user namespaces Chromium's own sandbox needs; the test page is local
        return new CdpBrowser.Options(binary, Duration.ofSeconds(30), true);
    }

    @Test
    void opensPagesRunsScriptsTakesScreenshotsAndBlocksWhatIsNotAllowed() throws Exception {
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        // another site of the same server: a cross-site frame, a popup and a worker try to reach it
        String other = "http://localhost:" + port;
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/")) {
                hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] body = ("<html><head><title>站点</title></head><body><h1>你好</h1>"
                            + "<img src=\"/blocked.png\">"
                            + "<iframe src=\"" + other + "/frame\"></iframe>"
                            + "<script>new Worker(URL.createObjectURL(new Blob([\"fetch('" + other
                            + "/worker')\"])))</script>"
                            + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + port;
        try (CdpBrowser browser = CdpBrowser.launch(
                        options(),
                        url -> url.startsWith("about:") || url.startsWith("blob:") || url.equals(base + "/"));
                CdpPage page = CdpPage.open(browser, 800, 600)) {
            page.navigate(base + "/", Duration.ofSeconds(15));
            assertThat(page.evaluate("document.title").asString()).isEqualTo("站点");
            assertThat(page.evaluate("document.querySelector('h1').textContent").asString())
                    .isEqualTo("你好");
            assertThat(page.evaluate("Promise.resolve(40 + 2)").asInt()).isEqualTo(42);
            // a popup opened with a user gesture is a new tab: it is guarded too
            browser.send(
                    "Runtime.evaluate",
                    Map.of("expression", "window.open('" + other + "/popup')", "userGesture", true),
                    page.sessionId());
            byte[] png = page.screenshot();
            assertThat(Arrays.copyOf(png, 4)).isEqualTo(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
            Thread.sleep(1500);
            assertThat(page.blocked()).contains(base + "/blocked.png", other + "/frame");
            assertThat(hits).as("requests that reached the server").isEmpty();
            assertThat(browser.blocked()).anyMatch(url -> url.endsWith("/popup"));
            assertThat(List.copyOf(browser.blocked())).anyMatch(url -> url.endsWith("/worker"));
        } finally {
            server.stop(0);
        }
    }
}
