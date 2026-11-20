package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.AssetKind;
import io.github.loopforge4j.core.domain.CatalogAsset;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.catalog.CatalogService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rules, skills and MCP servers roles bind by id (upstream {@code assetHandlers}). A role picks up
 * changes on its next invocation; runs do not freeze these assets.
 */
@Tag(name = "Assets")
@RestController
@RequestMapping("/api/v1")
class AssetController {

    private final CatalogStore store;
    private final CatalogService catalog;

    AssetController(CatalogStore store, CatalogService catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping("/rules")
    List<RuleDefinition> rules() {
        return store.assets(AssetKind.RULE);
    }

    @GetMapping("/rules/{id}")
    RuleDefinition rule(@PathVariable String id) {
        return get(AssetKind.RULE, id);
    }

    @PutMapping("/rules/{id}")
    RuleDefinition putRule(@PathVariable String id, @RequestBody RuleDefinition body) {
        return catalog.saveAsset(
                AssetKind.RULE,
                new RuleDefinition(id, body.name(), body.description(), body.content(), body.priority(), null));
    }

    @DeleteMapping("/rules/{id}")
    ResponseEntity<Void> deleteRule(@PathVariable String id) {
        return delete(AssetKind.RULE, id);
    }

    @GetMapping("/skills")
    List<SkillDefinition> skills() {
        return store.assets(AssetKind.SKILL);
    }

    @GetMapping("/skills/{id}")
    SkillDefinition skill(@PathVariable String id) {
        return get(AssetKind.SKILL, id);
    }

    @PutMapping("/skills/{id}")
    SkillDefinition putSkill(@PathVariable String id, @RequestBody SkillDefinition body) {
        return catalog.saveAsset(
                AssetKind.SKILL,
                new SkillDefinition(id, body.name(), body.description(), body.content(), body.files(), null));
    }

    @DeleteMapping("/skills/{id}")
    ResponseEntity<Void> deleteSkill(@PathVariable String id) {
        return delete(AssetKind.SKILL, id);
    }

    @GetMapping("/mcp-servers")
    List<McpServerDefinition> mcpServers() {
        return store.assets(AssetKind.MCP_SERVER);
    }

    @GetMapping("/mcp-servers/{id}")
    McpServerDefinition mcpServer(@PathVariable String id) {
        return get(AssetKind.MCP_SERVER, id);
    }

    @PutMapping("/mcp-servers/{id}")
    McpServerDefinition putMcpServer(@PathVariable String id, @RequestBody McpServerDefinition body) {
        return catalog.saveAsset(
                AssetKind.MCP_SERVER,
                new McpServerDefinition(
                        id,
                        body.name(),
                        body.description(),
                        body.transport(),
                        body.command(),
                        body.args(),
                        body.url(),
                        body.environment(),
                        body.headers(),
                        body.workingDirectory(),
                        body.timeoutSec(),
                        null));
    }

    @DeleteMapping("/mcp-servers/{id}")
    ResponseEntity<Void> deleteMcpServer(@PathVariable String id) {
        return delete(AssetKind.MCP_SERVER, id);
    }

    private <T extends CatalogAsset> T get(AssetKind<T> kind, String id) {
        return store.asset(kind, id)
                .orElseThrow(() -> new RunNotFoundException(kind.wire() + " '" + id + "' not found"));
    }

    private ResponseEntity<Void> delete(AssetKind<?> kind, String id) {
        return store.deleteAsset(kind, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
