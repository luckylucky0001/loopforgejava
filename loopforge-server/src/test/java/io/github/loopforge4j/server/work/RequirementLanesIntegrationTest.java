package io.github.loopforge4j.server.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
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

/** Requirement SOP lanes: playbooks, chains with handoff, lanes side by side and the snapshot. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequirementLanesIntegrationTest {

    static final String TOKEN = "lanes-token-0123456789-abcdefghijk";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-lanes-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:lanes;DB_CLOSE_DELAY=-1");
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

    RestClient member(RestClient admin, String name, String role) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + created.get("id"), Map.of("role", role), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return client((String) login.get("token"));
    }

    static Map<String, Object> workflow(
            String name, String lane, List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        Map<String, Object> w =
                new HashMap<>(Map.of("name", name, "status", "published", "projectId", "p1", "nodes", nodes));
        if (lane != null) {
            w.put("sopLane", lane);
        }
        if (edges != null) {
            w.put("edges", edges);
        }
        return w;
    }

    static Map<String, Object> script(String id, String name, String script) {
        return Map.of("id", id, "name", name, "type", "script", "script", script);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> lane(Map<String, Object> snapshot, String workflowId) {
        return ((List<Map<String, Object>>) snapshot.get("lanes"))
                .stream()
                        .filter(l -> workflowId.equals(l.get("workflowId")))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError(workflowId + " not in " + Json.write(snapshot.get("lanes"))));
    }

    String runStatus(RestClient c, Object runId) {
        return String.valueOf(((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + runId, null, 200)).get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void lanesOfAChainRunSideBySideAndHandOver() {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        RestClient mia = member(admin, "mia", "maintainer");
        RestClient dev = member(admin, "dev1", "developer");
        RestClient vic = member(admin, "vic", "viewer");
        call(
                admin,
                "PUT",
                "/api/v1/workflows/product",
                workflow("产品交付", "product", List.of(script("prd", "写 PRD", "echo prd > prd.md")), null),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/backend",
                workflow(
                        "后端开发",
                        null,
                        List.of(script("impl", "实现", "sleep 2"), script("test", "测试", "true")),
                        List.of(Map.of("source", "impl", "target", "test"))),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/frontend",
                workflow(
                        "前端开发",
                        "frontend",
                        List.of(script("ui", "页面", "true"), Map.of("id", "review", "name", "验收", "type", "approval")),
                        List.of(Map.of("source", "ui", "target", "review"))),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/workflows/other",
                workflow("杂项", null, List.of(script("x", "x", "true")), null),
                200);
        assertThat((Map<String, Object>) call(admin, "GET", "/api/v1/workflows/product", null, 200))
                .containsEntry("sopLane", "product");

        // playbooks: maintainers define them, SOPs must exist
        List<Map<String, Object>> books = List.of(
                Map.of(
                        "name",
                        "完整需求",
                        "default",
                        true,
                        "bindings",
                        List.of(
                                Map.of("workflowId", "product"),
                                Map.of(
                                        "workflowId",
                                        "backend",
                                        "dependsOn",
                                        List.of("sop-product"),
                                        "handoff",
                                        List.of("prd.md"),
                                        "assignee",
                                        "dev1"),
                                Map.of("workflowId", "frontend", "dependsOn", List.of("sop-product", "ghost")))),
                Map.of("name", "仅后端", "bindings", List.of(Map.of("workflowId", "backend"))));
        call(dev, "PUT", "/api/v1/projects/p1/playbooks", books, 403);
        call(
                mia,
                "PUT",
                "/api/v1/projects/p1/playbooks",
                List.of(Map.of("name", "坏", "bindings", List.of(Map.of("workflowId", "ghost")))),
                422);
        List<Map<String, Object>> saved = call(mia, "PUT", "/api/v1/projects/p1/playbooks", books, 200);
        assertThat(saved)
                .extracting(b -> b.get("id") + ":" + b.get("default"))
                .containsExactly("pb-完整需求:true", "pb-仅后端:false");
        assertThat((List<Map<String, Object>>) saved.getFirst().get("bindings"))
                .extracting(b -> b.get("id") + "#" + b.get("order") + b.get("dependsOn"))
                .containsExactly("sop-product#1[]", "sop-backend#2[sop-product]", "sop-frontend#3[sop-product]");

        // a new requirement starts with the default chain
        Map<String, Object> req =
                call(dev, "POST", "/api/v1/projects/p1/requirements", Map.of("title", "登录页", "status", "todo"), 201);
        assertThat(req).containsEntry("workflowId", "product").containsEntry("playbookId", "pb-完整需求");
        String base = "/api/v1/projects/p1/requirements/" + req.get("number");
        Map<String, Object> before = call(vic, "GET", base + "/snapshot", null, 200);
        assertThat((List<Map<String, Object>>) before.get("lanes"))
                .extracting(l -> l.get("workflowId") + ":" + l.get("lane") + ":" + l.get("status"))
                .containsExactly(
                        "product:product:not_started", "backend:other:not_started", "frontend:frontend:not_started");
        assertThat((List<Map<String, Object>>) lane(before, "backend").get("stages"))
                .extracting(s -> s.get("nodeId") + ":" + s.get("status"))
                .containsExactly("impl:pending", "test:pending");
        assertThat((Map<String, Object>) before.get("overall")).containsEntry("status", "not_started");

        // the default lane, then the lanes it hands over to, side by side; a lane starts once
        List<Integer> codes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<Thread> racers = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            racers.add(Thread.ofVirtual()
                    .start(() -> codes.add(dev.post()
                            .uri(base + "/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of())
                            .retrieve()
                            .toBodilessEntity()
                            .getStatusCode()
                            .value())));
        }
        for (Thread t : racers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        assertThat(codes).containsOnly(201, 409).filteredOn(c -> c == 201).hasSize(1);
        Map<String, Object> product = Map.of(
                "id",
                ((List<String>) ((Map<String, Object>) call(vic, "GET", base, null, 200)).get("runIds")).getFirst());
        await().atMost(Duration.ofSeconds(30))
                .until(() -> runStatus(dev, product.get("id")).equals("succeeded"));
        call(dev, "POST", base + "/runs", Map.of("workflowId", "product"), 409);
        call(dev, "POST", base + "/runs", Map.of("workflowId", "backend", "rerun", true), 422);
        Map<String, Object> backend = call(dev, "POST", base + "/runs", Map.of("workflowId", "backend"), 201);
        assertThat((String) backend.get("input")).contains("前序 SOP 交接", "- product → 预期产物：prd.md", "不要重做前序 SOP");
        call(dev, "POST", base + "/runs", Map.of("workflowId", "backend", "rerun", true), 409);
        Map<String, Object> frontend = call(dev, "POST", base + "/runs", Map.of("workflowId", "frontend"), 201);
        assertThat((String) frontend.get("input")).contains("- product");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> runStatus(dev, frontend.get("id")).equals("waiting"));
        Map<String, Object> during = call(vic, "GET", base + "/snapshot", null, 200);
        assertThat(lane(during, "product")).containsEntry("status", "succeeded").containsEntry("runCount", 1);
        assertThat(lane(during, "frontend")).containsEntry("status", "waiting");
        assertThat((List<Map<String, Object>>) lane(during, "frontend").get("stages"))
                .extracting(s -> s.get("nodeId") + ":" + s.get("status"))
                .containsExactly("ui:succeeded", "review:waiting_approval");
        assertThat(lane(during, "backend"))
                .containsEntry("assignee", "dev1")
                .containsEntry("handoff", List.of("prd.md"));
        assertThat((Map<String, Object>) during.get("overall")).containsEntry("runCount", 3);
        assertThat((Map<String, Object>) call(vic, "GET", base, null, 200)).containsEntry("status", "in_progress");

        // all lanes done: the requirement is up for review
        await().atMost(Duration.ofSeconds(30))
                .until(() -> runStatus(dev, backend.get("id")).equals("succeeded"));
        call(dev, "POST", "/api/v1/runs/" + frontend.get("id") + "/nodes/review/approve", Map.of(), 200);
        await().atMost(Duration.ofSeconds(30))
                .until(() ->
                        "in_review".equals(((Map<String, Object>) call(vic, "GET", base, null, 200)).get("status")));
        Map<String, Object> after = call(vic, "GET", base + "/snapshot", null, 200);
        assertThat((Map<String, Object>) after.get("overall"))
                .containsEntry("status", "succeeded")
                .containsEntry("succeededLanes", 3)
                .containsEntry("activeLanes", 0);
        assertThat((List<Map<String, Object>>) after.get("stages"))
                .extracting(s -> s.get("key") + ":" + s.get("status"))
                .containsExactly("prd:succeeded");

        // an SOP outside the chain is its own (orphan) lane
        Map<String, Object> other = call(dev, "POST", base + "/runs", Map.of("workflowId", "other"), 201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> runStatus(dev, other.get("id")).equals("succeeded"));
        assertThat(lane(call(vic, "GET", base + "/snapshot", null, 200), "other"))
                .containsEntry("orphan", true);

        // switching the playbook replaces the chain; viewers cannot change it
        call(vic, "PUT", base + "/sops", Map.of("playbookId", "pb-仅后端"), 403);
        call(dev, "PUT", base + "/sops", Map.of("playbookId", "nope"), 422);
        Map<String, Object> switched = call(dev, "PUT", base + "/sops", Map.of("playbookId", "pb-仅后端"), 200);
        assertThat(switched).containsEntry("workflowId", "backend").containsEntry("playbookId", "pb-仅后端");
        Map<String, Object> sops = call(vic, "GET", base + "/sops", null, 200);
        assertThat((List<Map<String, Object>>) sops.get("bindings"))
                .extracting(b -> b.get("workflowId"))
                .containsExactly("backend");
        assertThat((List<Object>) sops.get("playbooks")).hasSize(2);
        Map<String, Object> custom = call(
                dev,
                "PUT",
                base + "/sops",
                Map.of(
                        "bindings",
                        List.of(
                                Map.of("workflowId", "frontend", "order", 5),
                                Map.of("workflowId", "product", "order", 1))),
                200);
        assertThat(custom).containsEntry("workflowId", "product");
    }
}
