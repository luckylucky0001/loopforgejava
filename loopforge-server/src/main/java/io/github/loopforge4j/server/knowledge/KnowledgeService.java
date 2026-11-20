package io.github.loopforge4j.server.knowledge;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.web.ApiException;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Team knowledge base (upstream {@code knowledge.Service}): the platform holds the upstream token,
 * projects read only their sources, and distilled knowledge reaches the knowledge base only through
 * a reviewed queue.
 */
@Service
public class KnowledgeService {

    public static final String TOOL_SEARCH = "kb_search";
    public static final String TOOL_FETCH = "kb_fetch";
    public static final String TOOL_LIST_SOURCES = "kb_list_sources";
    public static final String TOOL_GET_SOURCE = "kb_get_source";
    public static final String TOOL_WRITE = "kb_write_document";
    /** The only upstream surface agents and readers ever reach. */
    public static final Set<String> READ_ONLY_TOOLS =
            Set.of(TOOL_SEARCH, TOOL_FETCH, TOOL_LIST_SOURCES, TOOL_GET_SOURCE);

    public static final String USAGE_RULE_ID = "loopforge-knowledge-usage";
    static final String NOT_CONFIGURED = "知识库未接入：请管理员在「知识库 → 接入配置」填写地址和 Token 并启用";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final int ACTIVITY_LIMIT = 200;
    private static final Pattern FRONT_MATTER_LINE = Pattern.compile("^([A-Za-z_]+)\\s*:\\s*(.*)$");
    private static final Pattern UNSAFE_PATH = Pattern.compile("[^\\p{L}\\p{N}._/-]+");

    private final KnowledgeStore store;
    private final RunStore runs;
    private final CatalogStore catalog;
    private final Clock clock;
    private final String configuredToken;
    private final Duration timeout;
    private final Map<String, McpHttpClient> clients = new ConcurrentHashMap<>();
    private final Deque<Activity> activity = new ArrayDeque<>();

    public KnowledgeService(
            KnowledgeStore store, RunStore runs, CatalogStore catalog, Clock clock, LoopForgeProperties properties) {
        this.store = store;
        this.runs = runs;
        this.catalog = catalog;
        this.clock = clock;
        String token = properties.knowledge().token();
        this.configuredToken = token == null || token.isBlank() ? null : token.strip();
        this.timeout = properties.knowledge().timeout();
    }

    // ------------------------------------------------------------------ settings

    /** Settings changes from the console; a blank token keeps the current one. */
    public record SettingsInput(
            Boolean enabled,
            String url,
            String token,
            Boolean clearToken,
            Boolean writeEnabled,
            List<String> defaultSources,
            Map<String, List<String>> projectSources) {}

    public KnowledgeSettings settings() {
        return store.settings();
    }

    public KnowledgeSettings publicSettings() {
        KnowledgeSettings s = store.settings();
        return s.publicView(token(s) != null);
    }

    public boolean ready() {
        KnowledgeSettings s = store.settings();
        return s.enabled() && s.url() != null && !s.url().isBlank() && token(s) != null;
    }

    public KnowledgeSettings updateSettings(SettingsInput input, String actor) {
        KnowledgeSettings current = store.settings();
        String url = input.url() == null ? current.url() : input.url().strip();
        if (url != null && !url.isEmpty()) {
            checkUrl(url);
        }
        String token = Boolean.TRUE.equals(input.clearToken())
                ? null
                : input.token() != null && !input.token().isBlank() ? stripBearer(input.token()) : current.token();
        if (token != null && token.length() > 4096) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "token is too long");
        }
        Map<String, List<String>> projectSources =
                input.projectSources() == null ? current.projectSources() : input.projectSources();
        if (projectSources.size() > 1000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "too many project source entries");
        }
        KnowledgeSettings next = new KnowledgeSettings(
                input.enabled() == null ? current.enabled() : input.enabled(),
                url,
                token,
                false,
                input.writeEnabled() == null ? current.writeEnabled() : input.writeEnabled(),
                input.defaultSources() == null ? current.defaultSources() : input.defaultSources(),
                projectSources,
                actor,
                clock.instant());
        store.saveSettings(next);
        clients.clear();
        return next.publicView(token(next) != null);
    }

    /** Connection test with the saved settings, or with a candidate URL/token before saving. */
    public Map<String, Object> check(String overrideUrl, String overrideToken) {
        KnowledgeSettings s = store.settings();
        String url = overrideUrl != null && !overrideUrl.isBlank() ? overrideUrl.strip() : s.url();
        String token = overrideToken != null && !overrideToken.isBlank() ? stripBearer(overrideToken) : token(s);
        Map<String, Object> out = new LinkedHashMap<>();
        if (url == null || url.isBlank() || token == null) {
            out.put("ok", false);
            out.put("error", NOT_CONFIGURED);
            return out;
        }
        checkUrl(url);
        long started = System.nanoTime();
        try {
            List<McpHttpClient.Tool> tools = new McpHttpClient(URI.create(url), token, timeout).tools();
            List<String> names = tools.stream().map(McpHttpClient.Tool::name).toList();
            out.put("ok", names.containsAll(List.of(TOOL_SEARCH, TOOL_FETCH)));
            out.put("tools", names);
            out.put("writeTool", names.contains(TOOL_WRITE));
            if (!names.containsAll(List.of(TOOL_SEARCH, TOOL_FETCH))) {
                out.put("error", "upstream does not offer " + TOOL_SEARCH + " / " + TOOL_FETCH);
            }
        } catch (IOException e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        out.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
        return out;
    }

    // ------------------------------------------------------------------ reading

    /** Upstream read-only tools, as agents are offered them. */
    public List<Map<String, Object>> readOnlyTools() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (McpHttpClient.Tool tool : client().tools()) {
            if (!READ_ONLY_TOOLS.contains(tool.name())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put(
                    "inputSchema",
                    tool.inputSchema() == null || !tool.inputSchema().isObject()
                            ? Map.of("type", "object")
                            : tool.inputSchema());
            if (tool.annotations() != null && tool.annotations().isObject()) {
                item.put("annotations", tool.annotations());
            }
            out.add(item);
        }
        return out;
    }

    /** One scoped read-only call: the text upstream returned and how many hits it had. */
    public record Scoped(String text, int hits) {}

    /**
     * Calls a read-only tool within the project's sources: searches are narrowed to them, fetches
     * of other sources are refused and source lists are filtered.
     */
    public Scoped callScoped(String projectId, String tool, Map<String, Object> arguments) throws IOException {
        if (!READ_ONLY_TOOLS.contains(tool)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "知识库工具 " + tool + " 不可用：只能只读检索，新知识请写入 knowledge.md 等待审核");
        }
        KnowledgeSettings s = requireReady();
        List<String> allowed = s.sourcesFor(projectId);
        Map<String, Object> args = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        switch (tool) {
            case TOOL_SEARCH -> {
                List<String> scoped = scope(allowed, stringList(args.get("sources")));
                args.remove("sources");
                if (!scoped.isEmpty()) {
                    args.put("sources", scoped);
                }
            }
            case TOOL_FETCH, TOOL_GET_SOURCE -> {
                Object source = args.get("source");
                if (!sourceAllowed(allowed, source == null ? "" : source.toString())) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "知识库来源 " + source + " 不在当前项目的授权范围");
                }
            }
            default -> {
                // list sources: filtered below
            }
        }
        String text = client().call(tool, args);
        if (TOOL_LIST_SOURCES.equals(tool)) {
            return filterSources(text, allowed);
        }
        if (TOOL_SEARCH.equals(tool)) {
            try {
                JsonNode parsed = Json.mapper().readTree(text);
                return new Scoped(text, parsed.path("results").size());
            } catch (JacksonException e) {
                return new Scoped(text, 0);
            }
        }
        return new Scoped(text, 0);
    }

    /** Reads for the console and API: parsed JSON of a scoped call, recorded as activity. */
    public JsonNode read(String projectId, String tool, Map<String, Object> arguments, String who) {
        long started = System.nanoTime();
        try {
            Scoped result = callScoped(projectId, tool, arguments);
            record(tool, projectId, null, null, who, arguments, result.hits(), null, started);
            try {
                return Json.mapper().readTree(result.text());
            } catch (JacksonException e) {
                return Json.mapper().getNodeFactory().stringNode(result.text());
            }
        } catch (IOException e) {
            record(tool, projectId, null, null, who, arguments, 0, e.getMessage(), started);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "知识库调用失败：" + e.getMessage());
        } catch (ApiException e) {
            record(tool, projectId, null, null, who, arguments, 0, e.getMessage(), started);
            throw e;
        }
    }

    // ------------------------------------------------------------------ activity

    /** One knowledge base call, kept in memory so the console can show who uses the knowledge base. */
    public record Activity(
            Instant at,
            String tool,
            String projectId,
            String runId,
            String agentId,
            String caller,
            String query,
            int hits,
            boolean ok,
            String error,
            long latencyMs) {}

    public void record(
            String tool,
            String projectId,
            String runId,
            String agentId,
            String caller,
            Map<String, Object> arguments,
            int hits,
            String error,
            long startedNanos) {
        Object query = arguments == null ? null : arguments.get("query");
        Activity item = new Activity(
                clock.instant(),
                tool,
                projectId,
                runId,
                agentId,
                caller,
                query == null ? null : McpHttpClient.clip(query.toString(), 120),
                hits,
                error == null,
                error == null ? null : McpHttpClient.clip(error, 200),
                (System.nanoTime() - startedNanos) / 1_000_000);
        synchronized (activity) {
            activity.addFirst(item);
            while (activity.size() > ACTIVITY_LIMIT) {
                activity.removeLast();
            }
        }
        log.info(
                "knowledge tool={} project={} run={} agent={} ok={} latency={}ms",
                tool,
                projectId,
                runId,
                agentId,
                item.ok(),
                item.latencyMs());
    }

    /** Newest first; {@code projects} {@code null} means all. */
    public List<Activity> activity(Set<String> projects) {
        synchronized (activity) {
            return activity.stream()
                    .filter(a -> projects == null || a.projectId() != null && projects.contains(a.projectId()))
                    .toList();
        }
    }

    /** Roles that bind the knowledge base. */
    public List<Map<String, String>> usage() {
        return catalog.agents().stream()
                .filter(AgentDefinition::knowledgeBase)
                .map(a -> Map.of("agentId", a.id(), "name", a.name()))
                .toList();
    }

    // ------------------------------------------------------------------ review queue

    /** A new queue entry: from a run artifact (front matter supplies defaults) or raw content. */
    public record SubmissionInput(
            String projectId,
            String runId,
            String artifactId,
            String title,
            String source,
            String path,
            String content) {}

    /** A reviewer's edit; it moves the entry back to pending review. */
    public record SubmissionEdit(String title, String source, String path, String content, String reviewNote) {}

    /**
     * @param run the artifact's run, already access-checked by the caller ({@code null} for raw
     *     content)
     */
    public KnowledgeSubmission submit(SubmissionInput input, Run run, String actor) {
        String content = input.content();
        String projectId = blankToNull(input.projectId());
        String runId = null;
        String nodeId = null;
        String artifactId = null;
        String workflowId = null;
        if (input.artifactId() != null && !input.artifactId().isBlank()) {
            if (run == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "运行产物不存在");
            }
            if (store.submissionOfArtifact(input.artifactId()).isPresent()) {
                throw new ApiException(HttpStatus.CONFLICT, "该产物已加入沉淀队列");
            }
            Artifact artifact = runs.artifact(run.id(), input.artifactId())
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "运行产物不存在"));
            content = artifact.content();
            runId = run.id();
            nodeId = artifact.nodeId();
            artifactId = artifact.id();
            workflowId = run.workflow().id();
            projectId = run.context().projectId();
        }
        Distilled parsed = parseDistilled(content == null ? "" : content);
        if (parsed.body().isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "沉淀内容不能为空");
        }
        if (parsed.body().length() > 200_000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "沉淀内容过长");
        }
        KnowledgeSubmission item = new KnowledgeSubmission(
                "kbs-" + UUID.randomUUID(),
                projectId,
                runId,
                nodeId,
                artifactId,
                workflowId,
                clip(firstNonBlank(input.title(), parsed.title(), "未命名知识"), 300),
                clip(firstNonBlank(input.source(), parsed.source(), ""), 200),
                clip(firstNonBlank(input.path(), parsed.path(), ""), 500),
                parsed.body(),
                KnowledgeSubmission.PENDING,
                null,
                null,
                null,
                actor,
                null,
                null,
                null,
                null);
        try {
            return store.save(item);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "该产物已加入沉淀队列");
        }
    }

    public KnowledgeSubmission edit(KnowledgeSubmission item, SubmissionEdit edit) {
        requireMutable(item, "已写回知识库的条目不能再编辑");
        String content = edit.content() != null && !edit.content().isBlank() ? edit.content() : item.content();
        if (content.length() > 200_000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "沉淀内容过长");
        }
        return store.save(item.withReview(
                clip(firstNonBlank(edit.title(), item.title(), "未命名知识"), 300),
                clip(edit.source() == null ? "" : edit.source().strip(), 200),
                clip(edit.path() == null ? "" : edit.path().strip(), 500),
                content,
                KnowledgeSubmission.PENDING,
                null,
                null,
                edit.reviewNote() == null ? null : clip(edit.reviewNote(), 2000),
                item.reviewedBy(),
                null));
    }

    public void delete(KnowledgeSubmission item) {
        requireMutable(item, "已写回知识库的条目不能删除");
        store.delete(item.id());
    }

    public KnowledgeSubmission reject(KnowledgeSubmission item, String note, String actor) {
        requireMutable(item, "已写回知识库的条目不能驳回");
        return store.save(item.withReview(
                item.title(),
                item.source(),
                item.path(),
                item.content(),
                KnowledgeSubmission.REJECTED,
                null,
                null,
                note == null ? null : clip(note.strip(), 2000),
                actor,
                null));
    }

    /**
     * Writes a reviewed entry into the knowledge base. Needs the platform write switch and a source
     * in the entry's project scope; an upstream failure is recorded on the entry.
     */
    public KnowledgeSubmission publish(KnowledgeSubmission item, String actor) {
        requireMutable(item, "该条目已写回知识库");
        KnowledgeSettings s = requireReady();
        if (!s.writeEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "平台未开启知识写回：请管理员在「接入配置」中打开「允许审核后写回」");
        }
        String source = item.source() == null ? "" : item.source().strip();
        if (source.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "请先选择写入的知识库来源");
        }
        if (!sourceAllowed(s.sourcesFor(item.projectId()), source)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "该知识库来源不在项目授权范围内");
        }
        String path = normalizeDocPath(item.path());
        if (path == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "写入路径必须是仓库内的 .md 相对路径，例如 guides/xxx.md");
        }
        String content = item.content();
        if (item.title() != null && !item.title().isBlank() && !content.strip().startsWith("#")) {
            content = "# " + item.title().strip() + "\n\n" + content;
        }
        long started = System.nanoTime();
        try {
            String text = client().call(TOOL_WRITE, Map.of("source", source, "path", path, "content", content));
            String commit = null;
            try {
                commit = Json.mapper().readTree(text).path("commit").asString(null);
            } catch (JacksonException e) {
                // commit unknown
            }
            record(TOOL_WRITE, item.projectId(), item.runId(), null, actor, Map.of(), 0, null, started);
            return store.save(item.withReview(
                    item.title(),
                    source,
                    path,
                    item.content(),
                    KnowledgeSubmission.PUBLISHED,
                    commit,
                    null,
                    item.reviewNote(),
                    actor,
                    clock.instant()));
        } catch (IOException e) {
            record(TOOL_WRITE, item.projectId(), item.runId(), null, actor, Map.of(), 0, e.getMessage(), started);
            store.save(item.withReview(
                    item.title(),
                    source,
                    path,
                    item.content(),
                    item.status(),
                    null,
                    clip(e.getMessage(), 500),
                    item.reviewNote(),
                    actor,
                    null));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "写回知识库失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ agents

    /** The usage rule injected with the knowledge MCP (upstream {@code knowledge.UsageRule}). */
    public static RuleDefinition usageRule() {
        return new RuleDefinition(
                USAGE_RULE_ID,
                "知识库使用规范",
                "先检索团队知识库再动手，引用附出处，新知识写入 knowledge.md 待审核沉淀",
                """
                # 知识库使用规范

                本角色已接入团队知识库（MCP：%s，只读）。

                ## 先查再做
                - 动手前先用 kb_search 检索与任务相关的已有知识：排障手册、业务规范、历史方案、接口约定。
                - 命中后用 kb_fetch 按 source + path + 行号读取原文，不要只凭摘要下结论。
                - kb_list_sources 可查看当前项目可用的知识库来源；检索默认覆盖全部已授权来源。
                - 结果 degraded=true 表示语义检索降级为词法检索，可换关键词或更短的词再搜一次。

                ## 引用出处
                - 采用知识库内容时，在产物中附上结果里的 citation（形如 source@commit:path#L10-L20）。
                - 知识库没有覆盖的内容要明确说明"知识库未收录"，不要编造出处。

                ## 沉淀新知识
                - 知识库对你只读，不要尝试写入。
                - 本次任务中沉淀出的可复用知识（踩坑、排障步骤、团队约定），写到工作区根目录的 knowledge.md，
                  平台会在人工审核后写回知识库。格式：

                ```markdown
                ---
                title: 一句话标题
                source: 建议写入的知识库来源 id（可选）
                path: 建议路径，例如 guides/xxx.md（可选）
                ---
                正文（Markdown）
                ```
                """.formatted(PlatformMcp.KNOWLEDGE_ID),
                50,
                null);
    }

    // ------------------------------------------------------------------ helpers

    record Distilled(String title, String source, String path, String body) {}

    /** Optional front matter (title/source/path), else the first Markdown heading as title. */
    static Distilled parseDistilled(String content) {
        String text = content.replace("\r\n", "\n");
        String title = "";
        String source = "";
        String path = "";
        String body = text;
        if (text.startsWith("---\n")) {
            int end = text.indexOf("\n---", 4);
            if (end >= 0) {
                String header = text.substring(4, end);
                String rest = text.substring(end + 4);
                body = rest.replaceFirst("^[^\\n]*\\n", "").replaceAll("^\\n+", "");
                for (String line : header.split("\n")) {
                    Matcher m = FRONT_MATTER_LINE.matcher(line.strip());
                    if (!m.matches()) {
                        continue;
                    }
                    String value = m.group(2).strip().replaceAll("^[\"']|[\"']$", "");
                    switch (m.group(1).toLowerCase(Locale.ROOT)) {
                        case "title" -> title = value;
                        case "source" -> source = value;
                        case "path" -> path = value;
                        default -> {
                            // ignored
                        }
                    }
                }
            }
        }
        if (title.isEmpty()) {
            for (String line : body.split("\n")) {
                String l = line.strip();
                if (l.startsWith("#")) {
                    title = l.replaceAll("^#+", "").strip();
                    break;
                }
            }
        }
        return new Distilled(title, source, path, body);
    }

    /** A repository-relative Markdown path, or {@code null} (upstream {@code NormalizeDocPath}). */
    static String normalizeDocPath(String p) {
        if (p == null) {
            return null;
        }
        String s = UNSAFE_PATH.matcher(p.strip().replace('\\', '/')).replaceAll("-");
        java.nio.file.Path normalized = java.nio.file.Path.of("/" + s).normalize();
        String out = normalized.toString().replace('\\', '/').replaceAll("^/+", "");
        if (out.isEmpty()
                || out.equals(".")
                || out.startsWith("../")
                || !out.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return null;
        }
        return out;
    }

    /** Requested sources within the allowed ones (empty allowed: unrestricted, empty request: all allowed). */
    static List<String> scope(List<String> allowed, List<String> requested) {
        List<String> req = KnowledgeSettings.clean(requested);
        List<String> ok = KnowledgeSettings.clean(allowed);
        if (ok.isEmpty()) {
            return req;
        }
        if (req.isEmpty()) {
            return ok;
        }
        for (String s : req) {
            if (!ok.contains(s)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "知识库来源 " + s + " 不在当前项目的授权范围");
            }
        }
        return req;
    }

    static boolean sourceAllowed(List<String> allowed, String source) {
        List<String> ok = KnowledgeSettings.clean(allowed);
        return ok.isEmpty() || ok.contains(source.strip());
    }

    private static Scoped filterSources(String text, List<String> allowed) throws IOException {
        JsonNode parsed;
        try {
            parsed = Json.mapper().readTree(text);
        } catch (JacksonException e) {
            throw new McpHttpClient.McpException("知识库来源列表无法解析");
        }
        ArrayNode kept = Json.mapper().createArrayNode();
        for (JsonNode source : parsed.path("sources")) {
            if (sourceAllowed(allowed, source.path("id").asString(""))) {
                kept.add(source);
            }
        }
        ObjectNode out = Json.mapper().createObjectNode();
        out.set("sources", kept);
        return new Scoped(Json.write(out), kept.size());
    }

    static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        if (value instanceof String s && !s.isBlank()) {
            return List.of(s.split(","));
        }
        return List.of();
    }

    private KnowledgeSettings requireReady() {
        KnowledgeSettings s = store.settings();
        if (!s.enabled() || s.url() == null || s.url().isBlank() || token(s) == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED);
        }
        return s;
    }

    private McpHttpClient client() {
        KnowledgeSettings s = requireReady();
        String token = token(s);
        return clients.computeIfAbsent(
                s.url() + "\u0000" + token, k -> new McpHttpClient(URI.create(s.url()), token, timeout));
    }

    private String token(KnowledgeSettings s) {
        return configuredToken != null ? configuredToken : s.token();
    }

    private static void checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "url is invalid");
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || url.length() > 2000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "url must be an http(s) URL without credentials");
        }
    }

    private static void requireMutable(KnowledgeSubmission item, String message) {
        if (KnowledgeSubmission.PUBLISHED.equals(item.status())) {
            throw new ApiException(HttpStatus.CONFLICT, message);
        }
    }

    private static String stripBearer(String token) {
        String t = token.strip();
        return t.regionMatches(true, 0, "Bearer ", 0, 7) ? t.substring(7).strip() : t;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return "";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
