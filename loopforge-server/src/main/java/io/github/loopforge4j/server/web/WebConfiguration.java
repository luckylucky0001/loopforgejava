package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.RunStatus;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Query parameters use the same lower-case wire values as JSON payloads ({@code ?status=running}). */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final io.github.loopforge4j.server.account.AccessPolicy access;

    WebConfiguration(io.github.loopforge4j.server.account.AccessPolicy access) {
        this.access = access;
    }

    /**
     * The global catalog (MCP servers, sandboxes, bundle import) is writable by administrators;
     * SOPs, roles, rules, skills and repositories check project roles in their controllers.
     */
    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
                    @Override
                    public boolean preHandle(
                            jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response,
                            Object handler) {
                        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
                            access.requireAdmin(request.getUserPrincipal());
                        }
                        return true;
                    }
                })
                .addPathPatterns(
                        "/api/v1/mcp-servers/**",
                        "/api/v1/dockerfiles/**",
                        "/api/v1/environments/**",
                        "/api/v1/sandboxes/**",
                        "/api/v1/catalog/import");
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, RunStatus.class, RunStatus::fromWire);
    }
}
