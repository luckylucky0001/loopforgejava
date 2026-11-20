package io.github.loopforge4j.server.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.DockerfileDefinition;
import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

/** Sandbox profiles, environments and Dockerfiles defined in the catalog. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SandboxCatalogIntegrationTest {

    static final String TOKEN = "sandbox-catalog-token-0123456789-abcd";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-sandbox-catalog-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:sandbox-catalog;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
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

    static Map<String, Object> workflow(String profile, String script, String projectId) {
        Map<String, Object> w = new java.util.HashMap<>(Map.of(
                "name",
                "带工具",
                "status",
                "published",
                "sandboxProfileId",
                profile,
                "nodes",
                List.of(Map.of("id", "a", "type", "script", "script", script))));
        if (projectId != null) {
            w.put("projectId", projectId);
        }
        return w;
    }

    @Test
    @SuppressWarnings("unchecked")
    void runsUseTheProfilesTooling() {
        RestClient admin = client(TOKEN);
        call(
                admin,
                "PUT",
                "/api/v1/environments/tools",
                Map.of(
                        "name",
                        "工具",
                        "variables",
                        Map.of("GREETING", "hi"),
                        "pathEntries",
                        List.of(".tools/bin"),
                        "installScript",
                        "mkdir -p .tools/bin && printf '#!/bin/sh\\necho greet-$GREETING\\n' > .tools/bin/greet"
                                + " && chmod +x .tools/bin/greet"),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/sandboxes/with-tools",
                Map.of(
                        "name", "带工具的本地沙箱",
                        "driver", "local",
                        "environmentId", "tools",
                        "environment", Map.of("MODE", "profile")),
                200);
        List<Map<String, Object>> profiles = call(admin, "GET", "/api/v1/sandboxes", null, 200);
        assertThat(profiles)
                .singleElement()
                .satisfies(p -> assertThat(p)
                        .containsEntry("available", true)
                        .containsEntry("isolated", false)
                        .containsEntry("driver", "local"));

        call(admin, "PUT", "/api/v1/workflows/tooled", workflow("with-tools", "greet; echo mode=$MODE", null), 200);
        Map<String, Object> run =
                call(admin, "POST", "/api/v1/runs", Map.of("workflowId", "tooled", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of("succeeded", "failed")
                        .contains(((Map<String, Object>) call(admin, "GET", "/api/v1/runs/" + id, null, 200))
                                .get("status")));
        Map<String, Object> done = call(admin, "GET", "/api/v1/runs/" + id, null, 200);
        Map<String, Object> node = (Map<String, Object>) ((Map<String, Object>) done.get("nodes")).get("a");
        assertThat(done).as(() -> Json.write(node)).containsEntry("status", "succeeded");
        assertThat((String) node.get("output")).contains("greet-hi", "mode=profile");

        // validation
        call(admin, "PUT", "/api/v1/sandboxes/local", Map.of("driver", "local"), 422);
        call(admin, "PUT", "/api/v1/sandboxes/odd", Map.of("driver", "vm"), 422);
        call(admin, "PUT", "/api/v1/sandboxes/odd", Map.of("driver", "local", "environmentId", "ghost"), 422);
        call(admin, "PUT", "/api/v1/sandboxes/odd", Map.of("driver", "local", "dockerfileId", "x"), 422);
        call(admin, "PUT", "/api/v1/environments/bad", Map.of("variables", Map.of("BAD-NAME", "x")), 422);
        call(admin, "PUT", "/api/v1/environments/bad", Map.of("variables", Map.of("LOOPFORGE_RUN_ID", "x")), 422);
        call(admin, "PUT", "/api/v1/environments/bad", Map.of("pathEntries", List.of("../outside")), 422);
        call(
                admin,
                "PUT",
                "/api/v1/dockerfiles/bad",
                Map.of("content", "FROM alpine", "files", Map.of("../x", "y")),
                422);
        call(admin, "PUT", "/api/v1/dockerfiles/empty", Map.of("content", " "), 422);

        // what is in use cannot be deleted
        call(admin, "DELETE", "/api/v1/environments/tools", null, 409);
        call(admin, "DELETE", "/api/v1/sandboxes/with-tools", null, 409);

        // docker profiles count as isolated even before Docker is enabled here, but cannot run yet
        call(
                admin,
                "PUT",
                "/api/v1/dockerfiles/go",
                Map.of("content", "FROM golang:1.23\nRUN go version\n", "files", Map.of("conf/app.toml", "x=1")),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/sandboxes/go",
                Map.of("driver", "docker", "dockerfileId", "go", "readOnlyRootFs", true),
                200);
        call(admin, "DELETE", "/api/v1/dockerfiles/go", null, 409);
        assertThat((List<Map<String, Object>>) call(admin, "GET", "/api/v1/sandboxes", null, 200))
                .filteredOn(p -> "go".equals(p.get("id")))
                .singleElement()
                .satisfies(p -> assertThat(p).containsEntry("available", false).containsEntry("isolated", true));

        // members: catalog writes are for administrators; SOPs only on isolated profiles
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        Map<String, Object> mia =
                call(admin, "POST", "/api/v1/users", Map.of("username", "mia", "password", "mia-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + mia.get("id"), Map.of("role", "maintainer"), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", "mia", "password", "mia-password-1"),
                200);
        RestClient member = client((String) login.get("token"));
        call(member, "PUT", "/api/v1/environments/mine", Map.of("variables", Map.of()), 403);
        call(member, "GET", "/api/v1/environments", null, 200);
        call(member, "PUT", "/api/v1/workflows/member-local", workflow("with-tools", "true", "p1"), 403);
        call(member, "PUT", "/api/v1/workflows/member-go", workflow("go", "true", "p1"), 200);

        call(admin, "DELETE", "/api/v1/workflows/tooled", null, 204);
        call(admin, "DELETE", "/api/v1/sandboxes/with-tools", null, 204);
        call(admin, "DELETE", "/api/v1/environments/tools", null, 204);
    }

    @Test
    void imageTagsFollowTheContent() {
        DockerfileDefinition a = new DockerfileDefinition("Go_1.23", null, null, "FROM golang", Map.of("x", "1"), null);
        DockerfileDefinition sameContent =
                new DockerfileDefinition("Go_1.23", "other name", null, "FROM golang", Map.of("x", "1"), null);
        DockerfileDefinition changed =
                new DockerfileDefinition("Go_1.23", null, null, "FROM golang", Map.of("x", "2"), null);
        assertThat(DockerImages.tag(a)).startsWith("loopforge-env/go_1.23:").isEqualTo(DockerImages.tag(sameContent));
        assertThat(DockerImages.tag(changed)).isNotEqualTo(DockerImages.tag(a));
    }
}
