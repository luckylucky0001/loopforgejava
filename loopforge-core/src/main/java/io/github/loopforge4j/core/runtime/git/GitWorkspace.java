package io.github.loopforge4j.core.runtime.git;

import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.sandbox.CommandSpec;
import io.github.loopforge4j.core.runtime.sandbox.ExecResult;
import io.github.loopforge4j.core.runtime.sandbox.LineListener;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Checks a run repository out into its workspace directory (port of upstream {@code
 * prepareRepository}): clone the base branch, then continue, create or reuse the work branch.
 * Re-preparing an existing checkout (retry after restart) only refreshes refs and never discards
 * work.
 */
public final class GitWorkspace {

    /** Conservative git ref rule: no spaces, no "..", no leading "-" or "/", no ".lock" suffix. */
    private static final Pattern BRANCH =
            Pattern.compile("^(?!-)(?!.*\\.\\.)(?!.*//)(?!.*@\\{)(?!.*\\.lock$)" + "[A-Za-z0-9._/-]{1,200}(?<![/.])$");

    private static final Duration GIT_TIMEOUT = Duration.ofMinutes(10);

    private final Sandbox sandbox;
    private final Map<String, String> environment;
    private final Cancellation cancellation;

    public GitWorkspace(Sandbox sandbox, Map<String, String> environment, Cancellation cancellation) {
        this.sandbox = sandbox;
        this.environment = environment;
        this.cancellation = cancellation;
    }

    public static boolean validBranch(String branch) {
        return branch != null && BRANCH.matcher(branch).matches();
    }

    /** Branch names that belong to shared lines of work and may never be used as a work branch. */
    public static boolean sharedBranch(String branch, String baseBranch) {
        return branch.equals(baseBranch) || branch.equals("main") || branch.equals("master");
    }

    /** Clones or refreshes the checkout; returns the context with the recorded base commit. */
    public RepositoryContext prepare(RepositoryContext repository) throws GitException, CancelledException {
        String dir = repository.workspaceDir();
        if (!validBranch(repository.baseBranch())) {
            throw new GitException("invalid base branch '" + repository.baseBranch() + "'");
        }
        String work = repository.writableWorkBranch();
        if (work != null && (!validBranch(work) || sharedBranch(work, repository.baseBranch()))) {
            throw new GitException("work branch '" + work + "' is invalid or a shared branch");
        }
        if (Files.isDirectory(sandbox.workspace().resolve(dir).resolve(".git"))) {
            return resume(repository);
        }
        clone(repository);
        String baseSha = git(dir, "rev-parse", "HEAD").strip();
        if (work != null) {
            checkoutWorkBranch(repository, work);
        }
        return repository.withBaseSha(baseSha);
    }

    private void clone(RepositoryContext repository) throws GitException, CancelledException {
        String dir = repository.workspaceDir();
        String url = repository.cloneUrl();
        ExecResult clone = run(
                null,
                List.of(
                        "git",
                        "clone",
                        "--quiet",
                        "--single-branch",
                        "--branch",
                        repository.baseBranch(),
                        "--",
                        url,
                        dir));
        if (clone.succeeded()) {
            return;
        }
        // A brand-new repository has no branches: publish the base branch with an empty commit.
        ExecResult heads = run(null, List.of("git", "ls-remote", "--heads", "--", url));
        if (!heads.succeeded() || !heads.output().isBlank() || repository.readOnly()) {
            throw new GitException("git clone of " + repository.name() + " failed: " + tail(clone));
        }
        deleteQuietly(dir);
        git(null, "init", "--quiet", "--initial-branch=" + repository.baseBranch(), dir);
        git(dir, "remote", "add", "--", "origin", url);
        git(
                dir,
                "-c",
                "user.name=LoopForge4J",
                "-c",
                "user.email=loopforge4j@users.noreply.local",
                "commit",
                "--quiet",
                "--allow-empty",
                "-m",
                "Initialize repository");
        git(dir, "push", "--quiet", "origin", "HEAD:refs/heads/" + repository.baseBranch());
        git(dir, "fetch", "--quiet", "origin", repository.baseBranch());
        git(dir, "branch", "--quiet", "--set-upstream-to=origin/" + repository.baseBranch());
    }

    private void checkoutWorkBranch(RepositoryContext repository, String work) throws GitException, CancelledException {
        String dir = repository.workspaceDir();
        boolean remote = remoteBranchExists(dir, work);
        if (repository.requireExistingWorkBranch() && !remote) {
            throw new GitException("existing work branch '" + work + "' does not exist on the remote");
        }
        if (repository.createNewWorkBranch() && remote) {
            throw new GitException("work branch '" + work + "' already exists on the remote; continue it instead");
        }
        // The single-branch clone only maps the base branch; register the work branch so it can be
        // tracked and fetched (upstream does the same before checking out an existing branch).
        git(dir, "config", "--add", "remote.origin.fetch", "+refs/heads/" + work + ":refs/remotes/origin/" + work);
        if (remote) {
            git(dir, "fetch", "--quiet", "origin", work);
            git(dir, "checkout", "--quiet", "-B", work, "--track", "origin/" + work);
        } else {
            git(dir, "checkout", "--quiet", "-b", work);
        }
    }

    /** Existing checkout: refresh refs, keep local work, make sure the work branch is checked out. */
    private RepositoryContext resume(RepositoryContext repository) throws GitException, CancelledException {
        String dir = repository.workspaceDir();
        run(dir, List.of("git", "fetch", "--quiet", "origin"));
        String work = repository.writableWorkBranch();
        if (work != null) {
            String current = git(dir, "branch", "--show-current").strip();
            if (!work.equals(current)) {
                if (run(dir, List.of("git", "rev-parse", "--verify", "--quiet", "refs/heads/" + work))
                        .succeeded()) {
                    git(dir, "checkout", "--quiet", work);
                } else {
                    checkoutWorkBranch(repository, work);
                }
            }
        }
        if (repository.baseSha() != null) {
            return repository;
        }
        ExecResult base = run(dir, List.of("git", "rev-parse", "origin/" + repository.baseBranch()));
        return base.succeeded() ? repository.withBaseSha(base.output().strip()) : repository;
    }

    /**
     * Moves a read-only scratch checkout (requirement clarification) to the fetched tip of its base
     * branch, discarding anything the agent left in it.
     */
    public void syncReadOnly(RepositoryContext repository) throws GitException, CancelledException {
        if (!repository.readOnly()) {
            throw new IllegalArgumentException("only read-only checkouts can be reset");
        }
        git(repository.workspaceDir(), "reset", "--hard", "--quiet", "origin/" + repository.baseBranch());
    }

    /** Remote tip of {@code branch} and how many commits it carries beyond {@code base} (MR gate). */
    public PushedWork pushedWork(RepositoryContext repository) throws GitException, CancelledException {
        String dir = repository.workspaceDir();
        String work = repository.writableWorkBranch();
        ExecResult refs = run(dir, List.of("git", "ls-remote", "origin", "refs/heads/" + work));
        if (!refs.succeeded()) {
            throw new GitException("cannot read the remote work branch " + work + ": " + tail(refs));
        }
        String line = refs.output().strip();
        if (line.isEmpty()) {
            return new PushedWork(null, 0);
        }
        String tip = line.split("\\s+")[0];
        if (!run(dir, List.of("git", "cat-file", "-e", tip + "^{commit}")).succeeded()) {
            git(dir, "fetch", "--quiet", "origin", "refs/heads/" + work);
        }
        run(dir, List.of("git", "fetch", "--quiet", "origin", repository.baseBranch()));
        String ahead = git(dir, "rev-list", "--count", tip, "--not", "refs/remotes/origin/" + repository.baseBranch());
        return new PushedWork(tip, Integer.parseInt(ahead.strip()));
    }

    /** Work branch tip on origin and commits beyond the base branch. */
    public record PushedWork(String tip, int ahead) {}

    private boolean remoteBranchExists(String dir, String branch) throws GitException, CancelledException {
        ExecResult result = run(dir, List.of("git", "ls-remote", "--heads", "origin", "refs/heads/" + branch));
        if (!result.succeeded()) {
            throw new GitException("cannot list remote branches: " + tail(result));
        }
        return !result.output().isBlank();
    }

    private String git(String dir, String... args) throws GitException, CancelledException {
        List<String> argv = new ArrayList<>(args.length + 1);
        argv.add("git");
        argv.addAll(List.of(args));
        ExecResult result = run(dir, argv);
        if (!result.succeeded()) {
            throw new GitException("git " + args[0] + " failed: " + tail(result));
        }
        return result.output();
    }

    private ExecResult run(String dir, List<String> argv) throws GitException, CancelledException {
        try {
            return sandbox.exec(
                    new CommandSpec(argv, dir, environment, null, GIT_TIMEOUT, "git"), LineListener.NONE, cancellation);
        } catch (IOException e) {
            throw new GitException("cannot run git: " + e.getMessage());
        }
    }

    private void deleteQuietly(String dir) {
        try {
            var path = sandbox.workspace().resolve(dir);
            if (Files.exists(path)) {
                try (var walk = Files.walk(path)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                }
            }
        } catch (IOException e) {
            // best effort; git init reports a non-empty directory
        }
    }

    private static String tail(ExecResult result) {
        String text = GitEnvironment.redact(result.output().strip());
        return text.length() <= 800 ? text : "…" + text.substring(text.length() - 800);
    }
}
