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

    public AccessPolicy(ProjectStore projects) {
        this.projects = projects;
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
        ProjectRole actual = projects.rolesOf(user.id()).get(projectId);
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
        return user.admin() ? null : projects.rolesOf(user.id()).keySet();
    }

    private boolean allowed(Principal principal, Run run, ProjectRole role) {
        CurrentUser user = current(principal);
        if (user.admin()) {
            return true;
        }
        String project = run.context().projectId();
        if (project == null || project.isBlank()) {
            return user.username().equals(run.context().triggeredBy());
        }
        Map<String, ProjectRole> roles = projects.rolesOf(user.id());
        ProjectRole actual = roles.get(project);
        return actual != null && actual.atLeast(role);
    }

    /** Thrown for authenticated callers without permission (403). */
    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }
}
