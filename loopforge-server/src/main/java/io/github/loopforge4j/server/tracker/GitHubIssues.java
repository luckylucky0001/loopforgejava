package io.github.loopforge4j.server.tracker;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/** GitHub issues of a repository ({@code owner/name}); pull requests in the issue list are skipped. */
public class GitHubIssues {

    private final URI api;
    private final Supplier<String> token;

    public GitHubIssues(URI api, Supplier<String> token) {
        this.api = api;
        this.token = token;
    }

    public List<ExternalItem> list(String repo) throws TrackerException {
        List<ExternalItem> out = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode items = TrackerHttp.send(
                    request("/repos/" + repo + "/issues?state=all&per_page=100&sort=updated&page=" + page)
                            .GET());
            for (JsonNode issue : items) {
                if (issue.has("pull_request")) {
                    continue;
                }
                out.add(item(issue));
            }
            if (items.size() < 100) {
                break;
            }
        }
        return out;
    }

    public ExternalItem create(String repo, String title, String body) throws TrackerException {
        return item(TrackerHttp.send(request("/repos/" + repo + "/issues")
                .POST(TrackerHttp.json(Map.of("title", title, "body", body == null ? "" : body)))));
    }

    public void comment(String repo, int number, String body) throws TrackerException {
        TrackerHttp.send(request("/repos/" + repo + "/issues/" + number + "/comments")
                .POST(TrackerHttp.json(Map.of("body", body))));
    }

    private HttpRequest.Builder request(String path) throws TrackerException {
        if (path.contains("/../") || path.contains("/./")) {
            throw new TrackerException("invalid GitHub repository name");
        }
        String credential = token.get();
        if (credential == null || credential.isBlank()) {
            throw new TrackerException("no GitHub token is configured (loopforge.git.https entry for the API host)");
        }
        return HttpRequest.newBuilder(TrackerHttp.uri(api, path))
                .header("Authorization", "Bearer " + credential)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json");
    }

    private static ExternalItem item(JsonNode issue) {
        String updated = TrackerHttp.text(issue, "updated_at");
        return new ExternalItem(
                "issue",
                TrackerHttp.text(issue, "id"),
                issue.path("number").asInt(),
                TrackerHttp.text(issue, "title"),
                TrackerHttp.text(issue, "body"),
                "open".equals(TrackerHttp.text(issue, "state")),
                TrackerHttp.text(issue, "state"),
                "medium",
                TrackerHttp.text(issue, "html_url"),
                TrackerHttp.text(issue.path("assignee"), "login"),
                updated.isEmpty() ? null : Instant.parse(updated));
    }
}
