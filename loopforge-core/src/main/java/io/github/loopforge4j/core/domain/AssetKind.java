package io.github.loopforge4j.core.domain;

import java.util.List;

/**
 * Typed key of a catalog asset collection.
 *
 * @param wire storage and API name
 * @param type record class of the asset
 */
public record AssetKind<T extends CatalogAsset>(String wire, Class<T> type) {

    public static final AssetKind<RuleDefinition> RULE = new AssetKind<>("rule", RuleDefinition.class);
    public static final AssetKind<SkillDefinition> SKILL = new AssetKind<>("skill", SkillDefinition.class);
    public static final AssetKind<McpServerDefinition> MCP_SERVER =
            new AssetKind<>("mcp-server", McpServerDefinition.class);

    public static List<AssetKind<?>> all() {
        return List.of(RULE, SKILL, MCP_SERVER);
    }

    @SuppressWarnings("unchecked")
    public T cast(CatalogAsset asset) {
        return (T) type.cast(asset);
    }
}
