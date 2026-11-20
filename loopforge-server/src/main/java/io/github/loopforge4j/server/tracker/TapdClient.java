package io.github.loopforge4j.server.tracker;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * TAPD open API (stories, bugs, tasks, comments) with an API account. Upstream reaches TAPD through
 * an internal MCP gateway; the open API is what other deployments have.
 */
public class TapdClient {

    static final String STORY_FIELDS = "id,name,description,status,owner,priority,iteration_id,created,modified";
    static final String BUG_FIELDS =
            "id,title,description,status,current_owner,severity,priority,iteration_id,created,modified";
    static final String TASK_FIELDS =
            "id,name,description,status,owner,priority,story_id,iteration_id,created,modified";
    private static final ZoneId TAPD_ZONE = ZoneId.of("Asia/Shanghai");

    private final URI api;
    private final String web;
    private final String authorization;

    public TapdClient(URI api, String webUrl, String user, String password) {
        this.api = api;
        this.web = webUrl.replaceAll("/+$", "");
        this.authorization = user == null || user.isBlank()
                ? null
                : "Basic "
                        + Base64.getEncoder()
                                .encodeToString((user + ":" + (password == null ? "" : password))
                                        .getBytes(StandardCharsets.UTF_8));
    }

    public boolean configured() {
        return authorization != null;
    }

    public String webHost() {
        return URI.create(web).getHost();
    }

    /** Stories and bugs of one iteration. */
    public List<ExternalItem> iteration(String workspace, String iteration, String kind) throws TrackerException {
        List<ExternalItem> out = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("workspace_id", workspace);
            query.put("iteration_id", iteration);
            query.put("fields", fields(kind));
            query.put("limit", "200");
            query.put("page", String.valueOf(page));
            List<ExternalItem> items = records(get(endpoint(kind), query), kind, workspace);
            out.addAll(items);
            if (items.size() < 200) {
                break;
            }
        }
        return out;
    }

    public ExternalItem get(String workspace, String kind, String id) throws TrackerException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("workspace_id", workspace);
        query.put("id", id);
        query.put("fields", fields(kind));
        return records(get(endpoint(kind), query), kind, workspace).stream()
                .filter(i -> i.externalId().equals(id))
                .findFirst()
                .orElseThrow(() -> new TrackerException("TAPD " + kind + " " + id + " was not found"));
    }

    public ExternalItem createStory(String workspace, String iteration, String name, String description)
            throws TrackerException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("workspace_id", workspace);
        form.put("name", name);
        form.put("description", description == null ? "" : description);
        form.put("iteration_id", iteration);
        form.put("creator", "LoopForge");
        JsonNode data = post("/stories", form);
        return item(data.path("Story"), "story", workspace);
    }

    public void comment(String workspace, String kind, String id, String text) throws TrackerException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("workspace_id", workspace);
        form.put("entry_type", kind.equals("bug") ? "bug" : kind.equals("task") ? "tasks" : "stories");
        form.put("entry_id", id);
        form.put("description", text);
        form.put("author", "LoopForge");
        post("/comments", form);
    }

    private JsonNode get(String path, Map<String, String> query) throws TrackerException {
        return unwrap(
                TrackerHttp.send(request(path + "?" + TrackerHttp.form(query)).GET()));
    }

    private JsonNode post(String path, Map<String, String> form) throws TrackerException {
        return unwrap(TrackerHttp.send(request(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(TrackerHttp.form(form), StandardCharsets.UTF_8))));
    }

    private HttpRequest.Builder request(String path) throws TrackerException {
        if (authorization == null) {
            throw new TrackerException("no TAPD API account is configured (loopforge.tapd.api-user / api-password)");
        }
        return HttpRequest.newBuilder(TrackerHttp.uri(api, path)).header("Authorization", authorization);
    }

    /** TAPD answers {@code {"status":1,"data":...,"info":"success"}}; other status values are errors. */
    private static JsonNode unwrap(JsonNode reply) throws TrackerException {
        if (reply.path("status").asInt(0) != 1) {
            throw new TrackerException("TAPD error: " + clip(TrackerHttp.text(reply, "info")));
        }
        return reply.path("data");
    }

    private List<ExternalItem> records(JsonNode data, String kind, String workspace) {
        String wrapper = wrapper(kind);
        List<ExternalItem> out = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode row : data) {
                JsonNode record = row.has(wrapper) ? row.get(wrapper) : row;
                out.add(item(record, kind, workspace));
            }
        } else if (data.has(wrapper)) {
            out.add(item(data.get(wrapper), kind, workspace));
        }
        return out;
    }

    private ExternalItem item(JsonNode r, String kind, String workspace) {
        String id = TrackerHttp.text(r, "id");
        String status = TrackerHttp.text(r, "status");
        String title = kind.equals("bug") ? TrackerHttp.text(r, "title") : TrackerHttp.text(r, "name");
        String owner = kind.equals("bug") ? TrackerHttp.text(r, "current_owner") : TrackerHttp.text(r, "owner");
        String path = switch (kind) {
            case "bug" -> "/bugtrace/bugs/view/";
            case "task" -> "/prong/tasks/view/";
            default -> "/prong/stories/view/";
        };
        String local = status(kind, status);
        return new ExternalItem(
                kind,
                id,
                0,
                title,
                TrackerHttp.text(r, "description"),
                !local.equals("closed") && !local.equals("done") && !local.equals("cancelled"),
                status,
                priority(kind.equals("bug") ? TrackerHttp.text(r, "severity") : TrackerHttp.text(r, "priority")),
                web + "/" + workspace + path + id,
                firstOwner(owner),
                time(TrackerHttp.text(r, "modified")));
    }

    /** TAPD status → local requirement status (upstream {@code tapdStatusToStatus}). */
    public static String status(String kind, String raw) {
        String s = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (kind.equals("task")) {
            if (s.equals("open")) {
                return "todo";
            }
            if (s.equals("progressing")) {
                return "in_progress";
            }
        }
        return switch (s) {
            case "open", "开发中", "开发-中", "progressing", "in_progress" -> "in_progress";
            case "resolved", "已解决" -> "in_review";
            case "closed", "已关闭" -> "closed";
            case "done", "status_4", "合入", "已合入", "已完成", "完成" -> "done";
            case "rejected", "已拒绝" -> "cancelled";
            case "planned", "规划中" -> "todo";
            default -> "backlog";
        };
    }

    static String priority(String raw) {
        String p = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (p.contains("fatal") || p.contains("urgent") || p.contains("紧急") || p.contains("致命")) {
            return "urgent";
        }
        if (p.contains("serious") || p.contains("high") || p.equals("4") || p.contains("高") || p.contains("严重")) {
            return "high";
        }
        if (p.contains("low") || p.contains("nice") || p.equals("1") || p.contains("低") || p.contains("提示")) {
            return "low";
        }
        return "medium";
    }

    /** The first handler of {@code "alice;bob;"}, without a login suffix like {@code "Alice(alice)"}. */
    static String firstOwner(String raw) {
        if (raw == null) {
            return "";
        }
        for (String part : raw.split("[;,]")) {
            String p = part.strip();
            if (!p.isEmpty()) {
                int open = p.indexOf('(');
                return open > 0 && p.endsWith(")")
                        ? p.substring(open + 1, p.length() - 1).strip()
                        : p;
            }
        }
        return "";
    }

    private static Instant time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.strip().replace(' ', 'T'))
                    .atZone(TAPD_ZONE)
                    .toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String endpoint(String kind) {
        return kind.equals("bug") ? "/bugs" : kind.equals("task") ? "/tasks" : "/stories";
    }

    private static String wrapper(String kind) {
        return kind.equals("bug") ? "Bug" : kind.equals("task") ? "Task" : "Story";
    }

    private static String fields(String kind) {
        return kind.equals("bug") ? BUG_FIELDS : kind.equals("task") ? TASK_FIELDS : STORY_FIELDS;
    }

    private static String clip(String s) {
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
