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

    WorkflowController(CatalogStore store, CatalogService catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping("/workflows")
    List<WorkflowDefinition> list() {
        return store.workflows();
    }

    @GetMapping("/workflows/{id}")
    WorkflowDefinition get(@PathVariable String id) {
        return store.workflow(id).orElseThrow(() -> new RunNotFoundException("workflow '" + id + "' not found"));
    }

    @Operation(summary = "Create or replace a workflow; the path id wins over the body id")
    @PutMapping("/workflows/{id}")
    WorkflowDefinition put(@PathVariable String id, @RequestBody WorkflowDefinition body) {
        return catalog.saveWorkflow(withId(body, id));
    }

    @DeleteMapping("/workflows/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        return store.deleteWorkflow(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
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
    CatalogBundle export() {
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
                body.updatedAt());
    }
}
