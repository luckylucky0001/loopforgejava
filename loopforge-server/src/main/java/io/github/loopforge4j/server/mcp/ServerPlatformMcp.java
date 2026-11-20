package io.github.loopforge4j.server.mcp;

import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import io.github.loopforge4j.server.knowledge.KnowledgeProxyController;
import io.github.loopforge4j.server.knowledge.KnowledgeService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Binds the platform-served MCP servers to one agent invocation: the address the sandbox reaches
 * this server at, and a fresh run token that only works while the run is active.
 */
@Component
public class ServerPlatformMcp implements PlatformMcp {

    private final RunTokens tokens;
    private final KnowledgeService knowledge;
    private final InternalApi api;
    private final boolean platformEnabled;
    private final String sandboxUrl;
    private final Duration ttl;
    /** Profiles whose processes share the host network and so reach the loopback address. */
    private final java.util.Set<String> hostNetworkProfiles;

    public ServerPlatformMcp(
            RunTokens tokens, KnowledgeService knowledge, InternalApi api, LoopForgeProperties properties) {
        this.tokens = tokens;
        this.knowledge = knowledge;
        this.api = api;
        this.platformEnabled = properties.mcp().platformEnabled();
        String url = properties.mcp().sandboxUrl();
        this.sandboxUrl = url == null || url.isBlank() ? null : url.strip().replaceAll("/+$", "");
        this.ttl = properties.mcp().agentTokenTtl();
        LoopForgeProperties.Bwrap bwrap = properties.sandbox().bwrap();
        this.hostNetworkProfiles = bwrap.enabled() && bwrap.shareNetwork()
                ? java.util.Set.of(LocalSandboxProvider.PROFILE, bwrap.profile())
                : java.util.Set.of(LocalSandboxProvider.PROFILE);
    }

    @Override
    public Binding bind(Invocation invocation) {
        return bindAt(invocation, baseFor(invocation.sandboxProfileId()));
    }

    /**
     * The binding for an agent that reaches this server at {@code base} (an execution machine passes
     * the URL it connects to).
     */
    public Binding bindAt(Invocation invocation, String base) {
        List<McpServerDefinition> servers = new ArrayList<>();
        List<RuleDefinition> rules = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (invocation.platform()) {
            if (!platformEnabled) {
                warnings.add("未注入 " + PLATFORM_ID + "：本部署关闭了平台 MCP（loopforge.mcp.platform-enabled）");
            } else if (base == null) {
                warnings.add(unreachable(PLATFORM_ID, invocation.sandboxProfileId()));
            } else {
                servers.add(server(
                        PLATFORM_ID,
                        "LoopForge 平台",
                        "LoopForge 平台只读操作：项目、需求、运行、产物、SOP 与分析",
                        base + PlatformMcpController.PATH,
                        tokens.issue(
                                RunTokens.Kind.PLATFORM,
                                invocation.runId(),
                                invocation.projectId(),
                                invocation.nodeId(),
                                invocation.agentId(),
                                ttl)));
            }
        }
        if (invocation.knowledge()) {
            if (!knowledge.ready()) {
                warnings.add("角色开启了团队知识库，但未注入 " + KNOWLEDGE_ID + "：知识库未启用或未配置地址/Token");
            } else if (base == null) {
                warnings.add(unreachable(KNOWLEDGE_ID, invocation.sandboxProfileId()));
            } else {
                servers.add(server(
                        KNOWLEDGE_ID,
                        "团队知识库",
                        "团队知识库只读检索：kb_search / kb_fetch / kb_list_sources / kb_get_source",
                        base + KnowledgeProxyController.PATH,
                        tokens.issue(
                                RunTokens.Kind.KNOWLEDGE,
                                invocation.runId(),
                                invocation.projectId(),
                                invocation.nodeId(),
                                invocation.agentId(),
                                ttl)));
                rules.add(KnowledgeService.usageRule());
            }
        }
        return new Binding(servers, rules, warnings);
    }

    /**
     * Servers for one conversation turn: the platform MCP acting for {@code userId} (the person
     * speaking, never with administrator rights) and, when connected, the knowledge base. Tokens
     * name the turn and die with it.
     */
    public Binding bindConversation(
            String sessionId, String turnId, String projectId, String userId, String sandboxProfileId) {
        List<McpServerDefinition> servers = new ArrayList<>();
        List<RuleDefinition> rules = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String base = baseFor(sandboxProfileId);
        String run = RunTokens.CONVERSATION_PREFIX + sessionId;
        if (base == null) {
            warnings.add(unreachable(PLATFORM_ID, sandboxProfileId));
            return new Binding(servers, rules, warnings);
        }
        if (platformEnabled && userId != null) {
            servers.add(server(
                    PLATFORM_ID,
                    "LoopForge 平台",
                    "以本轮发言人的身份操作平台：需求、运行、SOP、项目、知识库与分析",
                    base + PlatformMcpController.PATH,
                    tokens.issue(RunTokens.Kind.CONVERSATION, run, projectId, turnId, "main-bot", userId, ttl)));
        }
        if (knowledge.ready()) {
            servers.add(server(
                    KNOWLEDGE_ID,
                    "团队知识库",
                    "团队知识库只读检索：kb_search / kb_fetch / kb_list_sources / kb_get_source",
                    base + KnowledgeProxyController.PATH,
                    tokens.issue(RunTokens.Kind.KNOWLEDGE, run, projectId, turnId, "main-bot", ttl)));
            rules.add(KnowledgeService.usageRule());
        }
        return new Binding(servers, rules, warnings);
    }

    /** The configured sandbox-facing URL, else loopback for agents that share the host network. */
    private String baseFor(String sandboxProfileId) {
        if (sandboxUrl != null) {
            return sandboxUrl;
        }
        if (hostNetworkProfiles.contains(sandboxProfileId)) {
            return api.localBase();
        }
        return null;
    }

    private static String unreachable(String id, String profile) {
        return "未注入 " + id + "：沙箱 " + profile + " 访问不到平台地址，请配置 loopforge.mcp.sandbox-url（沙箱内可访问的服务地址）";
    }

    private static McpServerDefinition server(String id, String name, String description, String url, String token) {
        return new McpServerDefinition(
                id,
                name,
                description,
                McpServerDefinition.HTTP,
                null,
                List.of(),
                url,
                Map.of(),
                Map.of("Authorization", "Bearer " + token),
                null,
                60,
                null,
                null);
    }
}
