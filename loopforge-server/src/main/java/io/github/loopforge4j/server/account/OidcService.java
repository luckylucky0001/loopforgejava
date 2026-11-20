package io.github.loopforge4j.server.account;

import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * OpenID Connect sign-in (upstream enterprise identity). External identities are bound to accounts
 * by issuer and subject; a provider's username or e-mail never takes over an existing account
 * unless {@code link-by-email} is switched on for verified addresses.
 */
@Service
public class OidcService {

    private static final Logger log = LoggerFactory.getLogger(OidcService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** What the browser keeps between the redirect to the provider and the callback. */
    record Flow(String state, String nonce, String verifier, long expiresAt) {}

    private final LoopForgeProperties.Oidc config;
    private final String publicUrl;
    private final UserStore users;
    private final AccountService accounts;
    private final Clock clock;
    private final OidcClient client;

    public OidcService(LoopForgeProperties properties, UserStore users, AccountService accounts, Clock clock) {
        this.config = properties.security().oidc();
        this.publicUrl = properties.publicUrl();
        this.users = users;
        this.accounts = accounts;
        this.clock = clock;
        this.client = config.enabled()
                ? new OidcClient(
                        require(config.issuer(), "issuer"),
                        require(config.clientId(), "client-id"),
                        config.clientSecret(),
                        clock)
                : null;
    }

    public boolean enabled() {
        return client != null;
    }

    public String displayName() {
        return config.displayName();
    }

    /** The provider's sign-in URL and the flow the browser must bring back. */
    record Start(URI location, Flow flow) {}

    Start start(String requestBase) throws OidcClient.OidcException {
        Flow flow = new Flow(
                random(),
                random(),
                random() + random(),
                clock.instant().plusSeconds(600).getEpochSecond());
        URI location = client.authorizationUrl(
                redirectUri(requestBase), config.scopes(), flow.state(), flow.nonce(), challenge(flow.verifier()));
        return new Start(location, flow);
    }

    AccountService.Session complete(String code, String state, Flow flow, String requestBase)
            throws OidcClient.OidcException {
        if (flow == null
                || state == null
                || !MessageDigest.isEqual(
                        state.getBytes(StandardCharsets.UTF_8), flow.state().getBytes(StandardCharsets.UTF_8))) {
            throw new OidcClient.OidcException("state", "sign-in state does not match; start again");
        }
        if (flow.expiresAt() < clock.instant().getEpochSecond()) {
            throw new OidcClient.OidcException("state", "sign-in took too long; start again");
        }
        if (code == null || code.isBlank() || code.length() > 4096) {
            throw new OidcClient.OidcException("state", "no authorization code");
        }
        JsonNode claims = client.exchange(code, redirectUri(requestBase), flow.verifier(), flow.nonce());
        User user = account(claims);
        try {
            return accounts.sessionFor(user);
        } catch (AccountService.LoginException e) {
            throw new OidcClient.OidcException("disabled", "the account is disabled");
        }
    }

    private User account(JsonNode claims) throws OidcClient.OidcException {
        String issuer = claims.path("iss").asString();
        String subject = claims.path("sub").asString();
        String email = claims.path("email").asString(null);
        boolean emailVerified = claims.path("email_verified").asBoolean(false);
        if (!config.allowedEmailDomains().isEmpty()) {
            String domain = email == null || !email.contains("@")
                    ? ""
                    : email.substring(email.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
            boolean allowed = config.allowedEmailDomains().stream()
                    .anyMatch(d -> d.strip().equalsIgnoreCase(domain));
            if (!emailVerified || !allowed) {
                throw new OidcClient.OidcException("domain", "this e-mail domain may not sign in");
            }
        }
        Optional<String> linked = users.identity(issuer, subject);
        if (linked.isPresent()) {
            return users.byId(linked.get())
                    .orElseThrow(() -> new OidcClient.OidcException("account", "linked account is gone"));
        }
        if (config.linkByEmail() && emailVerified && email != null) {
            List<User> matches = users.byEmail(email);
            if (matches.size() == 1) {
                users.link(issuer, subject, matches.getFirst().id());
                log.info(
                        "linked OIDC subject of {} to existing account {}",
                        issuer,
                        matches.getFirst().username());
                return matches.getFirst();
            }
        }
        if (!config.autoProvision()) {
            throw new OidcClient.OidcException(
                    "account", "no account is linked to this identity; ask an administrator");
        }
        String name = claims.path("name").asString(null);
        User created = null;
        for (String candidate : usernames(claims.path(config.usernameClaim()).asString(""), email, subject)) {
            if (users.byUsername(candidate).isEmpty()) {
                try {
                    created = accounts.createExternal(candidate, name, email);
                    break;
                } catch (RuntimeException e) {
                    // taken concurrently: try the next candidate
                }
            }
        }
        if (created == null) {
            throw new OidcClient.OidcException("account", "cannot choose a username for this identity");
        }
        users.link(issuer, subject, created.id());
        log.info("created account {} for an OIDC identity of {}", created.username(), issuer);
        return created;
    }

    /**
     * Valid, unreserved usernames to try in order: the claim, the e-mail local part, each with a
     * subject-derived suffix (never someone else's account), and finally one from the subject alone.
     */
    static List<String> usernames(String claim, String email, String subject) {
        String suffix = HexFormat.of().formatHex(sha256(subject)).substring(0, 6);
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String base : new String[] {claim, email == null ? null : email.split("@")[0]}) {
            String clean = clean(base);
            if (clean != null) {
                out.add(clean);
                out.add(clean(clean + "-" + suffix));
            }
        }
        out.add("sso-" + suffix);
        out.remove(null);
        return List.copyOf(out);
    }

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^[^a-z0-9]+", "");
        if (s.length() > 64) {
            s = s.substring(0, 64);
        }
        return s.matches("[a-z0-9][a-z0-9._-]{1,63}") && !AccountService.RESERVED.contains(s) ? s : null;
    }

    String redirectUri(String requestBase) {
        if (config.redirectUri() != null && !config.redirectUri().isBlank()) {
            return config.redirectUri().strip();
        }
        String base =
                publicUrl != null && !publicUrl.isBlank() ? publicUrl.strip().replaceAll("/+$", "") : requestBase;
        return base + "/api/v1/auth/oidc/callback";
    }

    static String encode(Flow flow) {
        return B64.encodeToString(Json.write(flow).getBytes(StandardCharsets.UTF_8));
    }

    static Flow decode(String cookie) {
        if (cookie == null || cookie.length() > 2048) {
            return null;
        }
        try {
            return Json.read(new String(Base64.getUrlDecoder().decode(cookie), StandardCharsets.UTF_8), Flow.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String challenge(String verifier) {
        return B64.encodeToString(sha256(verifier));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("loopforge.security.oidc." + name + " is required when OIDC is enabled");
        }
        return value;
    }

    Map<String, Object> status() {
        return enabled() ? Map.of("enabled", true, "name", config.displayName()) : Map.of("enabled", false);
    }
}
