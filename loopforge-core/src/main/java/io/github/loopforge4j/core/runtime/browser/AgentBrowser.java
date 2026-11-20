package io.github.loopforge4j.core.runtime.browser;

import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * The agent browser (upstream {@code browserView}): one headless Chromium per run or conversation
 * workspace that agents drive by element refs from a page snapshot and that people watch and take
 * over from the console with mouse and keyboard events. Every request it makes passes the policy
 * it was launched with.
 */
public final class AgentBrowser implements AutoCloseable {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 800;
    static final int SNAPSHOT_ELEMENTS = 150;
    static final int SNAPSHOT_TEXT = 12_000;
    static final String REF_ATTR = "data-loopforge-ref";

    /** An open tab. */
    public record Tab(String id, String title, String url, boolean active) {}

    /** An element an agent can act on, by its ref. */
    public record Element(String ref, String role, String name, String value, Boolean checked, Boolean disabled) {}

    /** What an agent reads of the page: its visible text and interactive elements. */
    public record Snapshot(String text, boolean truncated, List<Element> elements) {}

    /** Where the browser is: the active tab, all tabs, and (when asked for) a frame and snapshot. */
    public record State(
            String url,
            String title,
            List<Tab> tabs,
            boolean canGoBack,
            boolean canGoForward,
            Snapshot snapshot,
            String frame,
            List<String> blocked) {}

    /** One input event from the console, with coordinates as fractions of the viewport. */
    public record Input(
            String type,
            String action,
            double x,
            double y,
            String button,
            int buttons,
            int clickCount,
            double deltaX,
            double deltaY,
            String key,
            String code,
            int keyCode,
            String text,
            int modifiers) {}

    private static final String SNAPSHOT_SCRIPT = """
            (() => {
              const attr = "%s", max = %d, limit = %d;
              document.querySelectorAll("[" + attr + "]").forEach(e => e.removeAttribute(attr));
              const visible = e => { const r = e.getBoundingClientRect(); if (r.width < 1 || r.height < 1) return false; const s = getComputedStyle(e); return s.visibility !== "hidden" && s.display !== "none"; };
              const clean = v => String(v || "").replace(/\\s+/g, " ").trim().slice(0, 80);
              const selector = 'a[href],button,input:not([type=hidden]),textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=option],[role=combobox],[role=textbox],[role=switch],[contenteditable=""],[contenteditable=true],[onclick]';
              const elements = [];
              let n = 0;
              for (const e of document.querySelectorAll(selector)) {
                if (elements.length >= max) break;
                if (!visible(e)) continue;
                const ref = "e" + (++n);
                e.setAttribute(attr, ref);
                const tag = e.tagName.toLowerCase(), type = (e.getAttribute("type") || "").toLowerCase();
                const role = e.getAttribute("role") || (tag === "a" ? "link" : tag === "input" ? (type || "text") : tag);
                const buttonValue = tag === "input" && ["button", "submit", "reset"].includes(type) ? e.value : "";
                const item = { ref, role, name: clean(e.getAttribute("aria-label") || e.innerText || buttonValue || e.getAttribute("placeholder") || e.title || e.getAttribute("alt") || e.getAttribute("name")) };
                if ((tag === "input" && !["button", "submit", "reset", "password", "checkbox", "radio"].includes(type)) || tag === "textarea" || tag === "select") item.value = clean(e.value);
                if (e.checked) item.checked = true;
                if (e.disabled) item.disabled = true;
                elements.push(item);
              }
              const text = String(document.body ? document.body.innerText : "").replace(/\\n{3,}/g, "\\n\\n").trim();
              return { text: text.slice(0, limit), truncated: text.length > limit, elements };
            })()""".formatted(REF_ATTR, SNAPSHOT_ELEMENTS, SNAPSHOT_TEXT);

    private record Key(String code, int keyCode, String text) {}

    private static final Map<String, Key> KEYS = Map.ofEntries(
            Map.entry("Enter", new Key("Enter", 13, "\r")),
            Map.entry("Tab", new Key("Tab", 9, "")),
            Map.entry("Escape", new Key("Escape", 27, "")),
            Map.entry("Backspace", new Key("Backspace", 8, "")),
            Map.entry("Delete", new Key("Delete", 46, "")),
            Map.entry("ArrowUp", new Key("ArrowUp", 38, "")),
            Map.entry("ArrowDown", new Key("ArrowDown", 40, "")),
            Map.entry("ArrowLeft", new Key("ArrowLeft", 37, "")),
            Map.entry("ArrowRight", new Key("ArrowRight", 39, "")),
            Map.entry("PageUp", new Key("PageUp", 33, "")),
            Map.entry("PageDown", new Key("PageDown", 34, "")),
            Map.entry("Home", new Key("Home", 36, "")),
            Map.entry("End", new Key("End", 35, "")),
            Map.entry(" ", new Key("Space", 32, " ")));

    private static final Map<String, String> MOUSE =
            Map.of("down", "mousePressed", "up", "mouseReleased", "move", "mouseMoved");

    private final CdpBrowser browser;
    private final Clock clock;
    private final Map<String, CdpPage> pages = new LinkedHashMap<>();
    private String active;
    private volatile Instant lastUsed;

    private AgentBrowser(CdpBrowser browser, Clock clock) {
        this.browser = browser;
        this.clock = clock;
        this.lastUsed = clock.instant();
    }

    /** Starts a browser with one blank tab; {@code allowed} decides every request it makes. */
    public static AgentBrowser launch(CdpBrowser.Options options, Predicate<String> allowed, Clock clock)
            throws IOException {
        CdpBrowser browser = CdpBrowser.launch(options, allowed);
        AgentBrowser agent = new AgentBrowser(browser, clock);
        try {
            // the tab Chromium opened itself predates the guard: replace it
            List<String> initial = agent.pageTargets().stream().map(Tab::id).toList();
            agent.newTab();
            for (String id : initial) {
                browser.send("Target.closeTarget", Map.of("targetId", id), null);
            }
            return agent;
        } catch (IOException | RuntimeException e) {
            browser.close();
            throw e;
        }
    }

    public Instant lastUsed() {
        return lastUsed;
    }

    private void touch() {
        lastUsed = clock.instant();
    }

    // ------------------------------------------------------------------ tabs

    private List<Tab> pageTargets() throws IOException {
        List<Tab> out = new ArrayList<>();
        for (JsonNode info : browser.send("Target.getTargets", Map.of(), null).path("targetInfos")) {
            if ("page".equals(info.path("type").asString(""))) {
                String id = info.path("targetId").asString("");
                out.add(new Tab(
                        id, info.path("title").asString(""), info.path("url").asString(""), id.equals(active)));
            }
        }
        return out;
    }

    /** The open tabs, oldest first; a tab a page opened becomes the active one. */
    public synchronized List<Tab> tabs() throws IOException {
        touch();
        List<Tab> tabs = pageTargets();
        Set<String> live = new java.util.HashSet<>();
        tabs.forEach(t -> live.add(t.id()));
        pages.keySet().removeIf(id -> !live.contains(id));
        if (active != null && !live.contains(active)) {
            active = null;
        }
        // follow a popup the page opened since the last look
        String newest = null;
        for (Tab tab : tabs) {
            if (!pages.containsKey(tab.id()) && !tab.url().isEmpty()) {
                newest = tab.id();
            }
        }
        if (newest != null) {
            activate(newest);
        } else if (active == null && !tabs.isEmpty()) {
            activate(tabs.getLast().id());
        }
        return pageTargets();
    }

    public synchronized void newTab() throws IOException {
        touch();
        CdpPage page = CdpPage.open(browser, WIDTH, HEIGHT);
        pages.put(page.targetId(), page);
        active = page.targetId();
    }

    public synchronized void activate(String targetId) throws IOException {
        touch();
        if (!pages.containsKey(targetId)) {
            if (pageTargets().stream().noneMatch(t -> t.id().equals(targetId))) {
                throw new IOException("没有这个标签页：" + targetId);
            }
            pages.put(targetId, CdpPage.attach(browser, targetId, WIDTH, HEIGHT));
        }
        active = targetId;
        browser.send("Target.activateTarget", Map.of("targetId", targetId), null);
    }

    public synchronized void closeTab(String targetId) throws IOException {
        touch();
        String id = targetId == null || targetId.isBlank() ? active : targetId;
        if (id == null) {
            return;
        }
        CdpPage page = pages.remove(id);
        if (page != null) {
            page.close();
        } else {
            browser.send("Target.closeTarget", Map.of("targetId", id), null);
        }
        if (id.equals(active)) {
            active = null;
        }
        if (pageTargets().isEmpty()) {
            newTab();
        } else {
            tabs();
        }
    }

    private CdpPage page() throws IOException {
        if (active == null || !pages.containsKey(active)) {
            tabs();
        }
        CdpPage page = active == null ? null : pages.get(active);
        if (page == null) {
            throw new IOException("没有可操作的标签页");
        }
        return page;
    }

    // ------------------------------------------------------------------ navigation

    /** What an address bar does with {@code input}: a URL as given, a host, or a web search. */
    public static String address(String input) {
        String target = input == null ? "" : input.strip();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("请输入网址");
        }
        if (target.contains("://") || target.startsWith("about:") || target.startsWith("data:")) {
            return target;
        }
        boolean local = target.startsWith("localhost") || target.startsWith("127.0.0.1");
        if (target.contains(" ") || !target.contains(".") && !local) {
            return "https://www.bing.com/search?q=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
        }
        return (local ? "http://" : "https://") + target;
    }

    public synchronized void navigate(String input) throws IOException {
        touch();
        CdpPage page = page();
        page.navigate(address(input), browser.timeout());
    }

    /** {@code back}, {@code forward} or {@code reload}. */
    public synchronized void history(String action) throws IOException {
        touch();
        CdpPage page = page();
        if ("reload".equals(action)) {
            browser.send("Page.reload", Map.of(), page.sessionId());
            return;
        }
        JsonNode history = browser.send("Page.getNavigationHistory", Map.of(), page.sessionId());
        int index = history.path("currentIndex").asInt() + ("back".equals(action) ? -1 : 1);
        JsonNode entries = history.path("entries");
        if (index < 0 || index >= entries.size()) {
            throw new IOException("back".equals(action) ? "没有可以后退的页面" : "没有可以前进的页面");
        }
        browser.send(
                "Page.navigateToHistoryEntry",
                Map.of("entryId", entries.get(index).path("id").asInt()),
                page.sessionId());
    }

    /** Waits briefly for what an action started to finish loading. */
    public synchronized void settle() {
        try {
            Thread.sleep(400);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                try {
                    if ("complete".equals(page().evaluate("document.readyState").asString(""))) {
                        return;
                    }
                } catch (IOException e) {
                    // navigating: the context is being replaced
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ agent operations

    public synchronized Snapshot snapshot() throws IOException {
        touch();
        JsonNode value = page().evaluate(SNAPSHOT_SCRIPT);
        List<Element> elements = new ArrayList<>();
        for (JsonNode e : value.path("elements")) {
            elements.add(new Element(
                    e.path("ref").asString(""),
                    e.path("role").asString(""),
                    e.path("name").asString(""),
                    e.has("value") ? e.path("value").asString("") : null,
                    e.path("checked").asBoolean(false) ? Boolean.TRUE : null,
                    e.path("disabled").asBoolean(false) ? Boolean.TRUE : null));
        }
        return new Snapshot(
                value.path("text").asString(""), value.path("truncated").asBoolean(false), elements);
    }

    static String refSelector(String ref) {
        String r = ref == null ? "" : ref.strip();
        if (!r.matches("e[0-9]{1,6}")) {
            throw new IllegalArgumentException("ref 无效：请先获取页面快照，使用其中的元素 ref（如 e12）");
        }
        return "document.querySelector('[" + REF_ATTR + "=\"" + r + "\"]')";
    }

    /** Clicks the element's centre with real mouse events, as a click from the console does. */
    public synchronized void click(String ref) throws IOException {
        touch();
        String selector = refSelector(ref);
        CdpPage page = page();
        JsonNode point = page.evaluate(
                "(() => { const e = " + selector
                        + "; if (!e) return null; e.scrollIntoView({block: 'center', inline: 'center'});"
                        + " const r = e.getBoundingClientRect(); return {x: r.left + r.width / 2, y: r.top + r.height / 2}; })()");
        if (point == null || point.isNull() || point.isMissingNode()) {
            throw new IOException("找不到元素 " + ref + "：页面可能已变化，请重新获取快照");
        }
        double x = point.path("x").asDouble();
        double y = point.path("y").asDouble();
        mouse(page, "mouseMoved", x, y, "none", 0, 0);
        mouse(page, "mousePressed", x, y, "left", 1, 1);
        mouse(page, "mouseReleased", x, y, "left", 0, 1);
    }

    private void mouse(CdpPage page, String type, double x, double y, String button, int buttons, int clicks)
            throws IOException {
        browser.send(
                "Input.dispatchMouseEvent",
                Map.of("type", type, "x", x, "y", y, "button", button, "buttons", buttons, "clickCount", clicks),
                page.sessionId());
    }

    /** Replaces the element's content with {@code text}; {@code submit} presses Enter after. */
    public synchronized void type(String ref, String text, boolean submit) throws IOException {
        click(ref);
        CdpPage page = page();
        JsonNode found = page.evaluate("(() => { const e = " + refSelector(ref)
                + "; if (!e) return false; e.focus(); if (typeof e.select === 'function') e.select();"
                + " else { const r = document.createRange(); r.selectNodeContents(e); const s = getSelection();"
                + " s.removeAllRanges(); s.addRange(r); } return true; })()");
        if (!found.asBoolean(false)) {
            throw new IOException("找不到元素 " + ref + "：页面可能已变化，请重新获取快照");
        }
        if (text == null || text.isEmpty()) {
            press("Backspace");
        } else {
            browser.send("Input.insertText", Map.of("text", text), page.sessionId());
        }
        if (submit) {
            press("Enter");
        }
    }

    public synchronized void press(String name) throws IOException {
        touch();
        String key = "space".equalsIgnoreCase(name) ? " " : name;
        Key spec = KEYS.get(key);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的按键 " + name);
        }
        CdpPage page = page();
        Map<String, Object> down = new LinkedHashMap<>(Map.of(
                "type", "rawKeyDown",
                "key", key,
                "code", spec.code(),
                "windowsVirtualKeyCode", spec.keyCode(),
                "nativeVirtualKeyCode", spec.keyCode()));
        if (!spec.text().isEmpty()) {
            down.put("type", "keyDown");
            down.put("text", spec.text());
            down.put("unmodifiedText", spec.text());
        }
        browser.send("Input.dispatchKeyEvent", down, page.sessionId());
        browser.send(
                "Input.dispatchKeyEvent",
                Map.of(
                        "type", "keyUp",
                        "key", key,
                        "code", spec.code(),
                        "windowsVirtualKeyCode", spec.keyCode(),
                        "nativeVirtualKeyCode", spec.keyCode()),
                page.sessionId());
    }

    /** The visible part of the active tab as JPEG. */
    public synchronized byte[] screenshot() throws IOException {
        touch();
        String data = browser.send(
                        "Page.captureScreenshot", Map.of("format", "jpeg", "quality", 70), page().sessionId())
                .path("data")
                .asString("");
        return Base64.getDecoder().decode(data);
    }

    // ------------------------------------------------------------------ the console

    /** Mouse, wheel, key and text events from the console (at most 200 at a time). */
    public synchronized void input(List<Input> events) throws IOException {
        touch();
        CdpPage page = page();
        int n = 0;
        for (Input ev : events) {
            if (++n > 200) {
                break;
            }
            double x = clamp(ev.x()) * WIDTH;
            double y = clamp(ev.y()) * HEIGHT;
            switch (ev.type() == null ? "" : ev.type()) {
                case "mouse" -> {
                    String action = MOUSE.get(ev.action());
                    if (action != null) {
                        browser.send(
                                "Input.dispatchMouseEvent",
                                Map.of(
                                        "type",
                                        action,
                                        "x",
                                        x,
                                        "y",
                                        y,
                                        "button",
                                        ev.button() == null ? "none" : ev.button(),
                                        "buttons",
                                        ev.buttons(),
                                        "clickCount",
                                        ev.clickCount(),
                                        "modifiers",
                                        ev.modifiers()),
                                page.sessionId());
                    }
                }
                case "wheel" ->
                    browser.send(
                            "Input.dispatchMouseEvent",
                            Map.of(
                                    "type",
                                    "mouseWheel",
                                    "x",
                                    x,
                                    "y",
                                    y,
                                    "deltaX",
                                    ev.deltaX(),
                                    "deltaY",
                                    ev.deltaY(),
                                    "modifiers",
                                    ev.modifiers()),
                            page.sessionId());
                case "key" -> {
                    if (ev.key() == null || ev.key().length() > 32) {
                        continue;
                    }
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("key", ev.key());
                    params.put("code", ev.code() == null ? "" : ev.code());
                    params.put("windowsVirtualKeyCode", ev.keyCode());
                    params.put("nativeVirtualKeyCode", ev.keyCode());
                    params.put("modifiers", ev.modifiers());
                    if ("up".equals(ev.action())) {
                        params.put("type", "keyUp");
                    } else if (ev.text() != null
                            && !ev.text().isEmpty()
                            && ev.text().length() <= 8) {
                        params.put("type", "keyDown");
                        params.put("text", ev.text());
                        params.put("unmodifiedText", ev.text());
                    } else {
                        params.put("type", "rawKeyDown");
                    }
                    browser.send("Input.dispatchKeyEvent", params, page.sessionId());
                }
                case "text" -> {
                    if (ev.text() != null && !ev.text().isEmpty()) {
                        String text = ev.text().length() > 10_000 ? ev.text().substring(0, 10_000) : ev.text();
                        browser.send("Input.insertText", Map.of("text", text), page.sessionId());
                    }
                }
                default -> {
                    // unknown events are ignored
                }
            }
        }
    }

    private static double clamp(double v) {
        return Double.isNaN(v) ? 0 : Math.max(0, Math.min(1, v));
    }

    /** The active tab, every tab and the history flags; a frame and a snapshot when asked for. */
    public synchronized State state(boolean frame, Snapshot snapshot) throws IOException {
        List<Tab> tabs = tabs();
        Tab current = tabs.stream().filter(Tab::active).findFirst().orElse(null);
        boolean back = false;
        boolean forward = false;
        try {
            JsonNode history = browser.send("Page.getNavigationHistory", Map.of(), page().sessionId());
            int index = history.path("currentIndex").asInt();
            back = index > 0;
            forward = index < history.path("entries").size() - 1;
        } catch (IOException e) {
            // no history yet
        }
        String image = frame ? Base64.getEncoder().encodeToString(screenshot()) : null;
        List<String> blocked = browser.blocked();
        return new State(
                current == null ? "" : current.url(),
                current == null ? "" : current.title(),
                tabs,
                back,
                forward,
                snapshot,
                image,
                blocked.size() > 10 ? blocked.subList(blocked.size() - 10, blocked.size()) : blocked);
    }

    /** The page as text for a language model: tabs, interactive elements by ref, visible text. */
    public static String describe(State state, String error) {
        StringBuilder b = new StringBuilder();
        if (error != null && !error.isBlank()) {
            b.append("操作失败：").append(error).append("\n\n");
        }
        b.append("URL: ")
                .append(state.url())
                .append("\n标题: ")
                .append(state.title())
                .append('\n');
        if (state.tabs().size() > 1) {
            b.append("\n标签页：\n");
            for (Tab tab : state.tabs()) {
                b.append(tab.active() ? "* " : "  ")
                        .append(tab.id())
                        .append(' ')
                        .append(Json.write(tab.title()))
                        .append(' ')
                        .append(tab.url())
                        .append('\n');
            }
        }
        Snapshot snap = state.snapshot();
        if (snap != null) {
            if (!snap.elements().isEmpty()) {
                b.append("\n可交互元素：\n");
                for (Element e : snap.elements()) {
                    b.append("- [").append(e.ref()).append("] ").append(e.role());
                    if (!e.name().isEmpty()) {
                        b.append(' ').append(Json.write(e.name()));
                    }
                    if (e.value() != null && !e.value().isEmpty()) {
                        b.append(" 值=").append(Json.write(e.value()));
                    }
                    if (Boolean.TRUE.equals(e.checked())) {
                        b.append(" 已选中");
                    }
                    if (Boolean.TRUE.equals(e.disabled())) {
                        b.append(" 已禁用");
                    }
                    b.append('\n');
                }
            }
            if (!snap.text().isEmpty()) {
                b.append("\n页面文本：\n").append(snap.text());
                if (snap.truncated()) {
                    b.append("\n（文本已截断）");
                }
                b.append('\n');
            }
        }
        if (!state.blocked().isEmpty()) {
            b.append("\n被拦截的请求：").append(String.join("、", state.blocked())).append('\n');
        }
        return b.toString();
    }

    @Override
    public synchronized void close() {
        pages.clear();
        browser.close();
    }

    public boolean alive() {
        return browser.alive();
    }
}
