package io.github.loopforge4j.server.tracker;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** GitLab and 工蜂 (GitLab-compatible API) issues of a project path, with the host's token. */
public class GitLabIssues {

    private final Map<String, URI> apiByHost;
    private final Function<String, String> tokenForHost;

    public GitLabIssues(Map<String, URI> apiByHost, Function<String, String> tokenForHost) {
        this.apiByHost = Map.copyOf(apiByHost);
        this.tokenForHost = tokenForHost;
    }

    public boolean supports(String host) {
        return apiByHost.containsKey(host);
    }

    public List<ExternalItem> list(String host, String path) throws TrackerException {
        List<ExternalItem> out = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode items = TrackerHttp.send(request(
                            host,
                            "/projects/" + TrackerHttp.encode(path)
                                    + "/issues?per_page=100&order_by=updated_at&sort=desc&page=" + page)
                    .GET());
            for (JsonNode issue : items) {
                out.add(item(issue));
            }
            if (items.size() < 100) {
                break;
            }
        }
        return out;
    }

    public ExternalItem create(String host, String path, String title, String description) throws TrackerException {
        return item(TrackerHttp.send(request(host, "/projects/" + TrackerHttp.encode(path) + "/issues")
                .POST(TrackerHttp.json(
                        Map.of("title", title, "description", description == null ? "" : description)))));
    }

    public void comment(String host, String path, int iid, String body) throws TrackerException {
        TrackerHttp.send(request(host, "/projects/" + TrackerHttp.encode(path) + "/issues/" + iid + "/notes")
                .POST(TrackerHttp.json(Map.of("body", body))));
    }

    private HttpRequest.Builder request(String host, String path) throws TrackerException {
        URI api = apiByHost.get(host);
        if (api == null) {
            throw new TrackerException(host + " is not a configured GitLab host (loopforge.gitlab)");
        }
        String token = tokenForHost.apply(host);
        if (token == null || token.isBlank()) {
            throw new TrackerException("no token is configured for " + host + " (loopforge.git.https)");
        }
        return HttpRequest.newBuilder(TrackerHttp.uri(api, path))
                .header("PRIVATE-TOKEN", token)
                .header("Content-Type", "application/json");
    }

    /** GitLab priority labels are free text; weight-like {@code priority} is used by 工蜂 (1 highest). */
    static String priority(JsonNode issue) {
        JsonNode p = issue.get("priority");
        if (p != null && p.isNumber()) {
            int v = p.asInt();
            return v <= 2 ? "urgent" : v == 3 ? "high" : v == 4 ? "medium" : "low";
        }
        for (JsonNode label : issue.path("labels")) {
            String l = label.asString("").toLowerCase(java.util.Locale.ROOT);
            if (l.contains("urgent") || l.contains("critical") || l.contains("紧急")) {
                return "urgent";
            }
            if (l.contains("high") || l.contains("高")) {
                return "high";
            }
            if (l.contains("low") || l.contains("低")) {
                return "low";
            }
        }
        return "medium";
    }

    private static ExternalItem item(JsonNode issue) {
        String updated = TrackerHttp.text(issue, "updated_at");
        return new ExternalItem(
                "issue",
                TrackerHttp.text(issue, "id"),
                issue.path("iid").asInt(),
                TrackerHttp.text(issue, "title"),
                TrackerHttp.text(issue, "description"),
                "opened".equals(TrackerHttp.text(issue, "state")),
                TrackerHttp.text(issue, "state"),
                priority(issue),
                TrackerHttp.text(issue, "web_url"),
                TrackerHttp.text(issue.path("assignee"), "username"),
                updated.isEmpty() ? null : OffsetDateTime.parse(updated).toInstant());
    }

    static Instant instant(String s) {
        return s == null || s.isEmpty() ? null : OffsetDateTime.parse(s).toInstant();
    }
}
