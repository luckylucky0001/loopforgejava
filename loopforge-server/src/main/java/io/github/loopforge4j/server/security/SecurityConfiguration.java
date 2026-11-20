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
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiTokens tokens, LoopForgeProperties properties)
            throws Exception {
        boolean publicDocs = properties.security().publicDocs();
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // The original REQUEST dispatch was authorized; SSE completion re-enters as ASYNC.
                    auth.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                            .permitAll();
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                            .permitAll();
                    if (publicDocs) {
                        auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .permitAll();
                    }
                    auth.requestMatchers("/error").permitAll();
                    auth.anyRequest().authenticated();
                })
                .addFilterBefore(new ApiTokenFilter(tokens), AnonymousAuthenticationFilter.class)
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
