package io.github.loopforge4j.server.browser;

import io.github.loopforge4j.core.runtime.browser.AgentBrowser;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Applies browser requests from the console and from agents to the browser of a workspace. */
@Component
public class BrowserOperations {

    /** Operations whose result is a new page: they settle before the snapshot. */
    private static final Set<String> CHANGES =
            Set.of("navigate", "back", "forward", "reload", "click", "type", "press", "activate", "newTab", "closeTab");

    /**
     * The result: {@code available} false with a reason when there is no browser; {@code error} when
     * the operation failed but the browser is there (its state is still returned).
     */
    public record Outcome(boolean available, String reason, AgentBrowser.State state, String error) {

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", available);
            if (reason != null) {
                out.put("reason", reason);
            }
            if (error != null) {
                out.put("error", error);
            }
            if (state != null) {
                out.put("url", state.url());
                out.put("title", state.title());
                out.put("tabs", state.tabs());
                out.put("canGoBack", state.canGoBack());
                out.put("canGoForward", state.canGoForward());
                out.put("width", AgentBrowser.WIDTH);
                out.put("height", AgentBrowser.HEIGHT);
                if (state.snapshot() != null) {
                    out.put("snapshot", state.snapshot());
                }
                if (state.frame() != null) {
                    out.put("frame", state.frame());
                }
                if (!state.blocked().isEmpty()) {
                    out.put("blocked", state.blocked());
                }
            }
            return out;
        }
    }

    private final AgentBrowsers browsers;

    public BrowserOperations(AgentBrowsers browsers) {
        this.browsers = browsers;
    }

    /**
     * @param key the workspace's browser key
     * @param workspace the workspace (its files may be opened); null when it is not on this instance
     * @param notHere why the workspace's browser cannot run here, if it cannot
     */
    public Outcome apply(String key, Path workspace, String notHere, BrowserRequest request) {
        String operation = request.operation() == null ? "status" : request.operation();
        if ("close".equals(operation)) {
            browsers.close(key);
            return new Outcome(false, "浏览器已关闭", null, null);
        }
        Optional<AgentBrowser> open = browsers.find(key);
        AgentBrowser browser;
        if (open.isPresent()) {
            browser = open.get();
        } else if (notHere != null) {
            return new Outcome(false, notHere, null, null);
        } else if (!request.starts()) {
            return new Outcome(
                    false, browsers.unavailable().orElse("Agent 浏览器尚未启动：Agent 用到时会自动启动，也可以现在手动启动"), null, null);
        } else {
            try {
                browser = browsers.ensure(key, workspace);
            } catch (AgentBrowsers.Unavailable e) {
                return new Outcome(false, e.getMessage(), null, null);
            }
        }
        String error = null;
        AgentBrowser.Snapshot snapshot = null;
        try {
            switch (operation) {
                case "status" -> {
                    // nothing to do
                }
                case "navigate" -> {
                    if (request.url() == null || request.url().isBlank()) {
                        throw new IllegalArgumentException("url 不能为空");
                    }
                    browser.navigate(request.url());
                }
                case "back", "forward", "reload" -> browser.history(operation);
                case "snapshot" -> snapshot = browser.snapshot();
                case "screenshot" -> {
                    // the frame is the screenshot
                }
                case "click" -> browser.click(request.ref());
                case "type" -> {
                    if (request.text() == null) {
                        throw new IllegalArgumentException("text 必须是字符串");
                    }
                    browser.type(request.ref(), request.text(), Boolean.TRUE.equals(request.submit()));
                }
                case "press" -> browser.press(request.key());
                case "newTab" -> browser.newTab();
                case "activate" -> {
                    if (request.targetId() == null || request.targetId().isBlank()) {
                        throw new IllegalArgumentException("需要 targetId");
                    }
                    browser.activate(request.targetId());
                }
                case "closeTab" -> browser.closeTab(request.targetId());
                case "input" -> browser.input(request.events() == null ? List.of() : request.events());
                default -> throw new IllegalArgumentException("不支持的浏览器操作 " + operation);
            }
            if (CHANGES.contains(operation)) {
                browser.settle();
            }
            if (request.snapshot() && snapshot == null) {
                snapshot = browser.snapshot();
            }
        } catch (IOException | IllegalArgumentException e) {
            error = e.getMessage();
        }
        try {
            AgentBrowser.State state = browser.state(request.frame() || "screenshot".equals(operation), snapshot);
            return new Outcome(true, null, state, error);
        } catch (IOException e) {
            browsers.close(key);
            return new Outcome(false, "浏览器连接已断开：" + e.getMessage(), null, error);
        }
    }
}
