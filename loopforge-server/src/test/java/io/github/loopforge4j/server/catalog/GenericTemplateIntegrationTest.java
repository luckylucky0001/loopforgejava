package io.github.loopforge4j.server.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

/** Project-scoped roles, rules and skills, and generic SOP templates adopted into a project. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenericTemplateIntegrationTest {

    static final String TOKEN = "generic-template-token-0123456789-abc";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-generic-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:generic-templates;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
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

    RestClient member(RestClient admin, String name, String project, String role) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/" + project + "/members/" + created.get("id"), Map.of("role", role), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return client((String) login.get("token"));
    }

    static Map<String, Object> role(String name, String prompt, Object projectId, List<String> rules) {
        Map<String, Object> role =
                new java.util.HashMap<>(Map.of("name", name, "prompt", prompt, "executorId", "mock", "rules", rules));
        if (projectId != null) {
            role.put("projectId", projectId);
        }
        return role;
    }

    static Map<String, Object> sop(String project, String agent, boolean generic) {
        Map<String, Object> sop = new java.util.HashMap<>(Map.of(
                "name",
                "通用后端交付",
                "status",
                "published",
                "generic",
                generic,
                "tags",
                List.of("backend"),
                "nodes",
                List.of(Map.of("id", "a", "type", "agent", "agentId", agent))));
        if (project != null) {
            sop.put("projectId", project);
        }
        return sop;
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectsAdoptTemplatesAndOwnTheirRoles() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        call(admin, "PUT", "/api/v1/projects/p2", Map.of("name", "P2"), 200);
        RestClient mia = member(admin, "mia", "p1", "maintainer");
        RestClient dave = member(admin, "dave", "p1", "developer");
        RestClient bob = member(admin, "bob", "p2", "maintainer");

        // shared assets stay with administrators
        call(admin, "PUT", "/api/v1/rules/r-shared", Map.of("name", "共享", "content", "先写测试"), 200);
        call(admin, "PUT", "/api/v1/agents/dev", role("开发", "写代码", null, List.of("r-shared")), 200);
        call(mia, "PUT", "/api/v1/agents/dev", role("开发", "改掉", null, List.of()), 403);
        call(mia, "PUT", "/api/v1/rules/r-shared", Map.of("name", "共享", "content", "x"), 403);

        // a template belongs to no project and is never run directly
        call(admin, "PUT", "/api/v1/workflows/tpl", sop("p1", "dev", true), 422);
        call(admin, "PUT", "/api/v1/workflows/tpl", sop(null, "dev", true), 200);
        call(admin, "POST", "/api/v1/runs", Map.of("workflowId", "tpl", "input", "x"), 422);
        call(mia, "PUT", "/api/v1/workflows/tpl2", sop(null, "dev", true), 403);

        List<Map<String, Object>> templates = call(dave, "GET", "/api/v1/workflow-templates", null, 200);
        assertThat(templates).singleElement().satisfies(t -> {
            assertThat(t).containsEntry("id", "tpl").containsEntry("sopLane", "backend");
            assertThat((List<Map<String, Object>>) t.get("roles"))
                    .singleElement()
                    .satisfies(r -> assertThat(r).containsEntry("id", "dev").containsEntry("name", "开发"));
        });
        call(dave, "GET", "/api/v1/workflow-templates/nope", null, 404);

        // adopting is for the project's maintainers
        call(dave, "POST", "/api/v1/workflow-templates/tpl/adopt", Map.of("projectId", "p1"), 403);
        call(mia, "POST", "/api/v1/workflow-templates/tpl/adopt", Map.of("projectId", "p2"), 403);
        Map<String, Object> adopted =
                call(mia, "POST", "/api/v1/workflow-templates/tpl/adopt", Map.of("projectId", "p1"), 201);
        assertThat(adopted)
                .containsEntry("id", "tpl-p1")
                .containsEntry("projectId", "p1")
                .containsEntry("status", "draft")
                .containsEntry("generic", false)
                .containsEntry("owner", "mia");
        assertThat((List<String>) adopted.get("tags")).contains("backend", "generic-source:tpl");
        assertThat((List<Map<String, Object>>) adopted.get("nodes"))
                .singleElement()
                .satisfies(n -> assertThat(n).containsEntry("agentId", "dev-p1"));
        call(mia, "POST", "/api/v1/workflow-templates/tpl/adopt", Map.of("projectId", "p1"), 409);

        // the role copy belongs to the project: its maintainers edit it, other projects never see it
        Map<String, Object> copy = call(dave, "GET", "/api/v1/agents/dev-p1", null, 200);
        assertThat(copy).containsEntry("projectId", "p1").containsEntry("prompt", "写代码");
        assertThat((List<String>) copy.get("rules")).containsExactly("r-shared");
        call(bob, "GET", "/api/v1/agents/dev-p1", null, 404);
        assertThat((List<Map<String, Object>>) call(bob, "GET", "/api/v1/agents", null, 200))
                .extracting(a -> a.get("id"))
                .contains("dev")
                .doesNotContain("dev-p1");
        call(dave, "PUT", "/api/v1/agents/dev-p1", role("开发", "项目版", "p1", List.of("r-shared")), 403);
        call(bob, "PUT", "/api/v1/agents/dev-p1", role("开发", "项目版", "p1", List.of("r-shared")), 403);
        call(mia, "PUT", "/api/v1/agents/dev-p1", role("开发", "项目版", "p2", List.of("r-shared")), 422);
        call(mia, "PUT", "/api/v1/agents/dev-p1", role("开发", "项目版", null, List.of("r-shared")), 422);
        call(mia, "PUT", "/api/v1/agents/dev-p1", role("开发", "项目版", "p1", List.of("r-shared")), 200);

        // re-adopting reuses the project's copy and keeps its edits
        Map<String, Object> again = call(
                mia,
                "POST",
                "/api/v1/workflow-templates/tpl/adopt",
                Map.of("projectId", "p1", "id", "tpl-p1-b", "name", "第二份"),
                201);
        assertThat(again).containsEntry("name", "第二份");
        assertThat((List<Map<String, Object>>) again.get("nodes"))
                .singleElement()
                .satisfies(n -> assertThat(n).containsEntry("agentId", "dev-p1"));
        assertThat((Map<String, Object>) call(mia, "GET", "/api/v1/agents/dev-p1", null, 200))
                .containsEntry("prompt", "项目版");
        Map<String, Object> inP2 =
                call(bob, "POST", "/api/v1/workflow-templates/tpl/adopt", Map.of("projectId", "p2"), 201);
        assertThat((List<Map<String, Object>>) inP2.get("nodes"))
                .singleElement()
                .satisfies(n -> assertThat(n).containsEntry("agentId", "dev-p2"));
        assertThat((Map<String, Object>) call(bob, "GET", "/api/v1/agents/dev-p2", null, 200))
                .containsEntry("prompt", "写代码");

        // project rules: maintainers own them, other projects cannot bind them
        call(dave, "PUT", "/api/v1/rules/r-p1", Map.of("name", "P1 规则", "content", "x", "projectId", "p1"), 403);
        call(mia, "PUT", "/api/v1/rules/r-p1", Map.of("name", "P1 规则", "content", "只改 p1", "projectId", "p1"), 200);
        call(bob, "GET", "/api/v1/rules/r-p1", null, 404);
        assertThat((List<Map<String, Object>>) call(bob, "GET", "/api/v1/rules", null, 200))
                .extracting(r -> r.get("id"))
                .containsExactly("r-shared");
        call(bob, "PUT", "/api/v1/agents/x-p2", role("X", "x", "p2", List.of("r-p1")), 422);
        call(mia, "PUT", "/api/v1/agents/x-p1", role("X", "x", "p1", List.of("r-p1", "r-shared")), 200);
        call(mia, "PUT", "/api/v1/skills/s-p1", Map.of("name", "技能", "content", "步骤", "projectId", "p1"), 200);
        call(bob, "GET", "/api/v1/skills/s-p1", null, 404);
        call(bob, "DELETE", "/api/v1/skills/s-p1", null, 403);

        // an SOP may use shared roles and its own project's, never another project's
        call(bob, "PUT", "/api/v1/workflows/wf-p2", sop("p2", "dev-p1", false), 422);
        call(bob, "PUT", "/api/v1/workflows/wf-p2", sop("p2", "dev", false), 200);
        call(admin, "PUT", "/api/v1/workflows/tpl3", sop(null, "dev-p1", true), 422);

        // the adopted copy runs with the project's role
        call(mia, "POST", "/api/v1/workflows/tpl-p1/publish", null, 200);
        Map<String, Object> run = call(
                dave, "POST", "/api/v1/runs", Map.of("workflowId", "tpl-p1", "projectId", "p1", "input", "做"), 201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "succeeded"
                        .equals(((Map<String, Object>) call(dave, "GET", "/api/v1/runs/" + run.get("id"), null, 200))
                                .get("status")));

        // deleting a project role is for its maintainers and refused while an SOP uses it
        call(bob, "DELETE", "/api/v1/agents/dev-p1", null, 403);
        call(mia, "DELETE", "/api/v1/agents/dev-p1", null, 409);
        call(mia, "DELETE", "/api/v1/agents/x-p1", null, 204);
    }
}
