package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.WorkflowValidator;
import io.github.loopforge4j.core.store.CatalogStore;
import io.github.loopforge4j.server.catalog.CatalogBundle;
import io.github.loopforge4j.server.catalog.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SOP definitions (upstream {@code catalogHandlers}, workflow part). */
@Tag(name = "Workflows")
@RestController
@RequestMapping("/api/v1")
class WorkflowController {

    private final CatalogStore store;
    private final CatalogService catalog;
    private final io.github.loopforge4j.server.persistence.DefinitionRevisionStore revisions;

    private final io.github.loopforge4j.server.account.AccessPolicy access;

    WorkflowController(
            CatalogStore store,
            CatalogService catalog,
            io.github.loopforge4j.server.persistence.DefinitionRevisionStore revisions,
            io.github.loopforge4j.server.account.AccessPolicy access) {
        this.access = access;
        this.store = store;
        this.catalog = catalog;
        this.revisions = revisions;
    }

    @GetMapping("/workflows")
    List<WorkflowDefinition> list(java.security.Principal principal) {
        return store.workflows().stream()
                .filter(w -> access.canSee(principal, w.projectId()))
                .toList();
    }

    @GetMapping("/workflows/{id}")
    WorkflowDefinition get(@PathVariable String id, java.security.Principal principal) {
        return store.workflow(id)
                .filter(w -> access.canSee(principal, w.projectId()))
                .orElseThrow(() -> new RunNotFoundException("workflow '" + id + "' not found"));
    }

    @Operation(summary = "Create or replace a workflow; the path id wins over the body id")
    @PutMapping("/workflows/{id}")
    WorkflowDefinition put(
            @PathVariable String id, @RequestBody WorkflowDefinition body, java.security.Principal principal) {
        // project SOPs belong to the project's maintainers; global ones to administrators
        store.workflow(id)
                .ifPresent(existing -> access.requireProject(
                        principal, existing.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER));
        access.requireProject(principal, body.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER);
        access.requireIsolatedSandbox(principal, body);
        return catalog.saveWorkflow(withId(body, id));
    }

    @DeleteMapping("/workflows/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, java.security.Principal principal) {
        store.workflow(id)
                .ifPresent(existing -> access.requireProject(
                        principal, existing.projectId(), io.github.loopforge4j.server.account.ProjectRole.MAINTAINER));
        return store.deleteWorkflow(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @Operation(summary = "Saved revisions of a workflow, newest first")
    @GetMapping("/workflows/{id}/revisions")
    List<io.github.loopforge4j.server.persistence.DefinitionRevision> revisions(
            @PathVariable String id, java.security.Principal principal) {
        get(id, principal);
        return revisions.list(io.github.loopforge4j.server.persistence.DefinitionRevision.WORKFLOW, id);
    }

    @GetMapping("/workflows/{id}/revisions/{revision}")
    io.github.loopforge4j.server.persistence.DefinitionRevision revision(
            @PathVariable String id, @PathVariable int revision, java.security.Principal principal) {
        get(id, principal);
        return revisions
                .find(
                        io.github.loopforge4j.server.persistence.DefinitionRevision.WORKFLOW,
                        id,
                        revision,
                        WorkflowDefinition.class)
                .orElseThrow(() -> new RunNotFoundException("revision " + revision + " of '" + id + "' not found"));
    }

    @Operation(summary = "Restore a revision: its definition is saved as the newest revision")
    @PostMapping("/workflows/{id}/revisions/{revision}/restore")
    WorkflowDefinition restore(@PathVariable String id, @PathVariable int revision, java.security.Principal principal) {
        WorkflowDefinition old =
                (WorkflowDefinition) revision(id, revision, principal).payload();
        return put(id, old, principal);
    }

    @Operation(summary = "Validate and publish a workflow (version + 1)")
    @PostMapping("/workflows/{id}/publish")
    WorkflowDefinition publish(@PathVariable String id, java.security.Principal principal) {
        WorkflowDefinition current = get(id, principal);
        return put(
                id,
                copy(
                        current,
                        id,
                        current.name(),
                        current.projectId(),
                        io.github.loopforge4j.core.domain.WorkflowStatus.PUBLISHED,
                        current.version() + 1),
                principal);
    }

    record CloneRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 200)
            String id,

            @jakarta.validation.constraints.Size(max = 500) String name,
            String projectId) {}

    @Operation(summary = "Copy a workflow as a new draft (optionally into another project)")
    @PostMapping("/workflows/{id}/clone")
    ResponseEntity<WorkflowDefinition> cloneWorkflow(
            @PathVariable String id,
            @jakarta.validation.Valid @RequestBody CloneRequest body,
            java.security.Principal principal) {
        WorkflowDefinition source = get(id, principal);
        String target = body.id().strip();
        if (store.workflow(target).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "workflow '" + target + "' already exists");
        }
        String project =
                body.projectId() == null ? source.projectId() : body.projectId().isBlank() ? null : body.projectId();
        WorkflowDefinition copy = copy(
                source,
                target,
                body.name() == null || body.name().isBlank()
                        ? source.name() + " 副本"
                        : body.name().strip(),
                project,
                io.github.loopforge4j.core.domain.WorkflowStatus.DRAFT,
                1);
        return ResponseEntity.status(HttpStatus.CREATED).body(put(target, copy, principal));
    }

    private static WorkflowDefinition copy(
            WorkflowDefinition w,
            String id,
            String name,
            String projectId,
            io.github.loopforge4j.core.domain.WorkflowStatus status,
            int version) {
        return new WorkflowDefinition(
                id,
                name,
                version,
                w.revision(),
                status,
                projectId,
                w.owner(),
                w.tags(),
                w.description(),
                w.sandboxProfileId(),
                w.nodes(),
                w.edges(),
                null,
                null,
                w.preview());
    }

    @Operation(summary = "Validate a workflow without saving it")
    @PostMapping("/workflows/validate")
    Map<String, Object> validate(@RequestBody WorkflowDefinition body) {
        List<String> problems = WorkflowValidator.problems(body);
        return Map.of("valid", problems.isEmpty(), "problems", problems);
    }

    @Operation(summary = "Import agents and workflows; existing ids are kept unless overwrite=true")
    @PostMapping("/catalog/import")
    @ResponseStatus(HttpStatus.OK)
    Map<String, Object> importBundle(
            @RequestBody CatalogBundle bundle, @RequestParam(defaultValue = "false") boolean overwrite) {
        return Map.of("written", catalog.importBundle(bundle, overwrite));
    }

    @Operation(summary = "Export the whole catalog as an importable bundle")
    @GetMapping("/catalog/export")
    CatalogBundle export(java.security.Principal principal) {
        access.requireAdmin(principal);
        return new CatalogBundle(
                store.agents(),
                store.workflows(),
                store.repositories(),
                store.assets(io.github.loopforge4j.core.domain.AssetKind.RULE),
                store.assets(io.github.loopforge4j.core.domain.AssetKind.SKILL),
                store.assets(io.github.loopforge4j.core.domain.AssetKind.MCP_SERVER));
    }

    private static WorkflowDefinition withId(WorkflowDefinition body, String id) {
        if (id.equals(body.id())) {
            return body;
        }
        return new WorkflowDefinition(
                id,
                body.name(),
                body.version(),
                body.revision(),
                body.status(),
                body.projectId(),
                body.owner(),
                body.tags(),
                body.description(),
                body.sandboxProfileId(),
                body.nodes(),
                body.edges(),
                body.createdAt(),
                body.updatedAt(),
                body.preview());
    }
}
