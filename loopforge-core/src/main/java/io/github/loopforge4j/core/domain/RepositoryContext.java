package io.github.loopforge4j.core.domain;

/**
 * A repository frozen into a run (port of upstream {@code domain.RepositoryContext}). Every bound
 * repository is checked out into its own first-level {@link #workspaceDir()} of the run workspace;
 * SOP deliverables stay at the workspace root.
 *
 * @param workBranch branch the run commits to; {@code null} for read-only checkouts
 * @param requireExistingWorkBranch continue the named remote branch; fail when it is missing
 * @param createNewWorkBranch create the work branch; fail when it already exists remotely
 * @param readOnly check the base branch out without a work branch (never committed or pushed)
 * @param baseSha commit of the base branch the run started from (recorded at checkout)
 */
public record RepositoryContext(
        String repositoryId,
        String name,
        RepositoryProvider provider,
        String cloneUrl,
        String githubRepo,
        String baseBranch,
        String workBranch,
        String workspaceDir,
        boolean readOnly,
        boolean requireExistingWorkBranch,
        boolean createNewWorkBranch,
        String baseSha) {

    public static RepositoryContext of(RepositoryDefinition repository, String baseBranch) {
        return new RepositoryContext(
                repository.id(),
                repository.name(),
                repository.provider(),
                repository.cloneUrl(),
                repository.githubRepo(),
                baseBranch == null || baseBranch.isBlank() ? repository.defaultBranch() : baseBranch,
                null,
                null,
                false,
                false,
                false,
                null);
    }

    /** Work branch this run may push to, or {@code null} for read-only checkouts. */
    public String writableWorkBranch() {
        return readOnly || workBranch == null || workBranch.isBlank() ? null : workBranch;
    }

    public RepositoryContext withWorkBranch(String branch) {
        return new RepositoryContext(
                repositoryId,
                name,
                provider,
                cloneUrl,
                githubRepo,
                baseBranch,
                branch,
                workspaceDir,
                readOnly,
                requireExistingWorkBranch,
                createNewWorkBranch,
                baseSha);
    }

    public RepositoryContext withWorkspaceDir(String dir) {
        return new RepositoryContext(
                repositoryId,
                name,
                provider,
                cloneUrl,
                githubRepo,
                baseBranch,
                workBranch,
                dir,
                readOnly,
                requireExistingWorkBranch,
                createNewWorkBranch,
                baseSha);
    }

    public RepositoryContext withBaseSha(String sha) {
        return new RepositoryContext(
                repositoryId,
                name,
                provider,
                cloneUrl,
                githubRepo,
                baseBranch,
                workBranch,
                workspaceDir,
                readOnly,
                requireExistingWorkBranch,
                createNewWorkBranch,
                sha);
    }

    public RepositoryContext withMode(boolean read, boolean requireExisting, boolean createNew) {
        return new RepositoryContext(
                repositoryId,
                name,
                provider,
                cloneUrl,
                githubRepo,
                baseBranch,
                read ? null : workBranch,
                workspaceDir,
                read,
                requireExisting,
                createNew,
                baseSha);
    }
}
