package io.github.loopforge4j.server.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.delivery.DeliveryException;
import io.github.loopforge4j.core.delivery.DeliveryProvider;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.RepositoryProvider;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitLabDeliveryProviderTest {

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mergeRequestsAreQueriedByProjectPathAndMapped() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN") + " "
                    + exchange.getRequestURI().getRawPath() + "?"
                    + exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    [{"iid":3,"web_url":"https://gitlab.example.com/group/sub/app/-/merge_requests/3","state":"merged",
                      "source_branch":"feature/x","target_branch":"main","sha":"abc","merged_at":"2026-01-02T03:04:05Z",
                      "updated_at":"2026-01-02T03:04:05Z"},
                     {"iid":4,"web_url":"u4","state":"opened","source_branch":"feature/x","target_branch":"main","sha":"def",
                      "merged_at":null,"updated_at":"2026-02-01T00:00:00Z"},
                     {"iid":5,"web_url":"u5","state":"locked","source_branch":"feature/x","target_branch":"main","sha":"x",
                      "updated_at":null}]
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4");
        GitLabDeliveryProvider gitlab = new GitLabDeliveryProvider(
                Map.of("gitlab.example.com", api), host -> host.equals("gitlab.example.com") ? "glpat-secret" : null);
        RepositoryContext rc = context("https://gitlab.example.com/group/sub/app.git", RepositoryProvider.GITLAB);
        assertThat(gitlab.supports(rc)).isTrue();
        assertThat(gitlab.supports(context("https://github.com/a/b.git", RepositoryProvider.GITHUB)))
                .isFalse();
        assertThat(gitlab.supports(context("https://other.example.com/a/b.git", RepositoryProvider.GITLAB)))
                .isFalse();

        List<PullRequest> pulls = gitlab.pullRequests(rc, "feature/x", "main");
        assertThat(requests)
                .singleElement()
                .satisfies(r -> assertThat(r)
                        .startsWith("glpat-secret /api/v4/projects/group%2Fsub%2Fapp/merge_requests?")
                        .contains("source_branch=feature%2Fx", "target_branch=main", "state=all"));
        assertThat(pulls).extracting(PullRequest::number).containsExactly(4, 3, 5);
        assertThat(pulls).extracting(PullRequest::state).containsExactly("open", "merged", "closed");
        assertThat(pulls.get(1).mergedAt()).isNotNull();

        DeliveryProvider composite = DeliveryProvider.composite(List.of(DeliveryProvider.NONE, gitlab));
        assertThat(composite.supports(rc)).isTrue();
        assertThat(composite.pullRequests(rc, "feature/x", "main")).hasSize(3);

        GitLabDeliveryProvider noToken = new GitLabDeliveryProvider(Map.of("gitlab.example.com", api), host -> null);
        assertThatThrownBy(() -> noToken.pullRequests(rc, "feature/x", "main"))
                .isInstanceOf(DeliveryException.class)
                .hasMessageContaining("no token");
    }

    private static RepositoryContext context(String url, RepositoryProvider provider) {
        return new RepositoryContext(
                "app", "app", provider, url, null, "main", "feature/x", "app", false, false, false, null);
    }
}
