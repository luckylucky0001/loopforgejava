package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.Principal;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Requirements of a project (upstream issues of kind requirement). */
@Tag(name = "Requirements")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/requirements")
class RequirementController {

    private final RequirementService requirements;
    private final RequirementStore store;
    private final AccessPolicy access;
    private final ClarificationService clarifications;

    RequirementController(
            RequirementService requirements,
            RequirementStore store,
            AccessPolicy access,
            ClarificationService clarifications) {
        this.requirements = requirements;
        this.store = store;
        this.access = access;
        this.clarifications = clarifications;
    }

    record StartRequest(
            @Size(max = 200) String workflowId,
            @Size(max = 8_000) String input,
            Boolean rerun) {}

    /** A playbook to apply and/or an explicit SOP chain. */
    record SopsRequest(@Size(max = 100) String playbookId, List<SopBinding> bindings) {}

    @Operation(summary = "The requirement's SOP lanes with node stages, usage and overall status")
    @GetMapping("/{number}/snapshot")
    RequirementSnapshot snapshot(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return requirements.snapshot(projectId, number);
    }

    @Operation(summary = "The requirement's SOP chain, its playbook and the project's playbooks")
    @GetMapping("/{number}/sops")
    java.util.Map<String, Object> sops(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return requirements.sops(projectId, number);
    }

    @Operation(
            summary = "Apply a playbook and/or set the requirement's SOP chain (order, owner, dependencies, handoff)")
    @PutMapping("/{number}/sops")
    Requirement setSops(
            @PathVariable String projectId,
            @PathVariable int number,
            @Valid @RequestBody SopsRequest body,
            Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        return requirements.setSops(projectId, number, body.playbookId(), body.bindings());
    }

    @GetMapping
    List<Requirement> list(
            @PathVariable String projectId, @RequestParam(required = false) String status, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return store.list(projectId, status);
    }

    @PostMapping
    ResponseEntity<Requirement> create(
            @PathVariable String projectId, @RequestBody Requirement body, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        Requirement created =
                requirements.create(projectId, body, access.current(principal).username());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/requirements/" + created.number()))
                .body(created);
    }

    @GetMapping("/{number}")
    Requirement get(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return requirements.require(projectId, number);
    }

    @PutMapping("/{number}")
    Requirement update(
            @PathVariable String projectId,
            @PathVariable int number,
            @RequestBody Requirement body,
            Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        return requirements.update(projectId, number, body);
    }

    @DeleteMapping("/{number}")
    ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable int number, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        Requirement requirement = requirements.require(projectId, number);
        store.delete(requirement.id());
        clarifications.requirementDeleted(requirement.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Start one SOP lane of the requirement on its repositories (work branch feature/requirement-N-…);"
                    + " a lane that already ran needs rerun=true")
    @PostMapping("/{number}/runs")
    ResponseEntity<Run> start(
            @PathVariable String projectId,
            @PathVariable int number,
            @Valid @RequestBody(required = false) StartRequest body,
            Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        access.requireIsolatedSandbox(
                principal,
                body != null && body.workflowId() != null && !body.workflowId().isBlank()
                        ? body.workflowId()
                        : requirements.require(projectId, number).workflowId());
        Run run = requirements.start(
                projectId,
                number,
                body == null ? null : body.workflowId(),
                body == null ? null : body.input(),
                access.current(principal).username(),
                body != null && Boolean.TRUE.equals(body.rerun()));
        return ResponseEntity.status(HttpStatus.CREATED).body(run);
    }
}
