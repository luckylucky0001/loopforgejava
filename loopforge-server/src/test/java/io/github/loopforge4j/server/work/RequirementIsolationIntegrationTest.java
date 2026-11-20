package io.github.loopforge4j.server.work;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Members cannot run a non-isolated SOP through a requirement's chain. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequirementIsolationIntegrationTest {

    static final String TOKEN = "isolation-token-0123456789-abcdefgh";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-req-isolation-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:req-isolation;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
    }

    @LocalServerPort
    int port;

    RestClient client(String token) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {})
                .build();
    }

    @SuppressWarnings("unchecked")
    <T> T call(RestClient c, String method, String path, Object body, int status) {
        var spec = c.method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        ResponseEntity<String> r = spec.retrieve().toEntity(String.class);
        assertThat(r.getStatusCode().value())
                .as(method + " " + path + ": " + r.getBody())
                .isEqualTo(status);
        return r.getBody() == null || r.getBody().isBlank() ? null : (T) Json.read(r.getBody(), Object.class);
    }

    @Test
    void theChainsFirstStepIsCheckedForIsolation() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        Map<String, Object> dev =
                call(admin, "POST", "/api/v1/users", Map.of("username", "dev", "password", "dev-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + dev.get("id"), Map.of("role", "developer"), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", "dev", "password", "dev-password-1"),
                200);
        RestClient developer = client((String) login.get("token"));
        // an administrator's SOP on the server's own sandbox
        call(
                admin,
                "PUT",
                "/api/v1/workflows/host",
                Map.of(
                        "name",
                        "宿主",
                        "status",
                        "published",
                        "projectId",
                        "p1",
                        "nodes",
                        List.of(Map.of("id", "a", "type", "script", "script", "id"))),
                200);
        Map<String, Object> req = call(
                developer,
                "POST",
                "/api/v1/projects/p1/requirements",
                Map.of("title", "x", "sopBindings", List.of(Map.of("workflowId", "host"))),
                201);
        String base = "/api/v1/projects/p1/requirements/" + req.get("number");
        // clearing the default SOP does not hide the chain's first step
        call(developer, "PUT", base, Map.of("title", "x"), 200);
        call(developer, "POST", base + "/runs", Map.of(), 403);
        call(developer, "POST", base + "/runs", Map.of("workflowId", "host"), 403);
        assertThat((List<Object>) ((Map<String, Object>) call(admin, "GET", base, null, 200)).get("runIds"))
                .isEmpty();
    }
}
