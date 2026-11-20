package io.github.loopforge4j.server.account;

import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The OpenID Connect relying party, JDK only: provider discovery, the code exchange with PKCE and
 * id_token verification (RS256 / ES256 against the provider's JWKS, issuer, audience, expiry,
 * nonce).
 */
final class OidcClient {

    /** Sign-in failed; the message is safe to log, the code is what the console shows. */
    static final class OidcException extends Exception {
        final String code;

        OidcException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    record Provider(String issuer, URI authorization, URI token, URI jwks, boolean basicAuth) {}

    private static final Duration SKEW = Duration.ofSeconds(60);
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    private final String issuer;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private volatile Provider provider;
    private volatile Instant providerLoaded = Instant.EPOCH;
    private final Map<String, PublicKey> keys = new HashMap<>();
    private Instant keysLoaded = Instant.EPOCH;

    OidcClient(String issuer, String clientId, String clientSecret, Clock clock) {
        this.issuer = issuer.strip().replaceAll("/+$", "");
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    Provider provider() throws OidcException {
        Provider p = provider;
        if (p != null && providerLoaded.isAfter(clock.instant().minus(Duration.ofHours(1)))) {
            return p;
        }
        JsonNode doc = getJson(URI.create(issuer + "/.well-known/openid-configuration"));
        String declared = doc.path("issuer").asString("").replaceAll("/+$", "");
        if (!issuer.equals(declared)) {
            throw new OidcException("provider", "discovery issuer " + declared + " does not match " + issuer);
        }
        boolean basic = true;
        JsonNode methods = doc.get("token_endpoint_auth_methods_supported");
        if (methods != null && methods.isArray()) {
            basic = false;
            for (JsonNode m : methods) {
                basic |= "client_secret_basic".equals(m.asString(""));
            }
        }
        p = new Provider(
                doc.path("issuer").asString(),
                https(doc.path("authorization_endpoint").asString("")),
                https(doc.path("token_endpoint").asString("")),
                https(doc.path("jwks_uri").asString("")),
                basic);
        provider = p;
        providerLoaded = clock.instant();
        return p;
    }

    /** Where the browser goes to sign in. */
    URI authorizationUrl(String redirectUri, String scopes, String state, String nonce, String challenge)
            throws OidcException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", redirectUri);
        query.put("scope", scopes);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", challenge);
        query.put("code_challenge_method", "S256");
        URI base = provider().authorization();
        return URI.create(base + (base.getQuery() == null ? "?" : "&") + form(query));
    }

    /** Exchanges the code and returns the verified id_token claims. */
    JsonNode exchange(String code, String redirectUri, String verifier, String nonce) throws OidcException {
        Provider p = provider();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", verifier);
        HttpRequest.Builder request = HttpRequest.newBuilder(p.token())
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json");
        if (p.basicAuth()) {
            String credentials = URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":"
                    + URLEncoder.encode(clientSecret == null ? "" : clientSecret, StandardCharsets.UTF_8);
            request.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else {
            form.put("client_id", clientId);
            if (clientSecret != null) {
                form.put("client_secret", clientSecret);
            }
        }
        JsonNode reply = send(
                request.POST(HttpRequest.BodyPublishers.ofString(form(form))).build());
        String idToken = reply.path("id_token").asString("");
        if (idToken.isEmpty()) {
            throw new OidcException("token", "the provider returned no id_token");
        }
        return verify(idToken, nonce);
    }

    /** Signature, issuer, audience, time and nonce of an id_token. */
    JsonNode verify(String jwt, String nonce) throws OidcException {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new OidcException("token", "id_token is not a signed JWT");
        }
        JsonNode header = decode(parts[0]);
        JsonNode claims = decode(parts[1]);
        String alg = header.path("alg").asString("");
        if (!alg.equals("RS256") && !alg.equals("ES256")) {
            throw new OidcException("token", "unsupported id_token algorithm " + alg);
        }
        PublicKey key = key(header.path("kid").asString(""), alg);
        try {
            Signature signature =
                    Signature.getInstance(alg.equals("RS256") ? "SHA256withRSA" : "SHA256withECDSAinP1363Format");
            signature.initVerify(key);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(B64URL.decode(parts[2]))) {
                throw new OidcException("token", "id_token signature is invalid");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new OidcException("token", "id_token signature is invalid");
        }
        if (!provider().issuer().equals(claims.path("iss").asString(""))) {
            throw new OidcException("token", "id_token issuer mismatch");
        }
        JsonNode aud = claims.get("aud");
        boolean audience = aud != null && (aud.isArray() ? contains(aud, clientId) : clientId.equals(aud.asString("")));
        if (!audience) {
            throw new OidcException("token", "id_token audience mismatch");
        }
        if (aud.isArray()
                && aud.size() > 1
                && !clientId.equals(claims.path("azp").asString(clientId))) {
            throw new OidcException("token", "id_token authorized party mismatch");
        }
        Instant now = clock.instant();
        if (!claims.has("exp")
                || Instant.ofEpochSecond(claims.path("exp").asLong()).isBefore(now.minus(SKEW))) {
            throw new OidcException("token", "id_token expired");
        }
        if (claims.has("iat")
                && Instant.ofEpochSecond(claims.path("iat").asLong()).isAfter(now.plus(SKEW))) {
            throw new OidcException("token", "id_token issued in the future");
        }
        if (nonce == null || !nonce.equals(claims.path("nonce").asString(""))) {
            throw new OidcException("token", "id_token nonce mismatch");
        }
        if (claims.path("sub").asString("").isBlank()) {
            throw new OidcException("token", "id_token has no subject");
        }
        return claims;
    }

    private synchronized PublicKey key(String kid, String alg) throws OidcException {
        PublicKey key = keys.get(kid + "|" + alg);
        // a key the cache does not know yet may be a rotation: reload at most once a minute
        if (key == null && keysLoaded.isBefore(clock.instant().minusSeconds(60))) {
            loadKeys();
            key = keys.get(kid + "|" + alg);
        }
        if (key == null) {
            throw new OidcException("token", "unknown id_token signing key");
        }
        return key;
    }

    private void loadKeys() throws OidcException {
        JsonNode jwks = getJson(provider().jwks());
        keys.clear();
        for (JsonNode jwk : jwks.path("keys")) {
            String use = jwk.path("use").asString("sig");
            if (!"sig".equals(use)) {
                continue;
            }
            String kid = jwk.path("kid").asString("");
            try {
                if ("RSA".equals(jwk.path("kty").asString(""))) {
                    keys.put(
                            kid + "|RS256",
                            KeyFactory.getInstance("RSA")
                                    .generatePublic(new RSAPublicKeySpec(
                                            unsigned(jwk.path("n").asString("")),
                                            unsigned(jwk.path("e").asString("")))));
                } else if ("EC".equals(jwk.path("kty").asString(""))
                        && "P-256".equals(jwk.path("crv").asString(""))) {
                    AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
                    params.init(new ECGenParameterSpec("secp256r1"));
                    ECPoint point = new ECPoint(
                            unsigned(jwk.path("x").asString("")),
                            unsigned(jwk.path("y").asString("")));
                    keys.put(
                            kid + "|ES256",
                            KeyFactory.getInstance("EC")
                                    .generatePublic(new ECPublicKeySpec(
                                            point, params.getParameterSpec(ECParameterSpec.class))));
                }
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // skip keys this JDK cannot use
            }
        }
        keysLoaded = clock.instant();
    }

    private JsonNode getJson(URI uri) throws OidcException {
        return send(HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build());
    }

    private JsonNode send(HttpRequest request) throws OidcException {
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new OidcException(
                        "provider", request.uri().getHost() + " answered HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > (1 << 20)) {
                throw new OidcException("provider", "provider response too large");
            }
            return Json.mapper().readTree(body);
        } catch (IOException | JacksonException e) {
            throw new OidcException(
                    "provider",
                    "cannot reach the sign-in provider: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OidcException("provider", "interrupted");
        }
    }

    private static JsonNode decode(String part) throws OidcException {
        try {
            return Json.mapper().readTree(B64URL.decode(part));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new OidcException("token", "id_token is malformed");
        }
    }

    private URI https(String value) throws OidcException {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new OidcException("provider", "invalid provider endpoint");
        }
        // plain http only when the issuer itself is http (local development and tests)
        if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && issuer.startsWith("http://"))) {
            throw new OidcException("provider", "provider endpoints must use https");
        }
        return uri;
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.asString(""))) {
                return true;
            }
        }
        return false;
    }

    private static BigInteger unsigned(String b64) {
        return new BigInteger(1, B64URL.decode(b64));
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
