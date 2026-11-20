package io.github.loopforge4j.server.mcp;

import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The public operation manifest and how to reach the MCP endpoints (upstream {@code /api/capabilities}). */
@RestController
public class CapabilitiesController {

    private final AccessPolicy access;
    private final boolean platform;

    public CapabilitiesController(AccessPolicy access, LoopForgeProperties properties) {
        this.access = access;
        this.platform = properties.mcp().platformEnabled();
    }

    @GetMapping("/api/v1/capabilities")
    Map<String, Object> capabilities(Principal principal) {
        return Map.of(
                "operations",
                PlatformMcpController.visible(access.current(principal)),
                "mcp",
                Map.of(
                        "platform",
                        platform ? PlatformMcpController.PATH : "",
                        "transport",
                        "streamable-http (stateless, JSON responses)",
                        "auth",
                        "Authorization: Bearer <account or service token>",
                        "stdioBridge",
                        "java -cp loopforge-core.jar io.github.loopforge4j.core.mcp.PlatformMcpBridge"
                                + " (LOOPFORGE_API_URL, LOOPFORGE_API_TOKEN)"));
    }
}
