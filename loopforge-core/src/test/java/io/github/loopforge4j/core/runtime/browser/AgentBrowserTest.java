package io.github.loopforge4j.core.runtime.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The agent browser against a real headless Chromium (skipped where none is installed). */
class AgentBrowserTest {

    static final String FORM = """
            <html><head><title>登录</title></head><body>
            <h1>欢迎</h1>
            <input id="name" placeholder="用户名">
            <button onclick="document.querySelector('h1').textContent = '你好 ' + document.getElementById('name').value">进入</button>
            <a href="/next">下一页</a>
            <a href="/next" target="_blank">新窗口</a>
            </body></html>""";

    @Test
    void agentsReadPagesActByRefAndManageTabsAndHistory() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body = (path.equals("/next") ? "<html><head><title>第二页</title></head><body>到了</body></html>" : FORM)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentBrowser browser = AgentBrowser.launch(CdpBrowserTest.options(), url -> true, Clock.systemUTC())) {
            assertThat(browser.tabs())
                    .singleElement()
                    .satisfies(t -> assertThat(t.active()).isTrue());

            browser.navigate(base + "/");
            AgentBrowser.Snapshot snap = browser.snapshot();
            assertThat(snap.text()).contains("欢迎");
            assertThat(snap.elements())
                    .extracting(AgentBrowser.Element::role, AgentBrowser.Element::name)
                    .contains(
                            org.assertj.core.groups.Tuple.tuple("text", "用户名"),
                            org.assertj.core.groups.Tuple.tuple("button", "进入"),
                            org.assertj.core.groups.Tuple.tuple("link", "下一页"));
            String input = ref(snap, "用户名");
            String button = ref(snap, "进入");

            browser.type(input, "小明", false);
            browser.click(button);
            assertThat(browser.snapshot().text()).contains("你好 小明");
            assertThat(browser.snapshot().elements())
                    .filteredOn(e -> e.name().equals("用户名"))
                    .singleElement()
                    .satisfies(e -> assertThat(e.value()).isEqualTo("小明"));

            // a link followed by a click, then back and forward
            browser.click(ref(browser.snapshot(), "下一页"));
            browser.settle();
            assertThat(browser.state(false, null).title()).isEqualTo("第二页");
            assertThat(browser.state(false, null).canGoBack()).isTrue();
            browser.history("back");
            browser.settle();
            assertThat(browser.state(false, null).title()).isEqualTo("登录");
            browser.history("forward");
            browser.settle();
            assertThat(browser.state(false, null).title()).isEqualTo("第二页");
            browser.history("back");
            browser.settle();

            // a link opening a new window: the new tab becomes the active one
            browser.click(ref(browser.snapshot(), "新窗口"));
            browser.settle();
            List<AgentBrowser.Tab> tabs = browser.tabs();
            assertThat(tabs).hasSize(2);
            assertThat(tabs)
                    .filteredOn(AgentBrowser.Tab::active)
                    .singleElement()
                    .satisfies(t -> assertThat(t.url()).endsWith("/next"));
            browser.closeTab(null);
            assertThat(browser.tabs())
                    .singleElement()
                    .satisfies(t -> assertThat(t.title()).isEqualTo("登录"));

            // the console: a click at a point and typed text
            browser.press("Tab");
            byte[] jpeg = browser.screenshot();
            assertThat(Arrays.copyOf(jpeg, 2)).isEqualTo(new byte[] {(byte) 0xff, (byte) 0xd8});
            AgentBrowser.State state = browser.state(true, browser.snapshot());
            assertThat(state.frame()).isNotBlank();
            assertThat(AgentBrowser.describe(state, null))
                    .contains("URL: " + base + "/")
                    .contains("[" + button + "] button \"进入\"")
                    .contains("页面文本");

            assertThatThrownBy(() -> browser.click("e999")).hasMessageContaining("找不到元素");
            assertThatThrownBy(() -> browser.click("body")).hasMessageContaining("ref 无效");
            assertThatThrownBy(() -> browser.press("F13")).hasMessageContaining("不支持的按键");
        } finally {
            server.stop(0);
        }
    }

    static String ref(AgentBrowser.Snapshot snap, String name) {
        return snap.elements().stream()
                .filter(e -> e.name().equals(name))
                .findFirst()
                .orElseThrow()
                .ref();
    }

    @Test
    void theAddressBarCompletesHostsAndSearchesWords() {
        assertThat(AgentBrowser.address("example.com")).isEqualTo("https://example.com");
        assertThat(AgentBrowser.address("localhost:3000/a")).isEqualTo("http://localhost:3000/a");
        assertThat(AgentBrowser.address("http://x.test/")).isEqualTo("http://x.test/");
        assertThat(AgentBrowser.address("如何 部署")).startsWith("https://www.bing.com/search?q=");
        assertThatThrownBy(() -> AgentBrowser.address(" ")).hasMessageContaining("请输入网址");
    }
}
