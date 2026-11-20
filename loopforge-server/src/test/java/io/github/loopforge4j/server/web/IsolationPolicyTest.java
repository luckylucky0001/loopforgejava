package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Secure defaults: members only author and run SOPs in isolated sandboxes; setup, names and webhooks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IsolationPolicyTest {

    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-isolation");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:isolation;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> "isolation-test-token-0123456789-abcdef");
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    RestClient client(String token) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {});
        if (token != null) {
            builder.defaultHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    @Test
    void membersAreConfinedToIsolatedSandboxesAndProjectlessRunsStayWithAdmins() throws Exception {
        RestClient anonymous = client(null);
        // concurrent first-run setups: exactly one administrator is created
        List<Callable<Integer>> setups = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String name = "root" + i;
            setups.add(() -> anonymous
                    .post()
                    .uri("/api/v1/auth/setup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("username", name, "password", "correct horse battery"))
                    .retrieve()
                    .toEntity(MAP)
                    .getStatusCode()
                    .value());
        }
        List<Integer> codes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (Future<Integer> f : pool.invokeAll(setups)) {
                codes.add(f.get());
            }
        }
        assertThat(codes.stream().filter(c -> c == 201)).hasSize(1);
        RestClient admin = client("isolation-test-token-0123456789-abcdef");
        assertThat(admin.get().uri("/api/v1/users").retrieve().body(LIST).stream()
                        .filter(u -> "admin".equals(u.get("role"))))
                .hasSize(1);

        assertThat(post(admin, "/api/v1/users", Map.of("username", "operator", "password", "operator-password"))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        Map<String, Object> dev = post(admin, "/api/v1/users", Map.of("username", "dev", "password", "dev-password-1"))
                .getBody();
        put(admin, "/api/v1/projects/p", Map.of("name", "P"), 200);
        put(admin, "/api/v1/projects/p/members/" + dev.get("id"), Map.of("role", "maintainer"), 200);
        put(
                admin,
                "/api/v1/workflows/local-sop",
                Map.of("status", "published", "nodes", List.of(Map.of("id", "a", "type", "script", "script", "true"))),
                200);
        String token =
                (String) post(anonymous, "/api/v1/auth/login", Map.of("username", "dev", "password", "dev-password-1"))
                        .getBody()
                        .get("token");
        RestClient member = client(token);

        // the local sandbox is the server's own OS user: members may neither run nor author local SOPs
        ResponseEntity<Map<String, Object>> denied =
                post(member, "/api/v1/runs", Map.of("workflowId", "local-sop", "projectId", "p"));
        assertThat(denied.getStatusCode().value()).isEqualTo(403);
        assertThat(String.valueOf(denied.getBody().get("detail"))).contains("isolated profiles [docker]");
        put(
                member,
                "/api/v1/workflows/p-local",
                Map.of(
                        "projectId",
                        "p",
                        "nodes",
                        List.of(Map.of("id", "a", "type", "script", "script", "cat ../../api-token"))),
                403);
        put(
                member,
                "/api/v1/workflows/p-docker",
                Map.of(
                        "projectId",
                        "p",
                        "sandboxProfileId",
                        "docker",
                        "nodes",
                        List.of(Map.of("id", "a", "type", "script", "script", "true"))),
                200);

        // a projectless run belongs to administrators only
        String adminRun = (String) post(admin, "/api/v1/runs", Map.of("workflowId", "local-sop"))
                .getBody()
                .get("id");
        assertThat(member.get()
                        .uri("/api/v1/runs/{id}", adminRun)
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(member.get().uri("/api/v1/runs").retrieve().body(LIST))
                .extracting(r -> r.get("id"))
                .doesNotContain(adminRun);
        // project SOPs of other projects are not listed; export is administrative
        put(admin, "/api/v1/projects/q", Map.of("name", "Q"), 200);
        put(
                admin,
                "/api/v1/workflows/q-sop",
                Map.of("projectId", "q", "nodes", List.of(Map.of("id", "a", "type", "script", "script", "true"))),
                200);
        assertThat(member.get().uri("/api/v1/workflows").retrieve().body(LIST))
                .extracting(w -> w.get("id"))
                .contains("local-sop", "p-docker")
                .doesNotContain("q-sop");
        assertThat(member.get()
                        .uri("/api/v1/workflows/q-sop")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(member.get()
                        .uri("/api/v1/catalog/export")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);

        // webhooks may not target the server's own network by default
        ResponseEntity<Map<String, Object>> hook = post(
                member,
                "/api/v1/notifications",
                Map.of("projectId", "p", "name", "probe", "url", "http://169.254.169.254/latest/meta-data/"));
        assertThat(hook.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(hook.getBody().get("problems"))).contains("internal address");
        assertThat(post(
                                member,
                                "/api/v1/notifications",
                                Map.of("projectId", "p", "name", "local", "url", "http://127.0.0.1:9/x"))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    private ResponseEntity<Map<String, Object>> post(RestClient client, String path, Map<String, Object> body) {
        return client.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(MAP);
    }

    private void put(RestClient client, String path, Map<String, Object> body, int status) {
        ResponseEntity<String> response = client.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
        assertThat(response.getStatusCode().value())
                .as(path + ": " + response.getBody())
                .isEqualTo(status);
    }
}
