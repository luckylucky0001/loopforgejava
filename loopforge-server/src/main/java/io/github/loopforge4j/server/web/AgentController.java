package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.catalog.CatalogService;
import io.github.loopforge4j.server.persistence.DefinitionRevision;
import io.github.loopforge4j.server.persistence.DefinitionRevisionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Roles (upstream {@code catalogHandlers}, agent part). */
@Tag(name = "Agents")
@RestController
@RequestMapping("/api/v1/agents")
class AgentController {

    private final CatalogStore store;
    private final CatalogService catalog;
    private final DefinitionRevisionStore revisions;
    private final io.github.loopforge4j.server.account.AccessPolicy access;

    AgentController(
            CatalogStore store,
            CatalogService catalog,
            DefinitionRevisionStore revisions,
            io.github.loopforge4j.server.account.AccessPolicy access) {
        this.store = store;
        this.catalog = catalog;
        this.revisions = revisions;
        this.access = access;
    }

    @Operation(summary = "Shared roles and the roles of the projects the caller belongs to")
    @GetMapping
    List<AgentDefinition> list(java.security.Principal principal) {
        return store.agents().stream()
                .filter(a -> access.canSee(principal, a.projectId()))
                .toList();
    }

    @GetMapping("/{id}")
    AgentDefinition get(@PathVariable String id, java.security.Principal principal) {
        return store.agent(id)
                .filter(a -> access.canSee(principal, a.projectId()))
                .orElseThrow(() -> new RunNotFoundException("agent '" + id + "' not found"));
    }

    @Operation(summary = "Create or replace a role: shared roles by administrators, a project's by its maintainers")
    @PutMapping("/{id}")
    AgentDefinition put(@PathVariable String id, @RequestBody AgentDefinition body, java.security.Principal principal) {
        PathIds.require(id);
        var existing = store.agent(id);
        CatalogScopes.requireWrite(
                access,
                principal,
                existing.isPresent(),
                existing.map(AgentDefinition::projectId).orElse(null),
                body.projectId());
        AgentDefinition agent = id.equals(body.id())
                ? body
                : new AgentDefinition(
                        id,
                        body.name(),
                        body.version(),
                        body.description(),
                        body.prompt(),
                        body.rules(),
                        body.skills(),
                        body.mcpServers(),
                        body.hooks(),
                        body.executorId(),
                        body.model(),
                        body.effort(),
                        body.loop(),
                        body.interventions(),
                        body.artifactOutputs(),
                        body.verification(),
                        body.platformMcp(),
                        body.knowledgeBase(),
                        body.updatedAt(),
                        body.projectId());
        return catalog.saveAgent(agent);
    }

    @Operation(summary = "Delete a role; refused while an SOP node still uses it")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, java.security.Principal principal) {
        PathIds.require(id);
        store.agent(id)
                .ifPresent(existing -> access.requireProject(
                        principal, existing.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER));
        List<WorkflowDefinition> users = store.workflows().stream()
                .filter(w -> w.nodes().stream().anyMatch(n -> id.equals(n.agentId())))
                .toList();
        if (!users.isEmpty()) {
            // name only the SOPs the caller can see
            List<String> visible = users.stream()
                    .filter(w -> access.canSee(principal, w.projectId()))
                    .map(WorkflowDefinition::id)
                    .toList();
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "role '" + id + "' is used by "
                            + (visible.isEmpty() ? "other SOPs" : "SOP " + String.join(", ", visible)));
        }
        return store.deleteAgent(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @Operation(summary = "Saved revisions of a role, newest first")
    @GetMapping("/{id}/revisions")
    List<DefinitionRevision> revisions(@PathVariable String id, java.security.Principal principal) {
        get(id, principal);
        return revisions.list(DefinitionRevision.AGENT, id);
    }

    @GetMapping("/{id}/revisions/{revision}")
    DefinitionRevision revision(
            @PathVariable String id, @PathVariable int revision, java.security.Principal principal) {
        get(id, principal);
        return revisions
                .find(DefinitionRevision.AGENT, id, revision, AgentDefinition.class)
                .orElseThrow(() -> new RunNotFoundException("revision " + revision + " of '" + id + "' not found"));
    }

    @Operation(summary = "Restore a revision: its definition is saved as the newest revision")
    @PostMapping("/{id}/revisions/{revision}/restore")
    AgentDefinition restore(@PathVariable String id, @PathVariable int revision, java.security.Principal principal) {
        return put(id, (AgentDefinition) revision(id, revision, principal).payload(), principal);
    }
}
