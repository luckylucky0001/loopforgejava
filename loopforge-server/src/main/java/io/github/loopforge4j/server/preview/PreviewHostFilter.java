package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.server.config.LoopForgeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The preview host (upstream {@code previewHost} middleware): a page a preview sent to a root path
 * ({@code location.href = "/login"}, a plain link, a form) is redirected back under that preview's
 * path; with a separate preview origin, that origin serves previews only and previews are served on
 * it only.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PreviewHostFilter extends OncePerRequestFilter {

    private final LoopForgeProperties.Preview config;
    private final String previewAuthority;

    public PreviewHostFilter(LoopForgeProperties properties) {
        this.config = properties.preview();
        String authority = null;
        if (config.separateOrigin()) {
            authority = URI.create(config.publicUrl().strip()).getRawAuthority();
        }
        this.previewAuthority = authority;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean previewPath = path.startsWith(PreviewService.PREFIX);
        String host = request.getHeader("Host");
        if (previewAuthority != null) {
            boolean onPreviewHost = previewAuthority.equalsIgnoreCase(host);
            if (previewPath && !onPreviewHost) {
                // with allow-same-origin a preview must never share the console's origin
                response.sendRedirect(config.publicUrl().strip().replaceAll("/+$", "")
                        + path
                        + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
                return;
            }
            if (!previewPath && onPreviewHost) {
                String target = strayTarget(request);
                if (target != null) {
                    response.setStatus(HttpServletResponse.SC_SEE_OTHER);
                    response.setHeader("Location", target);
                    return;
                }
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        } else if (!previewPath) {
            String target = strayTarget(request);
            if (target != null) {
                response.setStatus(HttpServletResponse.SC_SEE_OTHER);
                response.setHeader("Location", target);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Where a navigation a preview page sent to a root path belongs, or null. */
    static String strayTarget(HttpServletRequest request) {
        if (!navigation(request)) {
            return null;
        }
        String referer = request.getHeader("Referer");
        if (referer == null || referer.isBlank()) {
            return null;
        }
        URI ref;
        try {
            ref = URI.create(referer);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String refPath = ref.getRawPath();
        if (ref.getRawAuthority() == null || refPath == null || !refPath.startsWith(PreviewService.PREFIX)) {
            return null;
        }
        String rest = refPath.substring(PreviewService.PREFIX.length());
        int slash = rest.indexOf('/');
        String token = slash < 0 ? rest : rest.substring(0, slash);
        if (!PreviewService.validToken(token)) {
            return null;
        }
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.isBlank()) {
            if (!site.equals("same-origin")) {
                return null;
            }
        } else if (!ref.getRawAuthority().equalsIgnoreCase(request.getHeader("Host"))) {
            // browsers send fetch metadata over HTTPS only; plain HTTP compares hosts
            return null;
        }
        return PreviewService.PREFIX
                + token
                + request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    }

    private static boolean navigation(HttpServletRequest request) {
        String mode = request.getHeader("Sec-Fetch-Mode");
        if (mode != null && !mode.isBlank()) {
            return mode.equals("navigate");
        }
        String accept = request.getHeader("Accept");
        return ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && accept != null
                && accept.contains("text/html");
    }
}
