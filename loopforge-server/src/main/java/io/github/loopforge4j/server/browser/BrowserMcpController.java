package io.github.loopforge4j.server.browser;

import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.browser.AgentBrowser;
import io.github.loopforge4j.server.mcp.McpRpc;
import io.github.loopforge4j.server.mcp.RunTokens;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The agent browser as an MCP server (upstream {@code agentflow-browser} and the conversation's
 * {@code workbench_browser_*} tools): stateless Streamable HTTP, authenticated by a browser token
 * that names the run or conversation turn, so an agent only ever drives its own workspace's browser.
 */
@RestController
public class BrowserMcpController {

    public static final String PATH = "/api/v1/browser/mcp";
    public static final String SERVER_ID = io.github.loopforge4j.core.mcp.PlatformMcp.BROWSER_ID;

    private static final String NO_ARGS = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    /** name, description, input schema */
    private static final List<String[]> TOOLS = List.of(
            new String[] {
                "browser_navigate",
                "在 Agent 浏览器里打开网址（不是网址时按关键词搜索），或执行 back / forward / reload。浏览器未启动时自动启动；"
                        + "localhost 上的开发服务器与工作区文件（file://）都能打开。返回加载后的页面快照。",
                "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"},\"action\":{\"type\":\"string\","
                        + "\"enum\":[\"back\",\"forward\",\"reload\"]}},\"additionalProperties\":false}"
            },
            new String[] {"browser_snapshot", "读取当前页面：URL、标题、标签页、可见文本，以及带 ref（如 e12）的可交互元素。点击、输入前先获取快照。", NO_ARGS},
            new String[] {
                "browser_click",
                "用真实鼠标点击快照中的元素。返回点击后的新快照（ref 会刷新）。",
                "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\"}},\"required\":[\"ref\"],"
                        + "\"additionalProperties\":false}"
            },
            new String[] {
                "browser_type",
                "清空并在快照中的输入元素里输入文本；submit=true 时随后按回车。返回新快照。",
                "{\"type\":\"object\",\"properties\":{\"ref\":{\"type\":\"string\"},\"text\":{\"type\":\"string\"},"
                        + "\"submit\":{\"type\":\"boolean\"}},\"required\":[\"ref\",\"text\"],\"additionalProperties\":false}"
            },
            new String[] {
                "browser_press",
                "在当前焦点按键：Enter、Tab、Escape、Backspace、Delete、Space、ArrowUp/Down/Left/Right、PageUp、PageDown、Home、End。返回新快照。",
                "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}},\"required\":[\"key\"],"
                        + "\"additionalProperties\":false}"
            },
            new String[] {
                "browser_screenshot", "截取当前标签页画面（图像），用于确认布局、样式或快照无法表达的内容。用户也能在控制台的「浏览器」面板实时看到并操作这个浏览器。", NO_ARGS
            },
            new String[] {
                "browser_tabs",
                "管理标签页：list 列出，switch 切换到 tabId，new 新建，close 关闭 tabId（缺省当前页）。",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"list\",\"switch\",\"new\","
                        + "\"close\"]},\"tabId\":{\"type\":\"string\"}},\"required\":[\"action\"],\"additionalProperties\":false}"
            });

    /** How agents are told to use the browser. */
    public static RuleDefinition usageRule() {
        return new RuleDefinition(
                "loopforge-browser-usage", "Agent 浏览器", "需要打开或查看网页时用 browser_* 工具", """
                # Agent 浏览器

                你有一个真实的 Chromium（MCP：%s），用户能在控制台的「浏览器」面板实时看到并接管。
                - 打开或检查网页（包括你在工作区里启动的 http://localhost:端口 开发服务器和工作区里的 HTML 文件）时，
                  用 browser_navigate 打开，用 browser_snapshot 读取文本和带 ref 的元素，再用 browser_click / browser_type / browser_press 操作。
                - 需要确认布局或样式时用 browser_screenshot。不要用 curl 抓取页面代替浏览器，也不要声称没有浏览器。
                - 页面被用户改动时以最新快照为准；ref 在每次快照后刷新。
                """.formatted(SERVER_ID), 40, null);
    }

    private final RunTokens tokens;
    private final ObjectProvider<RunEngine> engines;
    private final BrowserWorkspaces places;
    private final BrowserOperations operations;

    public BrowserMcpController(
            RunTokens tokens,
            ObjectProvider<RunEngine> engines,
            BrowserWorkspaces places,
            BrowserOperations operations) {
        this.tokens = tokens;
        this.engines = engines;
        this.places = places;
        this.operations = operations;
    }

    @PostMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Object> post(@RequestBody String raw, HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7) : null;
        Optional<RunTokens.Claims> claims = tokens.verify(token, RunTokens.Kind.BROWSER);
        if (claims.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer realm=\"LoopForge Browser\"")
                    .body(Map.of("error", "invalid browser token"));
        }
        JsonNode message;
        try {
            message = Json.mapper().readTree(raw);
        } catch (JacksonException e) {
            return ResponseEntity.badRequest().body(McpRpc.error(null, McpRpc.PARSE_ERROR, "parse error"));
        }
        if (message == null || !message.isObject()) {
            return ResponseEntity.badRequest()
                    .body(McpRpc.error(null, McpRpc.INVALID_REQUEST, "one JSON-RPC request object per POST"));
        }
        String method = message.path("method").asString("");
        if (method.startsWith("notifications/")) {
            return ResponseEntity.accepted().build();
        }
        Object id = McpRpc.id(message);
        JsonNode params = message.get("params");
        Object response = switch (method) {
            case "initialize" ->
                McpRpc.result(
                        id,
                        Map.of(
                                "protocolVersion", McpRpc.protocolVersion(params),
                                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                                "serverInfo", Map.of("name", SERVER_ID, "version", "1.0.0"),
                                "instructions",
                                        "Agent 浏览器：browser_navigate 打开页面，browser_snapshot 读取带 ref 的元素，再点击或输入。"));
            case "ping" -> McpRpc.result(id, Map.of());
            case "tools/list" -> McpRpc.result(id, Map.of("tools", tools()));
            case "tools/call" -> call(id, params, claims.get());
            default -> McpRpc.error(id, McpRpc.METHOD_NOT_FOUND, "method not found");
        };
        return ResponseEntity.ok()
                .header("MCP-Protocol-Version", McpRpc.protocolVersion(params))
                .body(response);
    }

    static List<Map<String, Object>> tools() {
        return TOOLS.stream()
                .map(t -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("name", t[0]);
                    tool.put("description", t[1]);
                    tool.put("inputSchema", Json.read(t[2], Object.class));
                    return tool;
                })
                .toList();
    }

    /** The request a tool call stands for (upstream {@code mainBotBrowserRequest}). */
    static BrowserRequest request(String tool, JsonNode args) {
        String url = text(args, "url");
        return switch (tool) {
            case "browser_navigate" -> {
                String action = text(args, "action");
                if (!action.isEmpty()) {
                    if (!List.of("back", "forward", "reload").contains(action)) {
                        throw new IllegalArgumentException("不支持的 action " + action);
                    }
                    yield BrowserRequest.of(action).snapshotting();
                }
                if (url.isEmpty()) {
                    throw new IllegalArgumentException("url 或 action 必须提供一个");
                }
                yield BrowserRequest.of("navigate").withUrl(url).snapshotting();
            }
            case "browser_snapshot" -> BrowserRequest.of("snapshot");
            case "browser_screenshot" -> BrowserRequest.of("screenshot");
            case "browser_click" ->
                BrowserRequest.of("click").withRef(text(args, "ref")).snapshotting();
            case "browser_type" -> {
                JsonNode value = args == null ? null : args.get("text");
                if (value == null || !value.isString()) {
                    throw new IllegalArgumentException("text 必须是字符串");
                }
                yield BrowserRequest.of("type")
                        .withRef(text(args, "ref"))
                        .withText(value.asString(), args.path("submit").asBoolean(false))
                        .snapshotting();
            }
            case "browser_press" ->
                BrowserRequest.of("press").withKey(text(args, "key")).snapshotting();
            case "browser_tabs" ->
                switch (text(args, "action")) {
                    case "list" -> BrowserRequest.of("status");
                    case "switch" -> {
                        String tab = text(args, "tabId");
                        if (tab.isEmpty()) {
                            throw new IllegalArgumentException("switch 需要 tabId");
                        }
                        yield BrowserRequest.of("activate").withTarget(tab).snapshotting();
                    }
                    case "new" -> BrowserRequest.of("newTab");
                    case "close" -> BrowserRequest.of("closeTab").withTarget(text(args, "tabId"));
                    default -> throw new IllegalArgumentException("action 必须是 list、switch、new 或 close");
                };
            default -> null;
        };
    }

    private static String text(JsonNode args, String key) {
        if (args == null) {
            return "";
        }
        JsonNode v = args.get(key);
        return v == null || !v.isString() ? "" : v.asString().strip();
    }

    private Map<String, Object> call(Object id, JsonNode params, RunTokens.Claims claims) {
        String name = params == null ? "" : params.path("name").asString("").strip();
        int qualified = name.lastIndexOf("__");
        String tool = qualified >= 0 ? name.substring(qualified + 2) : name;
        BrowserRequest request;
        try {
            request = request(tool, params == null ? null : params.get("arguments"));
        } catch (IllegalArgumentException e) {
            return McpRpc.result(id, McpRpc.toolResult(e.getMessage(), true, null));
        }
        if (request == null) {
            return McpRpc.error(id, McpRpc.INVALID_PARAMS, "unknown tool: " + name);
        }
        Optional<BrowserWorkspaces.Place> place = place(claims);
        if (place.isEmpty()) {
            return McpRpc.result(id, McpRpc.toolResult("本次运行或对话已不存在", true, null));
        }
        BrowserOperations.Outcome outcome = operations.apply(
                place.get().key(), place.get().workspace(), place.get().notHere(), request.ensuring());
        if (!outcome.available()) {
            return McpRpc.result(id, McpRpc.toolResult(outcome.reason(), true, null));
        }
        String summary = AgentBrowser.describe(outcome.state(), outcome.error());
        if ("screenshot".equals(request.operation()) && outcome.state().frame() != null && outcome.error() == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(
                    "content",
                    List.of(
                            Map.of("type", "text", "text", summary),
                            Map.of(
                                    "type",
                                    "image",
                                    "mimeType",
                                    "image/jpeg",
                                    "data",
                                    outcome.state().frame())));
            result.put("isError", false);
            return McpRpc.result(id, result);
        }
        return McpRpc.result(id, McpRpc.toolResult(summary, outcome.error() != null, null));
    }

    private Optional<BrowserWorkspaces.Place> place(RunTokens.Claims claims) {
        String run = claims.runId();
        if (run.startsWith(RunTokens.CONVERSATION_PREFIX)) {
            return places.conversation(run.substring(RunTokens.CONVERSATION_PREFIX.length()));
        }
        return engines.getObject().find(run).map(places::run);
    }
}
