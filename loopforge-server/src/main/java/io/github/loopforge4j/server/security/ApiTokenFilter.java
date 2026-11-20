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
 * endpoints alone also accept an {@code access_token} query parameter.
 */
class ApiTokenFilter extends OncePerRequestFilter {

    private static final Pattern STREAM = Pattern.compile("^/api/v1/(runs/[^/]+|assistant/sessions/[^/]+"
            + "|projects/[^/]+/requirements/\\d+/clarification|runs/[^/]+/eval)/stream$");

    private final ApiTokens tokens;
    private final io.github.loopforge4j.server.account.TokenStore accountTokens;
    private final io.github.loopforge4j.server.mcp.RunTokens runTokens;
    private final io.github.loopforge4j.server.account.UserStore users;

    ApiTokenFilter(
            ApiTokens tokens,
            io.github.loopforge4j.server.account.TokenStore accountTokens,
            io.github.loopforge4j.server.mcp.RunTokens runTokens,
            io.github.loopforge4j.server.account.UserStore users) {
        this.tokens = tokens;
        this.accountTokens = accountTokens;
        this.runTokens = runTokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = extract(request);
        Optional<io.github.loopforge4j.server.account.CurrentUser> principal = Optional.empty();
        if (token != null && token.startsWith(io.github.loopforge4j.server.account.TokenStore.PREFIX)) {
            principal = accountTokens.authenticate(token).map(io.github.loopforge4j.server.account.CurrentUser::of);
        } else if (io.github.loopforge4j.server.mcp.RunTokens.looksLike(token)) {
            principal = runTokens
                    .verify(token, io.github.loopforge4j.server.mcp.RunTokens.Kind.PLATFORM)
                    .map(c -> io.github.loopforge4j.server.account.CurrentUser.agent(
                            new io.github.loopforge4j.server.account.CurrentUser.AgentGrant(
                                    c.runId(), c.projectId(), c.nodeId(), c.agentId())));
            if (principal.isEmpty()) {
                // the conversation assistant acts for the person speaking, while the turn runs
                principal = runTokens
                        .verify(token, io.github.loopforge4j.server.mcp.RunTokens.Kind.CONVERSATION)
                        .flatMap(c -> users.byId(c.subject())
                                .filter(u -> !u.disabled())
                                .map(u -> io.github.loopforge4j.server.account.CurrentUser.assistantFor(
                                        u,
                                        c.runId()
                                                .substring(
                                                        io.github.loopforge4j.server.mcp.RunTokens.CONVERSATION_PREFIX
                                                                .length()),
                                        c.projectId())));
            }
        } else if (tokens.authenticate(token).isPresent()) {
            principal = Optional.of(io.github.loopforge4j.server.account.CurrentUser.SERVICE);
        }
        if (principal.isPresent() && principal.get().readOnly() && !readOnlyRequest(request)) {
            // agents read the platform; the MCP endpoint itself only forwards their read-only tools
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getWriter()
                    .write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                            + "\"detail\":\"agent run tokens are read-only\"}");
            return;
        }
        principal.ifPresent(user -> {
            String authority = user.admin() ? "ROLE_ADMIN" : user.readOnly() ? "ROLE_AGENT" : "ROLE_MEMBER";
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    user, null, List.of(new SimpleGrantedAuthority(authority)));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
        chain.doFilter(request, response);
    }

    private static boolean readOnlyRequest(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method)
                || "HEAD".equals(method)
                || "POST".equals(method)
                        && io.github.loopforge4j.server.mcp.PlatformMcpController.PATH.equals(request.getRequestURI()
                                .substring(request.getContextPath().length()));
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
