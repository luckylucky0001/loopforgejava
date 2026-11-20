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
    private final io.github.loopforge4j.server.account.AccessPolicy access;

    AssetController(
            CatalogStore store, CatalogService catalog, io.github.loopforge4j.server.account.AccessPolicy access) {
        this.store = store;
        this.catalog = catalog;
        this.access = access;
    }

    /**
     * Shared servers are for everyone; a project's servers for its members, a personal server for
     * its owner (and administrators).
     */
    private boolean visible(McpServerDefinition server, java.security.Principal principal) {
        if (server.shared()) {
            return true;
        }
        var user = access.current(principal);
        if (user.admin()) {
            return true;
        }
        var placement = server.placement();
        return switch (placement.scope()) {
            case McpServerDefinition.Placement.PROJECT -> access.canSee(principal, placement.projectId());
            case McpServerDefinition.Placement.PERSONAL ->
                user.username() != null && user.username().equals(placement.owner());
            default -> false;
        };
    }

    @GetMapping("/rules")
    List<RuleDefinition> rules(java.security.Principal principal) {
        return store.assets(AssetKind.RULE).stream()
                .filter(r -> access.canSee(principal, r.projectId()))
                .toList();
    }

    @GetMapping("/rules/{id}")
    RuleDefinition rule(@PathVariable String id, java.security.Principal principal) {
        RuleDefinition rule = get(AssetKind.RULE, id);
        if (!access.canSee(principal, rule.projectId())) {
            throw new RunNotFoundException("rule '" + id + "' not found");
        }
        return rule;
    }

    @PutMapping("/rules/{id}")
    RuleDefinition putRule(
            @PathVariable String id, @RequestBody RuleDefinition body, java.security.Principal principal) {
        PathIds.require(id);
        var existing = store.asset(AssetKind.RULE, id);
        CatalogScopes.requireWrite(
                access,
                principal,
                existing.isPresent(),
                existing.map(RuleDefinition::projectId).orElse(null),
                body.projectId());
        return catalog.saveAsset(
                AssetKind.RULE,
                new RuleDefinition(
                        id, body.name(), body.description(), body.content(), body.priority(), null, body.projectId()));
    }

    @DeleteMapping("/rules/{id}")
    ResponseEntity<Void> deleteRule(@PathVariable String id, java.security.Principal principal) {
        PathIds.require(id);
        store.asset(AssetKind.RULE, id)
                .ifPresent(r -> access.requireProject(
                        principal, r.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER));
        return delete(AssetKind.RULE, id);
    }

    @GetMapping("/skills")
    List<SkillDefinition> skills(java.security.Principal principal) {
        return store.assets(AssetKind.SKILL).stream()
                .filter(k -> access.canSee(principal, k.projectId()))
                .toList();
    }

    @GetMapping("/skills/{id}")
    SkillDefinition skill(@PathVariable String id, java.security.Principal principal) {
        SkillDefinition skill = get(AssetKind.SKILL, id);
        if (!access.canSee(principal, skill.projectId())) {
            throw new RunNotFoundException("skill '" + id + "' not found");
        }
        return skill;
    }

    @PutMapping("/skills/{id}")
    SkillDefinition putSkill(
            @PathVariable String id, @RequestBody SkillDefinition body, java.security.Principal principal) {
        PathIds.require(id);
        var existing = store.asset(AssetKind.SKILL, id);
        CatalogScopes.requireWrite(
                access,
                principal,
                existing.isPresent(),
                existing.map(SkillDefinition::projectId).orElse(null),
                body.projectId());
        return catalog.saveAsset(
                AssetKind.SKILL,
                new SkillDefinition(
                        id, body.name(), body.description(), body.content(), body.files(), null, body.projectId()));
    }

    @DeleteMapping("/skills/{id}")
    ResponseEntity<Void> deleteSkill(@PathVariable String id, java.security.Principal principal) {
        PathIds.require(id);
        store.asset(AssetKind.SKILL, id)
                .ifPresent(k -> access.requireProject(
                        principal, k.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER));
        return delete(AssetKind.SKILL, id);
    }

    @GetMapping("/mcp-servers")
    List<McpServerDefinition> mcpServers(java.security.Principal principal) {
        return store.assets(AssetKind.MCP_SERVER).stream()
                .filter(m -> visible(m, principal))
                .toList();
    }

    @GetMapping("/mcp-servers/{id}")
    McpServerDefinition mcpServer(@PathVariable String id, java.security.Principal principal) {
        McpServerDefinition server = get(AssetKind.MCP_SERVER, id);
        if (!visible(server, principal)) {
            throw new io.github.loopforge4j.core.engine.RunNotFoundException("mcp server '" + id + "' not found");
        }
        return server;
    }

    @PutMapping("/mcp-servers/{id}")
    McpServerDefinition putMcpServer(@PathVariable String id, @RequestBody McpServerDefinition body) {
        PathIds.require(id);
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
                        null,
                        // editing a project or personal server keeps where it applies
                        store.asset(AssetKind.MCP_SERVER, id)
                                .map(McpServerDefinition::placement)
                                .orElse(body.placement())));
    }

    @DeleteMapping("/mcp-servers/{id}")
    ResponseEntity<Void> deleteMcpServer(@PathVariable String id) {
        PathIds.require(id);
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
