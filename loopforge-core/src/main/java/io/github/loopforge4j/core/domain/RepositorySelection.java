package io.github.loopforge4j.core.domain;

/** A repository chosen when starting a run. Empty branches use the repository default / a generated name. */
public record RepositorySelection(String repositoryId, String baseBranch, String workBranch, BranchMode mode) {

    public RepositorySelection {
        mode = mode == null ? BranchMode.AUTO : mode;
    }

    public static RepositorySelection of(String repositoryId) {
        return new RepositorySelection(repositoryId, null, null, BranchMode.AUTO);
    }
}
