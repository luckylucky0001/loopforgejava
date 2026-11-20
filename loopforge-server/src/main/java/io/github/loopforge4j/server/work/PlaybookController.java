package io.github.loopforge4j.server.work;

import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A project's named SOP chains (upstream {@code sopPlaybooks}); new requirements start with the default. */
@Tag(name = "requirements")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/playbooks")
class PlaybookController {

    private final RequirementService requirements;
    private final AccessPolicy access;

    PlaybookController(RequirementService requirements, AccessPolicy access) {
        this.requirements = requirements;
        this.access = access;
    }

    @Operation(summary = "The project's SOP playbooks")
    @GetMapping
    List<SopPlaybook> list(@PathVariable String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return requirements.playbooks(projectId);
    }

    @Operation(summary = "Replace the project's SOP playbooks (exactly one is the default)")
    @PutMapping
    List<SopPlaybook> save(@PathVariable String projectId, @RequestBody List<SopPlaybook> body, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        return requirements.savePlaybooks(projectId, body);
    }
}
