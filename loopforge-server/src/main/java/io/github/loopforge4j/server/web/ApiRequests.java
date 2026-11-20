package io.github.loopforge4j.server.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of the run API. */
final class ApiRequests {

    static final int MAX_INPUT = 200_000;
    static final int MAX_CONTEXT = 8_000;

    private ApiRequests() {}

    record StartRun(
            @NotBlank @Size(max = 200) String workflowId,
            @Size(max = MAX_INPUT) String input,
            @Size(max = 200) String projectId,
            @Size(max = 20) List<@Valid RepositorySelection> repositories,
            @Size(max = 64) String runnerId) {

        List<io.github.loopforge4j.core.domain.RepositorySelection> selections() {
            return repositories == null
                    ? List.of()
                    : repositories.stream()
                            .map(r -> new io.github.loopforge4j.core.domain.RepositorySelection(
                                    r.repositoryId(), r.baseBranch(), r.workBranch(), r.mode()))
                            .toList();
        }
    }

    /** One repository a run works on; branches default to the repository and run settings. */
    record RepositorySelection(
            @NotBlank @Size(max = 200) String repositoryId,
            @Size(max = 250) String baseBranch,
            @Size(max = 250) String workBranch,
            io.github.loopforge4j.core.domain.BranchMode mode) {}

    record HumanContext(@Size(max = MAX_CONTEXT) String humanContext) {}

    record Retry(
            @Size(max = 200) String nodeId,
            @Size(max = 2_000) String reason,
            @Size(max = MAX_CONTEXT) String humanContext) {}

    record Reject(
            @Size(max = 200) String targetId,
            @NotBlank @Size(max = 2_000) String reason,
            @Size(max = MAX_CONTEXT) String humanContext) {}
}
