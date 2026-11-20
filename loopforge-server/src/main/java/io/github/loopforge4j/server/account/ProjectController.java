package io.github.loopforge4j.server.account;

import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.engine.RunStateException;
import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Projects and members (upstream {@code projectHandlers}). */
@Tag(name = "Projects")
@RestController
@RequestMapping("/api/v1/projects")
class ProjectController {

    private final ProjectStore projects;
    private final UserStore users;
    private final AccessPolicy access;

    ProjectController(ProjectStore projects, UserStore users, AccessPolicy access) {
        this.projects = projects;
        this.users = users;
        this.access = access;
    }

    record ProjectBody(
            @NotBlank @Size(max = 500) String name,
            @Size(max = 4000) String description) {}

    record MemberBody(@NotNull ProjectRole role) {}

    @GetMapping
    List<Project> list(Principal principal) {
        CurrentUser user = access.current(principal);
        if (user.admin()) {
            return projects.list();
        }
        if (user.agent() != null) {
            // an agent sees exactly its run's project
            java.util.Set<String> visible = access.rolesOf(user).keySet();
            return projects.list().stream()
                    .filter(p -> visible.contains(p.id()))
                    .toList();
        }
        return projects.listFor(user.id());
    }

    @GetMapping("/{id}")
    Project get(@PathVariable String id, Principal principal) {
        access.requireProject(principal, id, ProjectRole.VIEWER);
        return projects.find(id).orElseThrow(() -> new RunNotFoundException("project '" + id + "' not found"));
    }

    /** Creating a project needs an administrator; updating it needs an owner. */
    @PutMapping("/{id}")
    Project put(@PathVariable String id, @Valid @RequestBody ProjectBody body, Principal principal) {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new WorkflowValidationException(
                    List.of("project id must be 1-100 characters of A-Z, a-z, 0-9, '.', '_' or '-'"));
        }
        if (projects.find(id).isEmpty()) {
            access.requireAdmin(principal);
            CurrentUser user = access.current(principal);
            Project created = projects.create(id, body.name(), body.description(), user.username());
            if (!user.service()) {
                projects.putMember(id, user.id(), ProjectRole.OWNER);
            }
            return created;
        }
        access.requireProject(principal, id, ProjectRole.OWNER);
        return projects.update(id, body.name(), body.description());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, Principal principal) {
        access.requireAdmin(principal);
        return projects.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/{id}/members")
    List<ProjectMember> members(@PathVariable String id, Principal principal) {
        access.requireProject(principal, id, ProjectRole.VIEWER);
        return projects.members(id);
    }

    @PutMapping("/{id}/members/{userId}")
    List<ProjectMember> putMember(
            @PathVariable String id,
            @PathVariable String userId,
            @Valid @RequestBody MemberBody body,
            Principal principal) {
        access.requireProject(principal, id, ProjectRole.OWNER);
        projects.find(id).orElseThrow(() -> new RunNotFoundException("project '" + id + "' not found"));
        User user = users.byId(userId)
                .or(() -> users.byUsername(AccountService.normalize(userId)))
                .orElseThrow(() -> new RunNotFoundException("user '" + userId + "' not found"));
        guardLastOwner(id, user.id(), body.role());
        projects.putMember(id, user.id(), body.role());
        return projects.members(id);
    }

    @DeleteMapping("/{id}/members/{userId}")
    ResponseEntity<Void> removeMember(@PathVariable String id, @PathVariable String userId, Principal principal) {
        access.requireProject(principal, id, ProjectRole.OWNER);
        guardLastOwner(id, userId, null);
        return projects.removeMember(id, userId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private void guardLastOwner(String projectId, String userId, ProjectRole newRole) {
        List<ProjectMember> members = projects.members(projectId);
        boolean isOwner = members.stream().anyMatch(m -> m.userId().equals(userId) && m.role() == ProjectRole.OWNER);
        long owners =
                members.stream().filter(m -> m.role() == ProjectRole.OWNER).count();
        if (isOwner && owners <= 1 && newRole != ProjectRole.OWNER) {
            throw new RunStateException("a project needs at least one owner");
        }
    }
}
