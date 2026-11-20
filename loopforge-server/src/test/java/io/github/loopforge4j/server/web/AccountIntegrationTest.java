package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Iteration 5 accounts: setup, login, tokens, projects, members and what each role may do. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccountIntegrationTest {

    static final String SERVICE = "account-test-token-0123456789-abcdefgh";
    static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-account-it");
        } catch (java.io.IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:accounts;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> SERVICE);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    static String adminToken;
    static String aliceToken;
    static String bobToken;
    static String aliceId;

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
    @Order(1)
    void firstAccountIsCreatedOnceAndIsAnAdministrator() {
        RestClient anonymous = client(null);
        assertThat(anonymous.get().uri("/api/v1/auth/status").retrieve().body(MAP))
                .containsEntry("setupRequired", true);
        assertThat(post(anonymous, "/api/v1/auth/setup", Map.of("username", "Root", "password", "short"))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        ResponseEntity<Map<String, Object>> setup =
                post(anonymous, "/api/v1/auth/setup", Map.of("username", "Root", "password", "correct horse battery"));
        assertThat(setup.getStatusCode().value()).isEqualTo(201);
        adminToken = (String) setup.getBody().get("token");
        assertThat(adminToken).startsWith("lfu_");
        assertThat(String.valueOf(setup.getBody().get("user")))
                .contains("username=root", "role=admin")
                .doesNotContain("password");
        assertThat(post(
                                anonymous,
                                "/api/v1/auth/setup",
                                Map.of("username", "evil", "password", "correct horse battery"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(anonymous.get().uri("/api/v1/auth/status").retrieve().body(MAP))
                .containsEntry("setupRequired", false);
    }

    @Test
    @Order(2)
    void loginLockoutAndTokens() {
        RestClient admin = client(adminToken);
        Map<String, Object> alice = post(
                        admin, "/api/v1/users", Map.of("username", "alice", "password", "alice-password-1"))
                .getBody();
        aliceId = (String) alice.get("id");
        post(admin, "/api/v1/users", Map.of("username", "bob", "password", "bob-password-12"));
        assertThat(post(admin, "/api/v1/users", Map.of("username", "alice", "password", "alice-password-1"))
                        .getStatusCode()
                        .value())
                .isEqualTo(409);

        RestClient anonymous = client(null);
        assertThat(post(anonymous, "/api/v1/auth/login", Map.of("username", "alice", "password", "wrong-password"))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        aliceToken = (String)
                post(anonymous, "/api/v1/auth/login", Map.of("username", "ALICE", "password", "alice-password-1"))
                        .getBody()
                        .get("token");
        bobToken =
                (String) post(anonymous, "/api/v1/auth/login", Map.of("username", "bob", "password", "bob-password-12"))
                        .getBody()
                        .get("token");
        for (int i = 0; i < 5; i++) {
            post(anonymous, "/api/v1/auth/login", Map.of("username", "bob", "password", "nope-nope-nope"));
        }
        assertThat(post(anonymous, "/api/v1/auth/login", Map.of("username", "bob", "password", "bob-password-12"))
                        .getBody()
                        .get("detail"))
                .asString()
                .contains("too many failed attempts");

        RestClient aliceApi = client(aliceToken);
        assertThat(aliceApi.get().uri("/api/v1/me").retrieve().body(MAP).toString())
                .contains("username=alice");
        Map<String, Object> pat =
                post(aliceApi, "/api/v1/me/tokens", Map.of("name", "ci")).getBody();
        String personal = (String) pat.get("token");
        assertThat(client(personal)
                        .get()
                        .uri("/api/v1/me")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(aliceApi.get().uri("/api/v1/me/tokens").retrieve().body(LIST))
                .hasSize(2)
                .allSatisfy(t -> assertThat(t).doesNotContainKey("token").doesNotContainKey("tokenHash"));
        String patId = (String) ((Map<?, ?>) pat.get("metadata")).get("id");
        assertThat(aliceApi.delete()
                        .uri("/api/v1/me/tokens/{id}", patId)
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(client(personal)
                        .get()
                        .uri("/api/v1/me")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        // members cannot administer accounts
        assertThat(aliceApi.get()
                        .uri("/api/v1/users")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
    }

    @Test
    @Order(3)
    void projectRolesGovernRunsAndCatalog() {
        RestClient admin = client(adminToken);
        RestClient alice = client(aliceToken);
        RestClient bob = client(bobToken);
        put(admin, "/api/v1/projects/shop", Map.of("name", "Shop"), 200);
        put(admin, "/api/v1/projects/shop/members/" + aliceId, Map.of("role", "developer"), 200);
        put(admin, "/api/v1/projects/shop/members/bob", Map.of("role", "viewer"), 200);
        put(
                admin,
                "/api/v1/workflows/hello",
                Map.of(
                        "status",
                        "published",
                        "nodes",
                        List.of(Map.of("id", "a", "type", "script", "script", "echo hi"))),
                200);

        // members may not change the global catalog
        put(
                alice,
                "/api/v1/workflows/mine",
                Map.of("nodes", List.of(Map.of("id", "a", "type", "script", "script", "true"))),
                403);
        put(alice, "/api/v1/rules/r", Map.of("content", "x"), 403);
        put(alice, "/api/v1/projects/other", Map.of("name", "x"), 403);

        assertThat(post(bob, "/api/v1/runs", Map.of("workflowId", "hello", "projectId", "shop"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(post(alice, "/api/v1/runs", Map.of("workflowId", "hello"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        ResponseEntity<Map<String, Object>> started =
                post(alice, "/api/v1/runs", Map.of("workflowId", "hello", "projectId", "shop"));
        assertThat(started.getStatusCode().value()).isEqualTo(201);
        String runId = (String) started.getBody().get("id");
        assertThat(started.getBody().toString()).contains("triggeredBy=alice");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(admin.get()
                                .uri("/api/v1/runs/{id}", runId)
                                .retrieve()
                                .body(MAP)
                                .get("status")));

        // the viewer reads but cannot steer; the admin's own global run stays invisible to both
        assertThat(bob.get()
                        .uri("/api/v1/runs/{id}", runId)
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(post(bob, "/api/v1/runs/" + runId + "/retry", Map.of())
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        String adminRun = (String) post(admin, "/api/v1/runs", Map.of("workflowId", "hello"))
                .getBody()
                .get("id");
        assertThat(bob.get()
                        .uri("/api/v1/runs/{id}", adminRun)
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        List<Map<String, Object>> bobRuns =
                bob.get().uri("/api/v1/runs").retrieve().body(LIST);
        assertThat(bobRuns).extracting(r -> r.get("id")).contains(runId).doesNotContain(adminRun);
        assertThat(admin.get().uri("/api/v1/runs").retrieve().body(LIST))
                .extracting(r -> r.get("id"))
                .contains(runId, adminRun);
        assertThat(client(null)
                        .get()
                        .uri("/api/v1/runs/{id}/stream?access_token=" + bobToken, adminRun)
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);

        // repositories of a project are managed by its maintainers
        put(
                alice,
                "/api/v1/repositories/shop-app",
                Map.of("cloneUrl", "https://github.com/acme/shop.git", "projectId", "shop"),
                403);
        put(admin, "/api/v1/projects/shop/members/" + aliceId, Map.of("role", "maintainer"), 200);
        put(
                alice,
                "/api/v1/repositories/shop-app",
                Map.of("cloneUrl", "https://github.com/acme/shop.git", "projectId", "shop"),
                200);
        assertThat(alice.get().uri("/api/v1/projects").retrieve().body(LIST))
                .extracting(p -> p.get("id"))
                .containsExactly("shop");
    }

    @Test
    @Order(4)
    void passwordResetEndsSessionsAndTheLastAdminIsProtected() {
        RestClient admin = client(adminToken);
        put(admin, "/api/v1/users/" + aliceId, Map.of("password", "a-brand-new-pass"), 200);
        assertThat(client(aliceToken)
                        .get()
                        .uri("/api/v1/me")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        String rootId = (String)
                ((Map<?, ?>) admin.get().uri("/api/v1/me").retrieve().body(MAP).get("user")).get("id");
        put(admin, "/api/v1/users/" + rootId, Map.of("role", "member"), 409);
        assertThat(admin.delete()
                        .uri("/api/v1/users/{id}", rootId)
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(409);
        // the configured service token keeps working as an administrator
        assertThat(client(SERVICE)
                        .get()
                        .uri("/api/v1/users")
                        .retrieve()
                        .toEntity(String.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(client(adminToken)
                        .post()
                        .uri("/api/v1/auth/logout")
                        .retrieve()
                        .toBodilessEntity()
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(client(adminToken)
                        .get()
                        .uri("/api/v1/me")
                        .retrieve()
                        .toEntity(MAP)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
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
