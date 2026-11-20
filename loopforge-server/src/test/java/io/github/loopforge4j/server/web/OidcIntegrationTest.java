package io.github.loopforge4j.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.loopforge4j.core.json.Json;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** OpenID Connect sign-in against a fake identity provider that checks PKCE and signs id_tokens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcIntegrationTest {

    static final String TOKEN = "oidc-test-token-0123456789-abcdefgh";
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final HttpServer idp;
    static final KeyPair key;
    static final KeyPair rogue;
    static final Map<String, Map<String, String>> codes = new ConcurrentHashMap<>();
    /** Edits the claims of the next id_token (tests replace it). */
    static volatile UnaryOperator<Map<String, Object>> claimsEditor = c -> c;

    static volatile KeyPair signer;
    static volatile String subject = "sub-alice";
    static volatile String preferredUsername = "alice";

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            key = generator.generateKeyPair();
            rogue = generator.generateKeyPair();
            signer = key;
            idp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String issuer = "http://127.0.0.1:" + idp.getAddress().getPort();
            idp.createContext(
                    "/.well-known/openid-configuration",
                    ex -> reply(
                            ex,
                            Map.of(
                                    "issuer", issuer,
                                    "authorization_endpoint", issuer + "/authorize",
                                    "token_endpoint", issuer + "/token",
                                    "jwks_uri", issuer + "/jwks",
                                    "token_endpoint_auth_methods_supported", List.of("client_secret_basic"))));
            RSAPublicKey pub = (RSAPublicKey) key.getPublic();
            idp.createContext(
                    "/jwks",
                    ex -> reply(
                            ex,
                            Map.of(
                                    "keys",
                                    List.of(Map.of(
                                            "kty",
                                            "RSA",
                                            "kid",
                                            "k1",
                                            "use",
                                            "sig",
                                            "alg",
                                            "RS256",
                                            "n",
                                            B64.encodeToString(unsigned(pub.getModulus())),
                                            "e",
                                            B64.encodeToString(unsigned(pub.getPublicExponent())))))));
            idp.createContext("/token", ex -> {
                Map<String, String> form =
                        parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                Map<String, String> issued = codes.remove(form.get("code"));
                boolean ok = issued != null
                        && ("Basic " + Base64.getEncoder().encodeToString("lf-client:lf-secret".getBytes()))
                                .equals(auth)
                        && "authorization_code".equals(form.get("grant_type"))
                        && issued.get("redirect_uri").equals(form.get("redirect_uri"))
                        && issued.get("code_challenge").equals(challenge(form.get("code_verifier")));
                if (!ok) {
                    byte[] body = "{\"error\":\"invalid_grant\"}".getBytes();
                    ex.sendResponseHeaders(400, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                    return;
                }
                Map<String, Object> claims = new LinkedHashMap<>();
                long now = Instant.now().getEpochSecond();
                claims.put("iss", issuer);
                claims.put("sub", subject);
                claims.put("aud", "lf-client");
                claims.put("exp", now + 300);
                claims.put("iat", now);
                claims.put("nonce", issued.get("nonce"));
                claims.put("preferred_username", preferredUsername);
                claims.put("name", "Alice Example");
                claims.put("email", preferredUsername + "@example.com");
                claims.put("email_verified", true);
                reply(
                        ex,
                        Map.of(
                                "id_token",
                                jwt(claimsEditor.apply(claims)),
                                "access_token",
                                "at",
                                "token_type",
                                "Bearer"));
            });
            idp.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopIdp() {
        idp.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path data = Files.createTempDirectory("lf-oidc");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:oidc;DB_CLOSE_DELAY=-1");
        registry.add(
                "loopforge.workspace-root", () -> data.resolve("workspaces").toString());
        registry.add("loopforge.security.api-tokens", () -> TOKEN);
        registry.add("loopforge.demo-catalog", () -> "false");
        registry.add("loopforge.security.oidc.enabled", () -> "true");
        registry.add("loopforge.security.oidc.display-name", () -> "Example SSO");
        registry.add(
                "loopforge.security.oidc.issuer",
                () -> "http://127.0.0.1:" + idp.getAddress().getPort());
        registry.add("loopforge.security.oidc.client-id", () -> "lf-client");
        registry.add("loopforge.security.oidc.client-secret", () -> "lf-secret");
    }

    @LocalServerPort
    int port;

    final HttpClient http =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void reset() {
        claimsEditor = c -> c;
        signer = key;
        subject = "sub-alice";
        preferredUsername = "alice";
    }

    /** Runs the browser part of a sign-in; returns the console fragment the server redirects to. */
    String signIn(UnaryOperator<String> tamperState) throws Exception {
        HttpResponse<String> start = http.send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port + "/api/v1/auth/oidc/start?client_nonce=" + TAB_NONCE))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(start.statusCode()).isEqualTo(302);
        String cookie = start.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "SameSite=Lax", "Path=/api/v1/auth/oidc");
        URI authorize = URI.create(start.headers().firstValue("Location").orElseThrow());
        Map<String, String> query = parse(authorize.getRawQuery());
        assertThat(query).containsEntry("code_challenge_method", "S256").containsEntry("client_id", "lf-client");
        String code = "code-" + System.nanoTime();
        codes.put(code, query);
        HttpResponse<String> callback = http.send(
                HttpRequest.newBuilder(URI.create(query.get("redirect_uri") + "?code=" + code + "&state="
                                + tamperState.apply(query.get("state"))))
                        .header("Cookie", cookie.split(";")[0])
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        return URLDecoder.decode(callback.headers().firstValue("Location").orElseThrow(), StandardCharsets.UTF_8);
    }

    static final String TAB_NONCE = "tab-nonce-0123456789abcdef";

    String token(String location) {
        assertThat(location).startsWith("/#/sso?token=").endsWith("&nonce=" + TAB_NONCE);
        return location.substring("/#/sso?token=".length(), location.indexOf("&nonce="));
    }

    Map<String, Object> me(String token) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me"))
                        .header("Authorization", "Bearer " + token)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return (Map<String, Object>) Json.read(response.body(), Map.class).get("user");
    }

    @Test
    void signInProvisionsAMemberOnceAndRejectsTamperedFlows() throws Exception {
        String status = http.send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/status"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
        assertThat(status).contains("\"oidc\":{", "Example SSO");
        // a sign-in not started by a console tab (no nonce to echo) is refused
        HttpResponse<String> bare = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/oidc/start"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bare.headers().firstValue("Location").orElseThrow()).isEqualTo("/#/sso?error=state");

        Map<String, Object> first = me(token(signIn(s -> s)));
        assertThat(first)
                .containsEntry("username", "alice")
                .containsEntry("role", "member")
                .containsEntry("email", "alice@example.com");
        // the same identity signs in to the same account
        assertThat(me(token(signIn(s -> s)))).containsEntry("id", first.get("id"));

        assertThat(signIn(s -> s + "x")).isEqualTo("/#/sso?error=state");
        claimsEditor = c -> {
            c.put("nonce", "replayed");
            return c;
        };
        assertThat(signIn(s -> s)).isEqualTo("/#/sso?error=token");
        claimsEditor = c -> {
            c.put("aud", "another-client");
            return c;
        };
        assertThat(signIn(s -> s)).isEqualTo("/#/sso?error=token");
        claimsEditor = c -> {
            c.put("exp", Instant.now().getEpochSecond() - 600);
            return c;
        };
        assertThat(signIn(s -> s)).isEqualTo("/#/sso?error=token");
        claimsEditor = c -> c;
        signer = rogue;
        assertThat(signIn(s -> s)).isEqualTo("/#/sso?error=token");
    }

    @Test
    void aProviderUsernameNeverTakesOverALocalAccount() throws Exception {
        HttpResponse<String> created = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users"))
                        .header("Authorization", "Bearer " + TOKEN)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                Json.write(Map.of("username", "bob", "password", "bob-password-123", "role", "admin"))))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        subject = "sub-mallory";
        preferredUsername = "bob";
        Map<String, Object> user = me(token(signIn(s -> s)));
        assertThat(user.get("username")).asString().startsWith("bob-").isNotEqualTo("bob");
        assertThat(user).containsEntry("role", "member");
    }

    // ------------------------------------------------------------------ fake provider helpers

    static String jwt(Map<String, Object> claims) {
        try {
            String header = B64.encodeToString(Json.write(Map.of("alg", "RS256", "kid", "k1", "typ", "JWT"))
                    .getBytes());
            String payload = B64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + B64.encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String challenge(String verifier) {
        try {
            return B64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            return "";
        }
    }

    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    static Map<String, String> parse(String query) {
        Map<String, String> out = new HashMap<>();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static void reply(com.sun.net.httpserver.HttpExchange exchange, Object body) throws java.io.IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
