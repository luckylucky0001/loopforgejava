package io.github.loopforge4j.server.account;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser endpoints of the OIDC sign-in. The flow (state, nonce, PKCE verifier) lives in an
 * HttpOnly cookie scoped to these endpoints, so any instance can finish a sign-in another started.
 */
@RestController
public class OidcController {

    static final String COOKIE = "lf_oidc";
    private static final String PATH = "/api/v1/auth/oidc";
    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    private final OidcService oidc;

    public OidcController(OidcService oidc) {
        this.oidc = oidc;
    }

    @GetMapping(PATH + "/start")
    ResponseEntity<Void> start(HttpServletRequest request) {
        if (!oidc.enabled()) {
            return ResponseEntity.notFound().build();
        }
        try {
            OidcService.Start start = oidc.start(base(request));
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(start.location())
                    .header(
                            HttpHeaders.SET_COOKIE,
                            cookie(OidcService.encode(start.flow()), 600, request)
                                    .toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        } catch (OidcClient.OidcException e) {
            log.warn("OIDC sign-in cannot start: {}", e.getMessage());
            return console(request, null, e.code);
        }
    }

    @GetMapping(PATH + "/callback")
    ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @CookieValue(name = COOKIE, required = false) String flow,
            HttpServletRequest request) {
        if (!oidc.enabled()) {
            return ResponseEntity.notFound().build();
        }
        if (error != null) {
            return console(request, null, "provider");
        }
        try {
            AccountService.Session session = oidc.complete(code, state, OidcService.decode(flow), base(request));
            return console(request, session.token(), null);
        } catch (OidcClient.OidcException e) {
            log.warn("OIDC sign-in failed: {}", e.getMessage());
            return console(request, null, e.code);
        }
    }

    /** Back to the console: the session token in the fragment (never sent to a server), or an error code. */
    private ResponseEntity<Void> console(HttpServletRequest request, String token, String error) {
        String fragment = token != null
                ? "#/sso?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                : "#/sso?error=" + URLEncoder.encode(error, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(request.getContextPath() + "/" + fragment))
                .header(HttpHeaders.SET_COOKIE, cookie("", 0, request).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private static ResponseCookie cookie(String value, long maxAge, HttpServletRequest request) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())
                // the provider redirects back with a top-level GET: Lax cookies are sent, cross-site posts are not
                .sameSite("Lax")
                .path(request.getContextPath() + PATH)
                .maxAge(maxAge)
                .build();
    }

    private static String base(HttpServletRequest request) {
        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = "http".equals(scheme) && port == 80 || "https".equals(scheme) && port == 443;
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port) + request.getContextPath();
    }
}
