package io.github.loopforge4j.core.mcp;

import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP servers the deployment itself serves, switched on per role (upstream {@code
 * agentflow-platform} / {@code agentflow-knowledge}). They are never catalog rows: every invocation
 * gets a fresh, short-lived credential scoped to its run and project.
 */
@FunctionalInterface
public interface PlatformMcp {

    /** Read-only LoopForge API operations as MCP tools. */
    String PLATFORM_ID = "loopforge-platform";

    /** Read-only, project-scoped team knowledge base. */
    String KNOWLEDGE_ID = "loopforge-knowledge";
    /** The agent browser the server runs beside its own agents. */
    String BROWSER_ID = "loopforge-browser";

    /** Bindings without a provider: nothing is injected and the run says why. */
    PlatformMcp NONE = invocation -> {
        List<String> warnings = new ArrayList<>();
        if (invocation.platform()) {
            warnings.add("未注入 " + PLATFORM_ID + "：本部署未启用平台 MCP");
        }
        if (invocation.knowledge()) {
            warnings.add("未注入 " + KNOWLEDGE_ID + "：本部署未接入知识库");
        }
        return new Binding(List.of(), List.of(), warnings);
    };

    static boolean reserved(String id) {
        return PLATFORM_ID.equals(id)
                || KNOWLEDGE_ID.equals(id)
                || BROWSER_ID.equals(id)
                || GateMcpServer.SERVER_ID.equals(id);
    }

    /**
     * Resolves the platform servers one agent invocation asked for. A server that cannot be served
     * (not configured, unreachable from the sandbox) is left out with a warning; it never fails the
     * node.
     */
    Binding bind(Invocation invocation);

    /**
     * @param sandboxProfileId where the agent runs, which decides the address it reaches the
     *     platform at
     */
    record Invocation(
            String runId,
            String projectId,
            String nodeId,
            String agentId,
            String actor,
            String sandboxProfileId,
            boolean platform,
            boolean knowledge) {}

    /**
     * @param servers resolved http servers, credentials included
     * @param rules usage rules that come with the servers
     * @param warnings servers that were requested but not injected, and why
     */
    record Binding(List<McpServerDefinition> servers, List<RuleDefinition> rules, List<String> warnings) {

        public static final Binding EMPTY = new Binding(List.of(), List.of(), List.of());

        public Binding {
            servers = servers == null ? List.of() : List.copyOf(servers);
            rules = rules == null ? List.of() : List.copyOf(rules);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
