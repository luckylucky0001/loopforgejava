package io.github.loopforge4j.server.delivery;

import io.github.loopforge4j.core.delivery.DeliveryException;
import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RepositoryProvider;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.git.RepositoryRemote;
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
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/**
 * Merge requests of GitLab and GitLab-compatible hosts such as 工蜂 (upstream {@code gitHostGet} on
 * {@code /projects/:path/merge_requests}). The API root is configured per host; the token is the
 * host's Git HTTPS credential, sent as {@code PRIVATE-TOKEN}.
 */
public class GitLabDeliveryProvider implements DeliveryProvider {

    private final Map<String, URI> apiByHost;
    private final Function<String, String> tokenForHost;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * @param apiByHost lower-case host → API root, e.g. {@code gitlab.example.com →
     *     https://gitlab.example.com/api/v4}
     */
    public GitLabDeliveryProvider(Map<String, URI> apiByHost, Function<String, String> tokenForHost) {
        this.apiByHost = Map.copyOf(apiByHost);
        this.tokenForHost = tokenForHost;
    }

    @Override
    public boolean supports(RepositoryContext repository) {
        return repository.provider() == RepositoryProvider.GITLAB
                && RepositoryRemote.parse(repository.cloneUrl())
                        .map(r -> apiByHost.containsKey(r.host()))
                        .orElse(false);
    }

    @Override
    public List<PullRequest> pullRequests(RepositoryContext repository, String head, String base)
            throws DeliveryException {
        RepositoryRemote remote = RepositoryRemote.parse(repository.cloneUrl())
                .orElseThrow(() -> new DeliveryException("cannot parse the clone URL of " + repository.name()));
        String token = tokenForHost.apply(remote.host());
        if (token == null || token.isBlank()) {
            throw new DeliveryException("no token is configured for " + remote.host() + " (loopforge.git.https)");
        }
        URI uri = URI.create(apiByHost.get(remote.host()).toString().replaceAll("/+$", "") + "/projects/"
                + encode(remote.path()) + "/merge_requests?state=all&per_page=30&source_branch=" + encode(head)
                + "&target_branch=" + encode(base));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("PRIVATE-TOKEN", token)
                .header("Accept", "application/json")
                .header("User-Agent", "loopforge4j")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DeliveryException(remote.host() + " unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DeliveryException("interrupted");
        }
        if (response.statusCode() != 200) {
            throw new DeliveryException(
                    remote.host() + " returned HTTP " + response.statusCode() + " for " + remote.path());
        }
        List<PullRequest> pulls = new ArrayList<>();
        for (JsonNode mr : Json.mapper().readTree(response.body())) {
            String state = switch (text(mr, "state")) {
                case "opened" -> "open";
                case "merged" -> "merged";
                default -> "closed";
            };
            String mergedAt = text(mr, "merged_at");
            pulls.add(new PullRequest(
                    repository.repositoryId(),
                    mr.path("iid").asInt(),
                    text(mr, "web_url"),
                    state,
                    text(mr, "source_branch"),
                    text(mr, "target_branch"),
                    text(mr, "sha"),
                    mergedAt.isEmpty() ? null : Instant.parse(mergedAt),
                    text(mr, "updated_at").isEmpty() ? null : Instant.parse(text(mr, "updated_at"))));
        }
        pulls.sort(Comparator.comparing(PullRequest::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return pulls;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
