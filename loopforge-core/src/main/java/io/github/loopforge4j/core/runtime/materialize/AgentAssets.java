package io.github.loopforge4j.core.runtime.materialize;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.store.CatalogStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The catalog assets a role binds (upstream {@code AgentAssetBundle}). A role's {@code rules} entry
 * that names no catalog rule is inline rule text and stays in the task book.
 */
public record AgentAssets(
        List<RuleDefinition> rules, List<SkillDefinition> skills, List<McpServerDefinition> mcpServers) {

    public static final AgentAssets NONE = new AgentAssets(List.of(), List.of(), List.of());

    public AgentAssets {
        rules = rules == null ? List.of() : List.copyOf(rules);
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    }

    /**
     * Resolves the role's bindings.
     *
     * @throws IllegalArgumentException naming the first skill or MCP server that does not exist
     */
    public static AgentAssets resolve(AgentDefinition agent, CatalogStore catalog) {
        return resolve(agent, catalog, null, null);
    }

    /**
     * Resolves the role's bindings for work in {@code projectId} done for {@code responsible}
     * (upstream role asset resolution): an MCP entry names a catalog server the work may use (shared,
     * the project's, or the responsible person's), or a market id whose project install is used.
     * Servers the work may not use are left out.
     */
    public static AgentAssets resolve(
            AgentDefinition agent, CatalogStore catalog, String projectId, String responsible) {
        List<RuleDefinition> rules = new ArrayList<>();
        // a project's own rules and skills serve only the work of that project
        String owner = projectId != null ? projectId : agent.projectId();
        for (String ref : agent.rules()) {
            catalog.asset(AssetKind.RULE, ref.strip())
                    .filter(r -> r.projectId() == null || r.projectId().equals(owner))
                    .ifPresent(rules::add);
        }
        rules.sort(Comparator.comparingInt(RuleDefinition::priority).reversed());
        List<SkillDefinition> skills = new ArrayList<>();
        for (String id : agent.skills()) {
            skills.add(catalog.asset(AssetKind.SKILL, id)
                    .filter(k -> k.projectId() == null || k.projectId().equals(owner))
                    .orElseThrow(() -> new IllegalArgumentException("skill '" + id + "' not found")));
        }
        List<McpServerDefinition> servers = new ArrayList<>();
        List<McpServerDefinition> all = null;
        for (String id : agent.mcpServers()) {
            var direct = catalog.asset(AssetKind.MCP_SERVER, id);
            if (direct.isPresent()) {
                if (direct.get().visibleTo(projectId, responsible)) {
                    servers.add(direct.get());
                }
                continue;
            }
            if (all == null) {
                all = catalog.assets(AssetKind.MCP_SERVER);
            }
            var installed = all.stream()
                    .filter(m -> m.placement() != null
                            && McpServerDefinition.Placement.PROJECT.equals(
                                    m.placement().scope())
                            && id.equals(m.placement().marketId())
                            && m.visibleTo(projectId, responsible))
                    .findFirst();
            if (installed.isPresent()) {
                servers.add(installed.get());
            } else if (projectId == null
                    || all.stream()
                            .noneMatch(m -> m.placement() != null
                                    && id.equals(m.placement().marketId()))) {
                throw new IllegalArgumentException("MCP server '" + id + "' not found");
            }
        }
        return new AgentAssets(rules, skills, servers);
    }

    /** These assets plus platform-served servers and their usage rules. */
    public AgentAssets with(List<McpServerDefinition> extraServers, List<RuleDefinition> extraRules) {
        if (extraServers.isEmpty() && extraRules.isEmpty()) {
            return this;
        }
        List<RuleDefinition> allRules = new ArrayList<>(rules);
        allRules.addAll(extraRules);
        allRules.sort(Comparator.comparingInt(RuleDefinition::priority).reversed());
        List<McpServerDefinition> allServers = new ArrayList<>(mcpServers);
        allServers.addAll(extraServers);
        return new AgentAssets(allRules, skills, allServers);
    }

    /**
     * Sends {@code githubToken} as the bearer of servers that authenticate with the run's GitHub
     * token (upstream {@code BindGitHubMCPHeaders}); only the official HTTPS endpoint receives it.
     */
    public AgentAssets withGitHubToken(String githubToken) {
        List<McpServerDefinition> out = new ArrayList<>();
        boolean changed = false;
        for (McpServerDefinition server : mcpServers) {
            if (server.placement() == null
                    || !McpServerDefinition.Placement.GITHUB_TOKEN.equals(
                            server.placement().authType())) {
                out.add(server);
                continue;
            }
            changed = true;
            java.util.Map<String, String> headers = new java.util.TreeMap<>(server.headers());
            headers.keySet().removeIf(k -> k.equalsIgnoreCase("Authorization"));
            if (officialGitHub(server.url()) && githubToken != null && !githubToken.isBlank()) {
                headers.put("Authorization", "Bearer " + githubToken.strip());
            }
            out.add(new McpServerDefinition(
                    server.id(),
                    server.name(),
                    server.description(),
                    server.transport(),
                    server.command(),
                    server.args(),
                    server.url(),
                    server.environment(),
                    headers,
                    server.workingDirectory(),
                    server.timeoutSec(),
                    server.updatedAt(),
                    server.placement()));
        }
        return changed ? new AgentAssets(rules, skills, out) : this;
    }

    static boolean officialGitHub(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return "https".equals(uri.getScheme())
                    && "api.githubcopilot.com".equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (java.net.URISyntaxException | NullPointerException e) {
            return false;
        }
    }

    /** Rule entries of the role that are inline text rather than catalog references. */
    public static List<String> inlineRules(AgentDefinition agent, CatalogStore catalog) {
        return agent.rules().stream()
                .filter(r -> catalog.asset(AssetKind.RULE, r.strip()).isEmpty())
                .toList();
    }
}
