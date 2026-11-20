package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.engine.WorkflowValidationException;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import java.security.Principal;
import java.util.List;
import java.util.Objects;

/**
 * Who may write a project-scoped catalog asset (roles, rules, skills): administrators write shared
 * ones, a project's maintainers write the project's own. An asset stays in the project it was
 * created in, so the SOPs and roles of that project never lose what they use.
 */
final class CatalogScopes {

    private CatalogScopes() {}

    /**
     * @param exists whether the asset is already stored
     * @param current its owning project when stored ({@code null}: shared)
     * @param requested the owning project the write asks for ({@code null}: shared)
     */
    static void requireWrite(
            AccessPolicy access, Principal principal, boolean exists, String current, String requested) {
        if (exists) {
            access.requireProject(principal, current, ProjectRole.MAINTAINER);
            if (!Objects.equals(blankToNull(current), blankToNull(requested))) {
                throw new WorkflowValidationException(List.of("不能更改所属项目，请在目标项目中新建"));
            }
            return;
        }
        access.requireProject(principal, requested, ProjectRole.MAINTAINER);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
