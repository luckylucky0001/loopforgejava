package io.github.loopforge4j.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token authentication. Browsers cannot set headers on {@code EventSource}, so the SSE stream
 * endpoint alone also accepts an {@code access_token} query parameter.
 */
class ApiTokenFilter extends OncePerRequestFilter {

    private static final Pattern STREAM = Pattern.compile("^/api/v1/runs/[^/]+/stream$");

    private final ApiTokens tokens;

    ApiTokenFilter(ApiTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> principal = tokens.authenticate(extract(request));
        principal.ifPresent(name -> {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    name, null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
        chain.doFilter(request, response);
    }

    private static String extract(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7);
        }
        if ("GET".equals(request.getMethod())
                && STREAM.matcher(request.getRequestURI()).matches()) {
            return request.getParameter("access_token");
        }
        return null;
    }
}
