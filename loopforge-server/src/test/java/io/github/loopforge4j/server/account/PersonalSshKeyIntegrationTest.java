package io.github.loopforge4j.server.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.runtime.git.SshAgentTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/** Personal Git SSH keys: stored per host, never returned, served to the owner's runs by an agent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonalSshKeyIntegrationTest {

    static final String TOKEN = "personal-ssh-token-0123456789-abcdef";
    static final Path data;

    static {
        try {
            // short: the agent's socket lives in the run's workspace
            data = Files.createTempDirectory(Path.of("/tmp"), "lfk");
            Files.writeString(
                    data.resolve("known_hosts"),
                    "git.pinned.example ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl\n");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:personal-ssh;DB_CLOSE_DELAY=-1");
        registry.add("loopforge.workspace-root", () -> data.resolve("w").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.engine.scheduler-tick", () -> "50ms");
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.members-require-isolation", () -> "false");
        registry.add(
                "loopforge.git.ssh-known-hosts-file",
                () -> data.resolve("known_hosts").toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

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

    RestClient member(RestClient admin, String name) {
        Map<String, Object> created =
                call(admin, "POST", "/api/v1/users", Map.of("username", name, "password", name + "-password-1"), 201);
        call(admin, "PUT", "/api/v1/projects/p1/members/" + created.get("id"), Map.of("role", "developer"), 200);
        Map<String, Object> login = call(
                client("none"),
                "POST",
                "/api/v1/auth/login",
                Map.of("username", name, "password", name + "-password-1"),
                200);
        return client((String) login.get("token"));
    }

    @SuppressWarnings("unchecked")
    String probe(RestClient c) {
        Map<String, Object> run =
                call(c, "POST", "/api/v1/runs", Map.of("workflowId", "probe", "projectId", "p1", "input", "x"), 201);
        String id = (String) run.get("id");
        await().atMost(Duration.ofSeconds(30))
                .until(() -> List.of("succeeded", "failed")
                        .contains(
                                ((Map<String, Object>) call(c, "GET", "/api/v1/runs/" + id, null, 200)).get("status")));
        Map<String, Object> done = call(c, "GET", "/api/v1/runs/" + id, null, 200);
        assertThat(done.get("status")).isEqualTo("succeeded");
        return (String) ((Map<String, Object>) ((Map<String, Object>) done.get("nodes")).get("probe")).get("output");
    }

    @Test
    @SuppressWarnings("unchecked")
    void personalSshKeysServeOnlyTheirOwnersRuns() throws Exception {
        RestClient admin = client(TOKEN);
        call(admin, "PUT", "/api/v1/projects/p1", Map.of("name", "P1"), 200);
        RestClient erin = member(admin, "erin");
        RestClient frank = member(admin, "frank");
        String key = SshAgentTest.openSshKey();

        Map<String, Object> saved =
                call(erin, "PUT", "/api/v1/me/credentials/ssh:github.com", Map.of("privateKey", key), 200);
        assertThat((String) saved.get("hint")).startsWith("ssh-ed25519 SHA256:");
        assertThat((String) saved.get("publicKey")).startsWith("ssh-ed25519 AAAA");
        assertThat(saved).containsEntry("trusted", true).doesNotContainKey("privateKey");
        assertThat((Map<String, Object>) call(
                        erin, "PUT", "/api/v1/me/credentials/ssh:git.pinned.example", Map.of("privateKey", key), 200))
                .containsEntry("trusted", true);
        assertThat((Map<String, Object>) call(
                        erin, "PUT", "/api/v1/me/credentials/ssh:git.unknown.example", Map.of("privateKey", key), 200))
                .containsEntry("trusted", false);
        // a later save without the key keeps it
        call(erin, "PUT", "/api/v1/me/credentials/ssh:git.unknown.example", Map.of(), 200);

        // refused input
        call(erin, "PUT", "/api/v1/me/credentials/ssh:git.example.com", Map.of(), 422);
        call(erin, "PUT", "/api/v1/me/credentials/ssh:git.example.com:22", Map.of("privateKey", key), 422);
        call(erin, "PUT", "/api/v1/me/credentials/ssh:Bad_Host", Map.of("privateKey", key), 422);
        call(erin, "PUT", "/api/v1/me/credentials/ssh:git.example.com", Map.of("privateKey", "ssh-ed25519 AAAA"), 422);
        Map<String, Object> encrypted = call(
                erin,
                "PUT",
                "/api/v1/me/credentials/ssh:git.example.com",
                Map.of(
                        "privateKey",
                        "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n\nAAAA\n-----END RSA PRIVATE KEY-----"),
                422);
        assertThat(Json.write(encrypted)).contains("口令");

        // the key never leaves the server in clear text
        String listed = Json.write(call(erin, "GET", "/api/v1/me/credentials", null, 200));
        assertThat(listed).doesNotContain("PRIVATE KEY").contains("ssh:github.com");
        assertThat(jdbc.sql("SELECT secret FROM user_credentials")
                        .query(String.class)
                        .list())
                .allSatisfy(s -> assertThat(s).doesNotContain("OPENSSH"));

        call(
                admin,
                "PUT",
                "/api/v1/workflows/probe",
                Map.of(
                        "name", "probe",
                        "status", "published",
                        "projectId", "p1",
                        "nodes", List.of(Map.of("id", "probe", "type", "script", "script", """
                        if [ -z "$GIT_SSH_COMMAND" ]; then echo "no ssh"; exit 0; fi
                        eval "set -- $GIT_SSH_COMMAND"
                        sock=$(sed -n 's/^ *IdentityAgent "\\(.*\\)"$/\\1/p' "$3")
                        test -S "$sock" && echo "agent ok"
                        grep '^Host ' "$3"
                        """))),
                200);
        String mine = probe(erin);
        assertThat(mine)
                .contains("agent ok")
                .contains("Host git.pinned.example")
                .contains("Host github.com")
                .contains("Host git.unknown.example")
                .contains("Host * !git.pinned.example !git.unknown.example !github.com");
        // another person's run never gets erin's keys (and this deployment has no key of its own)
        assertThat(probe(frank)).contains("no ssh");

        call(erin, "DELETE", "/api/v1/me/credentials/ssh:git.unknown.example", null, 204);
        assertThat(probe(erin)).doesNotContain("git.unknown.example");
    }
}
