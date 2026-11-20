package io.github.loopforge4j.server.catalog;

import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.account.ProjectStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generic SOP templates: browse them and adopt one into a project. */
@Tag(name = "Generic templates")
@RestController
@RequestMapping("/api/v1/workflow-templates")
class GenericTemplateController {

    private final GenericTemplates templates;
    private final AccessPolicy access;
    private final ProjectStore projects;
    private final io.github.loopforge4j.core.store.CatalogStore store;

    GenericTemplateController(
            GenericTemplates templates,
            AccessPolicy access,
            ProjectStore projects,
            io.github.loopforge4j.core.store.CatalogStore store) {
        this.templates = templates;
        this.access = access;
        this.projects = projects;
        this.store = store;
    }

    @Operation(summary = "Generic SOP templates with the roles they use")
    @GetMapping
    List<Map<String, Object>> list() {
        return templates.templates().stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable String id) {
        return summary(templates.template(id));
    }

    private Map<String, Object> summary(WorkflowDefinition t) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", t.id());
        out.put("name", t.name());
        out.put("description", t.description() == null ? "" : t.description());
        out.put("tags", t.tags());
        out.put("sopLane", io.github.loopforge4j.core.domain.SopLanes.infer(t));
        out.put("status", t.status());
        out.put("nodes", t.nodes().size());
        out.put("sandboxProfileId", t.sandboxProfileId());
        out.put(
                "roles",
                templates.roles(t).stream()
                        .map(r -> Map.of(
                                "id",
                                r,
                                "name",
                                store.agent(r)
                                        .map(io.github.loopforge4j.core.domain.AgentDefinition::name)
                                        .orElse(r)))
                        .toList());
        out.put("preview", t.preview() != null);
        out.put("updatedAt", t.updatedAt());
        return out;
    }

    record AdoptRequest(
            @NotBlank @Size(max = 64) String projectId,
            @Size(max = 200) String id,
            @Size(max = 500) String name) {}

    @Operation(summary = "Copy a template and its roles into a project as an editable draft")
    @PostMapping("/{id}/adopt")
    ResponseEntity<WorkflowDefinition> adopt(
            @PathVariable String id, @Valid @RequestBody AdoptRequest body, Principal principal) {
        WorkflowDefinition template = templates.template(id);
        String project = body.projectId().strip();
        access.requireProject(principal, project, ProjectRole.MAINTAINER);
        if (projects.find(project).isEmpty()) {
            throw new RunNotFoundException("project '" + project + "' not found");
        }
        // the copy runs in the template's sandbox: members may only adopt into an isolated one
        access.requireIsolatedSandbox(principal, template);
        String target = body.id() == null || body.id().isBlank()
                ? GenericTemplates.defaultId(template.id(), project)
                : body.id().strip();
        var user = access.current(principal);
        WorkflowDefinition adopted = templates.adopt(template, project, target, body.name(), user.username());
        return ResponseEntity.status(HttpStatus.CREATED).body(adopted);
    }
}
