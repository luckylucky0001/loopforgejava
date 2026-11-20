package io.github.loopforge4j.core.runtime.materialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition.Placement;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Project and personal MCP servers reach only the work they belong to; market ids bind installs. */
class AgentAssetsScopeTest {

    static McpServerDefinition http(String id, String url, Placement placement, Map<String, String> headers) {
        return new McpServerDefinition(id, id, null, "http", null, null, url, null, headers, null, 0, null, placement);
    }

    @Test
    void scopedServersAndMarketInstalls() {
        InMemoryCatalogStore catalog = new InMemoryCatalogStore();
        catalog.saveAsset(AssetKind.MCP_SERVER, http("docs", "https://docs.example/mcp", null, null));
        catalog.saveAsset(
                AssetKind.MCP_SERVER,
                http(
                        "mcp-github-project-p1",
                        "https://api.githubcopilot.com/mcp/",
                        new Placement("project", "p1", null, "github", "github-token"),
                        Map.of("authorization", "Bearer stale")));
        catalog.saveAsset(
                AssetKind.MCP_SERVER,
                http(
                        "mcp-github-project-p2",
                        "https://api.githubcopilot.com/mcp/",
                        new Placement("project", "p2", null, "github", "github-token"),
                        null));
        catalog.saveAsset(
                AssetKind.MCP_SERVER,
                http("mine", "https://me.example/mcp", new Placement("personal", null, "alice", null, null), null));
        AgentDefinition role = AgentDefinition.builder("dev")
                .name("dev")
                .mcpServers(List.of("docs", "github", "mine", "mcp-github-project-p2"))
                .build();

        AgentAssets alice = AgentAssets.resolve(role, catalog, "p1", "alice");
        assertThat(alice.mcpServers())
                .extracting(McpServerDefinition::id)
                .containsExactly("docs", "mcp-github-project-p1", "mine");
        AgentAssets bob = AgentAssets.resolve(role, catalog, "p2", "bob");
        assertThat(bob.mcpServers())
                .extracting(McpServerDefinition::id)
                .containsExactly("docs", "mcp-github-project-p2", "mcp-github-project-p2");
        // without a project the market id resolves to nothing, but it is no error
        assertThat(AgentAssets.resolve(role, catalog, "p3", null).mcpServers())
                .extracting(McpServerDefinition::id)
                .containsExactly("docs");
        AgentDefinition unknown = AgentDefinition.builder("x")
                .name("x")
                .mcpServers(List.of("ghost"))
                .build();
        assertThatThrownBy(() -> AgentAssets.resolve(unknown, catalog, "p1", "alice"))
                .hasMessageContaining("ghost");

        // the GitHub token goes only to the official endpoint, replacing any stored authorization
        AgentAssets bound = alice.withGitHubToken("ghp_secret");
        assertThat(bound.mcpServers().get(1).headers())
                .containsExactly(Map.entry("Authorization", "Bearer ghp_secret"));
        assertThat(bound.mcpServers().get(0).headers()).isEmpty();
        assertThat(alice.withGitHubToken(null).mcpServers().get(1).headers()).isEmpty();
        McpServerDefinition fake = http(
                "fake",
                "https://api.githubcopilot.com.evil.example/mcp",
                new Placement("project", "p1", null, "github", "github-token"),
                null);
        assertThat(new AgentAssets(List.of(), List.of(), List.of(fake))
                        .withGitHubToken("ghp_secret")
                        .mcpServers()
                        .getFirst()
                        .headers())
                .isEmpty();
    }
}
