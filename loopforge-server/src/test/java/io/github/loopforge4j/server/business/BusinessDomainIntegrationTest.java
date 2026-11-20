package io.github.loopforge4j.server.business;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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

/** Business domains: who maintains them, project assignment and the delivery report. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessDomainIntegrationTest {

    static final String TOKEN = "business-token-0123456789-abcdefghi";
    static final Path data;

    static {
        try {
            data = Files.createTempDirectory("lf-business-it");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:business;DB_CLOSE_DELAY=-1");
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

    record Account(String id, RestClient client) {}

    Account user(RestClient admin, String name) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return new Account((String) created.get("id"), client((String) login.get("token")));
    }

    static Map<String, Object> domain(
            String id, String name, List<String> managers, List<String> viewers, boolean active) {
        Map<String, Object> d = new HashMap<>(
                Map.of("id", id, "name", name, "active", active, "managers", managers, "viewers", viewers));
        d.put("description", "说明");
        return d;
    }

    @Test
    @SuppressWarnings("unchecked")
    void domainsAreMaintainedAssignedAndReported() {
        RestClient admin = client(TOKEN);
        Account mgr = user(admin, "mgr");
        Account vic = user(admin, "vic");
        Account alice = user(admin, "alice");
        Account bob = user(admin, "bob");
        for (String p : List.of("p1", "p2", "p3")) {
            call(admin, "PUT", "/api/v1/projects/" + p, Map.of("name", p.toUpperCase()), 200);
        }
        call(admin, "PUT", "/api/v1/projects/p1/members/" + alice.id(), Map.of("role", "owner"), 200);
        call(admin, "PUT", "/api/v1/projects/p3/members/" + alice.id(), Map.of("role", "owner"), 200);
        call(admin, "PUT", "/api/v1/projects/p2/members/" + bob.id(), Map.of("role", "owner"), 200);

        // administrators create domains; managers maintain theirs within limits
        call(mgr.client(), "PUT", "/api/v1/business-domains", domain("trade", "交易", List.of(), List.of(), true), 403);
        call(admin, "PUT", "/api/v1/business-domains", domain("all", "全部", List.of(), List.of(), true), 422);
        call(admin, "PUT", "/api/v1/business-domains", domain("x", "X", List.of("ghost"), List.of(), true), 422);
        call(
                admin,
                "PUT",
                "/api/v1/business-domains",
                domain("x", "X", List.of(mgr.id()), List.of(mgr.id()), true),
                422);
        call(
                admin,
                "PUT",
                "/api/v1/business-domains",
                domain("trade", "交易", List.of(mgr.id()), List.of(vic.id()), true),
                200);
        call(admin, "PUT", "/api/v1/business-domains", domain("infra", "基础设施", List.of(), List.of(), true), 200);
        call(
                admin,
                "PUT",
                "/api/v1/business-domains?createOnly=true",
                domain("trade", "交易", List.of(), List.of(), true),
                409);
        call(admin, "PUT", "/api/v1/business-domains", domain("dup", "交易", List.of(), List.of(), true), 409);
        Map<String, Object> renamed = call(
                mgr.client(),
                "PUT",
                "/api/v1/business-domains",
                domain("trade", "交易中台", List.of(mgr.id()), List.of(vic.id(), bob.id()), true),
                200);
        assertThat(renamed).containsEntry("name", "交易中台");
        call(
                mgr.client(),
                "PUT",
                "/api/v1/business-domains",
                domain("trade", "交易中台", List.of(mgr.id()), List.of(), false),
                403);
        call(mgr.client(), "PUT", "/api/v1/business-domains", domain("infra", "基础设施", List.of(), List.of(), true), 403);
        Map<String, Object> listed = call(vic.client(), "GET", "/api/v1/business-domains", null, 200);
        assertThat((List<Map<String, Object>>) listed.get("items"))
                .extracting(i -> ((Map<String, Object>) i.get("domain")).get("id") + ":" + i.get("canView") + ":"
                        + i.get("canManage"))
                .containsExactlyInAnyOrder("infra:false:false", "trade:true:false");

        // owners set the first assignment; moving needs an administrator
        call(vic.client(), "PUT", "/api/v1/projects/p1/business-domain", Map.of("businessDomainId", "trade"), 403);
        assertThat((Map<String, Object>) call(
                        alice.client(),
                        "PUT",
                        "/api/v1/projects/p1/business-domain",
                        Map.of("businessDomainId", "trade", "reason", "交易链路"),
                        200))
                .containsEntry("businessDomainId", "trade");
        call(alice.client(), "PUT", "/api/v1/projects/p1/business-domain", Map.of("businessDomainId", "infra"), 403);
        call(bob.client(), "PUT", "/api/v1/projects/p2/business-domain", Map.of("businessDomainId", "infra"), 200);
        call(
                admin,
                "PUT",
                "/api/v1/projects/p2/business-domain",
                Map.of("businessDomainId", "trade", "expectedDomainId", "nope"),
                409);
        call(
                admin,
                "PUT",
                "/api/v1/projects/p2/business-domain",
                Map.of("businessDomainId", "trade", "expectedDomainId", "infra", "reason", "合并"),
                200);
        call(
                admin,
                "PUT",
                "/api/v1/projects/p2/business-domain",
                Map.of("businessDomainId", "infra", "expectedDomainId", "trade", "reason", "拆回"),
                200);
        List<Map<String, Object>> history =
                call(bob.client(), "GET", "/api/v1/projects/p2/business-domain/history", null, 200);
        assertThat(history)
                .extracting(h -> h.get("fromDomainId") + "→" + h.get("toDomainId"))
                .containsExactly("trade→infra", "infra→trade", "null→infra");
        call(vic.client(), "GET", "/api/v1/projects/p2/business-domain/history", null, 403);

        // a delivered requirement in p1
        call(
                admin,
                "PUT",
                "/api/v1/workflows/quick",
                Map.of(
                        "name",
                        "快",
                        "status",
                        "published",
                        "projectId",
                        "p1",
                        "nodes",
                        List.of(Map.of("id", "a", "type", "script", "script", "sleep 1"))),
                200);
        Map<String, Object> req = call(
                admin,
                "POST",
                "/api/v1/projects/p1/requirements",
                Map.of("title", "下单", "status", "todo", "workflowId", "quick"),
                201);
        call(admin, "POST", "/api/v1/projects/p1/requirements/" + req.get("number") + "/runs", Map.of(), 201);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> "in_review"
                        .equals(((Map<String, Object>) call(
                                        admin,
                                        "GET",
                                        "/api/v1/projects/p1/requirements/" + req.get("number"),
                                        null,
                                        200))
                                .get("status")));

        Map<String, Object> all =
                call(admin, "GET", "/api/v1/business-domain-report?grain=week&timeZone=Asia/Shanghai", null, 200);
        assertThat((List<Map<String, Object>>) all.get("projects"))
                .extracting(p -> p.get("projectId") + ":" + p.get("businessDomainId"))
                .containsExactlyInAnyOrder("p1:trade", "p2:infra", "p3:null");
        assertThat((List<Map<String, Object>>) all.get("businessDomains"))
                .filteredOn(d -> "trade".equals(d.get("id")))
                .singleElement()
                .satisfies(d -> assertThat(d)
                        .containsEntry("projectCount", 1)
                        .containsEntry("runs", 1)
                        .containsEntry("uniqueIssues", 1)
                        .containsEntry("delivered", 1));
        Map<String, Object> delivery = (Map<String, Object>) all.get("deliveryMetrics");
        assertThat(delivery)
                .containsEntry("completedIssues", 1)
                .containsEntry("cycleSamples", 1)
                .containsEntry("completionToMergeMissingIssues", 1);
        assertThat(((Number) delivery.get("averageCycleDurationMs")).longValue())
                .isPositive();
        assertThat((List<Object>) all.get("deliveryTrend")).hasSize(1);
        assertThat(((Map<String, Object>) all.get("summary")).get("totalRuns")).isEqualTo(1);

        // viewers see their domains; owners their unassigned projects
        Map<String, Object> vicAll = call(vic.client(), "GET", "/api/v1/business-domain-report", null, 200);
        assertThat((List<Map<String, Object>>) vicAll.get("projects"))
                .extracting(p -> p.get("projectId"))
                .containsExactly("p1");
        call(vic.client(), "GET", "/api/v1/business-domain-report?businessDomainId=infra", null, 403);
        call(vic.client(), "GET", "/api/v1/business-domain-report?businessDomainId=ghost", null, 404);
        Map<String, Object> aliceUnclassified =
                call(alice.client(), "GET", "/api/v1/business-domain-report?businessDomainId=unclassified", null, 200);
        assertThat((List<Map<String, Object>>) aliceUnclassified.get("projects"))
                .extracting(p -> p.get("projectId"))
                .containsExactly("p3");
        call(admin, "GET", "/api/v1/business-domain-report?grain=hour", null, 422);
    }

    @Test
    void deliveryMetricsSeparateCompletionAndMerge() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        Instant done = Instant.parse("2026-01-10T00:00:00Z");
        PullRequest after =
                new PullRequest("r", 1, "u1", "merged", "h", "b", null, done.plus(Duration.ofHours(4)), null);
        PullRequest before =
                new PullRequest("r", 2, "u2", "merged", "h", "b", null, done.minus(Duration.ofHours(1)), null);
        PullRequest unknown = new PullRequest("r", 3, "u3", "merged", "h", "b", null, null, null);
        List<BusinessDomainService.Work> work = List.of(
                new BusinessDomainService.Work(done, false, 3_600_000L, 600_000, 100, List.of(after, before), true),
                new BusinessDomainService.Work(
                        done.plus(Duration.ofDays(1)), true, 7_200_000L, 0, 50, List.of(unknown), false),
                new BusinessDomainService.Work(done.minus(Duration.ofDays(30)), false, 1L, 1, 999, List.of(), false),
                new BusinessDomainService.Work(null, false, null, 0, 0, List.of(), true));
        BusinessDomainService.DeliveryMetrics m = BusinessDomainService.metrics(work, from, to);
        assertThat(m.completedIssues()).isEqualTo(2);
        assertThat(m.estimatedCompletionIssues()).isEqualTo(1);
        assertThat(m.mergedMrs()).isEqualTo(2);
        assertThat(m.mergedMrsMissingTime()).isEqualTo(1);
        assertThat(m.completionToMergeSamples()).isEqualTo(1);
        assertThat(m.averageCompletionToMergeMs()).isEqualTo(4 * 3_600_000L);
        assertThat(m.mergedBeforeCompletionMrs()).isEqualTo(1);
        assertThat(m.mergedBeforeCompletionIssues()).isEqualTo(1);
        assertThat(m.cycleSamples()).isEqualTo(2);
        assertThat(m.p50CycleDurationMs()).isEqualTo(3_600_000L);
        assertThat(m.p95CycleDurationMs()).isEqualTo(7_200_000L);
        assertThat(m.executionSamples()).isEqualTo(1);
        assertThat(m.averageTokensPerCompletedIssue()).isEqualTo(75);
        assertThat(BusinessDomainService.bucket(
                        Instant.parse("2026-01-07T20:00:00Z"), "week", java.time.ZoneId.of("Asia/Shanghai")))
                .isEqualTo("2026-01-05");
    }
}
