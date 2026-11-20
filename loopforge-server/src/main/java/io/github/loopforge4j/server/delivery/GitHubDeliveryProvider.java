package io.github.loopforge4j.server.delivery;

import io.github.loopforge4j.core.delivery.DeliveryException;
import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

/**
 * Pull requests of GitHub repositories via the REST API (upstream {@code tracker/github.go}). Works
 * with github.com and GitHub Enterprise ({@code loopforge.github.api-url}).
 */
public class GitHubDeliveryProvider implements DeliveryProvider {

    private final HttpClient http;
    private final URI apiBase;
    private final Supplier<String> token;

    public GitHubDeliveryProvider(URI apiBase, Supplier<String> token) {
        this.apiBase = apiBase;
        this.token = token;
        this.http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean supports(RepositoryContext repository) {
        return repository.provider().hostsPullRequests() && repository.githubRepo() != null;
    }

    @Override
    public List<PullRequest> pullRequests(RepositoryContext repository, String head, String base)
            throws DeliveryException {
        String credential = token.get();
        if (credential == null || credential.isBlank()) {
            throw new DeliveryException("no GitHub token is configured (loopforge.git.https entry for the API host)");
        }
        String repo = repository.githubRepo();
        String owner = repo.substring(0, repo.indexOf('/'));
        String query = "state=all&per_page=30&head=" + encode(owner + ":" + head) + "&base=" + encode(base);
        URI uri = URI.create(apiBase.toString().replaceAll("/+$", "") + "/repos/" + repo + "/pulls?" + query);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Authorization", "Bearer " + credential)
                .header("User-Agent", "loopforge4j")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DeliveryException("GitHub API unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryException("interrupted");
        }
        if (response.statusCode() == 403
                && "0"
                        .equals(response.headers()
                                .firstValue("X-RateLimit-Remaining")
                                .orElse(""))) {
            throw new DeliveryException("GitHub API rate limit exhausted");
        }
        if (response.statusCode() != 200) {
            throw new DeliveryException("GitHub API returned " + response.statusCode() + " for " + repo);
        }
        List<PullRequest> pulls = new ArrayList<>();
        for (JsonNode pr : Json.mapper().readTree(response.body())) {
            String mergedAt = text(pr, "merged_at");
            String state = !mergedAt.isEmpty() ? "merged" : "open".equals(text(pr, "state")) ? "open" : "closed";
            pulls.add(new PullRequest(
                    repository.repositoryId(),
                    pr.path("number").asInt(),
                    text(pr, "html_url"),
                    state,
                    text(pr.path("head"), "ref"),
                    text(pr.path("base"), "ref"),
                    text(pr.path("head"), "sha"),
                    mergedAt.isEmpty() ? null : Instant.parse(mergedAt),
                    instant(text(pr, "updated_at"))));
        }
        pulls.sort(Comparator.comparing(PullRequest::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return pulls;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    private static Instant instant(String value) {
        return value.isEmpty() ? null : Instant.parse(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
