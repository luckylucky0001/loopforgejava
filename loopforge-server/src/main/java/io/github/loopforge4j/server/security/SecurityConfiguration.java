package io.github.loopforge4j.server.security;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Stateless API security: every request except health and (optionally) API docs needs a bearer
 * token. CSRF protection is off because no cookies or sessions are used.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            ApiTokens tokens,
            io.github.loopforge4j.server.account.TokenStore accountTokens,
            io.github.loopforge4j.server.mcp.RunTokens runTokens,
            LoopForgeProperties properties)
            throws Exception {
        boolean publicDocs = properties.security().publicDocs();
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // the console renders untrusted run output: no inline script or style, same-origin only
                .headers(headers -> headers.addHeaderWriter(
                        new org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter(
                                request -> {
                                    String path = request.getRequestURI();
                                    return path.equals("/")
                                            || path.equals("/index.html")
                                            || path.startsWith("/console/");
                                },
                                new org.springframework.security.web.header.writers.StaticHeadersWriter(
                                        "Content-Security-Policy",
                                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                                                + " connect-src 'self'; object-src 'none'; base-uri 'none';"
                                                + " frame-ancestors 'none'; form-action 'self'"))))
                .authorizeHttpRequests(auth -> {
                    // The original REQUEST dispatch was authorized; SSE completion re-enters as ASYNC.
                    auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                            .permitAll();
                    // info and prometheus need a token; only liveness/readiness probes are public
                    auth.requestMatchers("/actuator/health", "/actuator/health/**")
                            .permitAll();
                    if (publicDocs) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .permitAll();
                    }
                    auth.requestMatchers("/error").permitAll();
                    auth.requestMatchers(
                                    "/api/v1/auth/status",
                                    "/api/v1/auth/setup",
                                    "/api/v1/auth/login",
                                    "/api/v1/auth/logout",
                                    "/api/v1/auth/oidc/start",
                                    "/api/v1/auth/oidc/callback")
                            .permitAll();
                    // agents' knowledge proxy verifies its own run tokens (they are not API credentials)
                    auth.requestMatchers(
                                    org.springframework.http.HttpMethod.POST,
                                    io.github.loopforge4j.server.knowledge.KnowledgeProxyController.PATH)
                            .permitAll();
                    // the web console is static; every API call it makes needs a token
                    auth.requestMatchers("/", "/index.html", "/console/**", "/favicon.svg")
                            .permitAll();
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(
                        new ApiTokenFilter(tokens, accountTokens, runTokens), AnonymousAuthenticationFilter.class)
                .addFilterBefore(
                        new RequestSizeLimitFilter(
                                properties.security().maxRequestSize().toBytes()),
                        org.springframework.security.web.header.HeaderWriterFilter.class)
                .exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setHeader("WWW-Authenticate", "Bearer");
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    response.getWriter()
                            .write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                                    + "\"detail\":\"a valid bearer token is required\"}");
                }));
        return http.build();
    }
}
