package io.github.loopforge4j.server.account;

import io.github.loopforge4j.core.domain.Run;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Who may do what (upstream project permissions). Administrators and the service token may do
 * everything. Project resources need a membership with a sufficient {@link ProjectRole}; runs
 * without a project belong to the account that started them. The global catalog (SOPs, roles,
 * assets) is readable by every account and writable by administrators.
 */
@Component
public class AccessPolicy {

    private final ProjectStore projects;
    private final io.github.loopforge4j.core.store.CatalogStore catalog;
    private final boolean membersRequireIsolation;
    private final java.util.Set<String> isolatedProfiles;

    public AccessPolicy(
            ProjectStore projects,
            io.github.loopforge4j.core.store.CatalogStore catalog,
            io.github.loopforge4j.server.config.LoopForgeProperties properties) {
        this.projects = projects;
        this.catalog = catalog;
        this.membersRequireIsolation = properties.security().membersRequireIsolation();
        this.isolatedProfiles = java.util.Set.copyOf(properties.security().isolatedProfiles());
    }

    /**
     * Non-administrators may only author or run SOPs in an isolated sandbox profile (unless the
     * deployment trusts its members): a local-sandbox script or agent runs as the server's OS user.
     */
    public void requireIsolatedSandbox(
            Principal principal, io.github.loopforge4j.core.domain.WorkflowDefinition workflow) {
        if (!membersRequireIsolation || current(principal).admin() || workflow == null) {
            return;
        }
        if (!isolatedProfiles.contains(workflow.sandboxProfileId())) {
            throw new AccessDeniedException("SOP '" + workflow.id() + "' uses sandbox profile '"
                    + workflow.sandboxProfileId() + "'; members may only use isolated profiles " + isolatedProfiles);
        }
    }

    /** The same rule for a sandbox profile chosen directly (conversation workspaces). */
    public void requireIsolatedProfile(Principal principal, String profileId) {
        if (!membersRequireIsolation || current(principal).admin()) {
            return;
        }
        if (!isolatedProfiles.contains(profileId)) {
            throw new AccessDeniedException(
                    "sandbox profile '" + profileId + "' is not isolated; members may only use " + isolatedProfiles);
        }
    }

    public void requireIsolatedSandbox(Principal principal, String workflowId) {
        if (workflowId != null) {
            requireIsolatedSandbox(principal, catalog.workflow(workflowId).orElse(null));
        }
    }

    /** Whether a project-scoped definition is visible: global ones to everybody, others to members. */
    public boolean canSee(Principal principal, String projectId) {
        CurrentUser user = current(principal);
        if (user.admin() || projectId == null || projectId.isBlank()) {
            return true;
        }
        return rolesOf(user).containsKey(projectId);
    }

    public CurrentUser current(Principal principal) {
        if (principal instanceof CurrentUser user) {
            return user;
        }
        if (principal instanceof org.springframework.security.core.Authentication auth
                && auth.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        throw new AccessDeniedException("authentication required");
    }

    public void requireAdmin(Principal principal) {
        if (!current(principal).admin()) {
            throw new AccessDeniedException("administrator rights required");
        }
    }

    /** Admin, or at least {@code role} in {@code projectId}. A {@code null} project needs admin. */
    public void requireProject(Principal principal, String projectId, ProjectRole role) {
        CurrentUser user = current(principal);
        if (user.admin()) {
            return;
        }
        if (projectId == null || projectId.isBlank()) {
            throw new AccessDeniedException("administrator rights required");
        }
        ProjectRole actual = rolesOf(user).get(projectId);
        if (actual == null || !actual.atLeast(role)) {
            throw new AccessDeniedException("project '" + projectId + "' requires role " + role.wire());
        }
    }

    public boolean canReadRun(Principal principal, Run run) {
        return allowed(principal, run, ProjectRole.VIEWER);
    }

    public void requireReadRun(Principal principal, Run run) {
        if (!canReadRun(principal, run)) {
            throw new AccessDeniedException("no access to run " + run.id());
        }
    }

    public void requireOperateRun(Principal principal, Run run) {
        if (!allowed(principal, run, ProjectRole.DEVELOPER)) {
            throw new AccessDeniedException("run " + run.id() + " requires developer access");
        }
    }

    /** Projects whose runs the account may list, {@code null} for all (administrators). */
    public Set<String> visibleProjects(Principal principal) {
        CurrentUser user = current(principal);
        return user.admin() ? null : rolesOf(user).keySet();
    }

    private boolean allowed(Principal principal, Run run, ProjectRole role) {
        CurrentUser user = current(principal);
        if (user.admin()) {
            return true;
        }
        String project = run.context().projectId();
        if (project == null || project.isBlank()) {
            // only administrators start runs without a project, so only they may see them
            return false;
        }
        Map<String, ProjectRole> roles = rolesOf(user);
        ProjectRole actual = roles.get(project);
        return actual != null && actual.atLeast(role);
    }

    /** Project roles of the caller: an agent views exactly its run's project. */
    public Map<String, ProjectRole> rolesOf(CurrentUser user) {
        if (user.agent() != null && user.agent().delegated()) {
            Map<String, ProjectRole> own = user.id() == null ? Map.of() : projects.rolesOf(user.id());
            String project = user.agent().projectId();
            if (project == null || project.isBlank()) {
                return own;
            }
            return own.containsKey(project) ? Map.of(project, own.get(project)) : Map.of();
        }
        if (user.agent() != null) {
            String project = user.agent().projectId();
            return project == null || project.isBlank() ? Map.of() : Map.of(project, ProjectRole.VIEWER);
        }
        return user.id() == null ? Map.of() : projects.rolesOf(user.id());
    }

    /** Thrown for authenticated callers without permission (403). */
    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }
}
