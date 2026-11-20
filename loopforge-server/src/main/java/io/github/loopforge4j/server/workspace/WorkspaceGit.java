package io.github.loopforge4j.server.workspace;

import io.github.loopforge4j.core.engine.RunEngine;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only Git views of a workspace repository (upstream status, diff, log and commit views).
 *
 * <p>The repository was written by agents, so Git runs inside the run's own sandbox: whatever its
 * configuration, attributes or submodules ask for stays within that sandbox. Signatures, hooks,
 * fsmonitor, external diff, text conversion and submodules are switched off besides, and paths are
 * literal (no pathspec magic).
 */
final class WorkspaceGit {

    static final int LOG_LIMIT = 50;
    static final Duration TIMEOUT = Duration.ofSeconds(8);
    static final int MAX_OUTPUT = WorkspaceFiles.FILE_LIMIT + 1;

    private static final List<String> BASE = List.of(
            "git",
            "--no-optional-locks",
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.hooksPath=/dev/null",
            "-c",
            "core.pager=cat",
            "-c",
            "diff.external=",
            "-c",
            "log.showSignature=false",
            "-c",
            "gpg.program=false",
            "-c",
            "gpg.ssh.program=false",
            "-c",
            "gpg.x509.program=false",
            "-c",
            "safe.directory=*");

    private static final Map<String, String> ENV = Map.of(
            "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_CONFIG_GLOBAL", "/dev/null",
            "GIT_TERMINAL_PROMPT", "0",
            "GIT_OPTIONAL_LOCKS", "0",
            "GIT_LITERAL_PATHSPECS", "1");

    /** A Git result; {@code ok} is false when Git failed. */
    record Output(boolean ok, String text) {}

    record Change(String path, String repository, String index, String worktree) {}

    record Commit(String id, List<String> parentIds, String message, String author, long timestamp) {}

    record CommitFile(String path, String status) {}

    private final RunEngine engine;
    private final String runId;
    private final Path root;

    WorkspaceGit(RunEngine engine, String runId, Path root) {
        this.engine = engine;
        this.runId = runId;
        this.root = root;
    }

    /** Why this directory is not shown as a repository (not one, or reached through a link). */
    Optional<String> unsafe(String label) {
        try {
            WorkspaceFiles.requireDirectory(root, label);
        } catch (WorkspaceFiles.WorkspaceException e) {
            return Optional.of("仓库目录不可用：" + e.getMessage());
        }
        try {
            gitDirectory(label);
        } catch (WorkspaceFiles.WorkspaceException e) {
            return Optional.of(e.missing ? "不是 Git 仓库" : "Git 目录不可用：" + e.getMessage());
        }
        return Optional.empty();
    }

    /** {@code .git} must be a real directory of the repository (no link, no gitdir file). */
    private void gitDirectory(String label) {
        String git = label.isEmpty() ? ".git" : label + "/.git";
        WorkspaceFiles.io(() -> {
            try (var base = io.github.loopforge4j.core.runtime.SafeFiles.openRoot(root)) {
                var dir = io.github.loopforge4j.core.runtime.SafeFiles.openDirectory(base, List.of(git.split("/")));
                io.github.loopforge4j.core.runtime.SafeFiles.closeQuietly(dir);
            }
            return null;
        });
    }

    Output run(String label, String... args) {
        List<String> argv = new ArrayList<>(BASE);
        argv.addAll(List.of(args));
        try {
            RunEngine.CommandOutput out = engine.workspaceCommand(runId, argv, label, ENV, TIMEOUT, MAX_OUTPUT);
            return new Output(out.ok() && !out.truncated(), out.stdout());
        } catch (IOException | IllegalStateException e) {
            return new Output(false, "");
        }
    }

    List<Change> changes(String label) {
        Output out = run(label, "status", "--porcelain=v1", "-z", "--untracked-files=all", "--ignore-submodules=all");
        List<Change> changes = new ArrayList<>();
        if (!out.ok()) {
            return changes;
        }
        String[] items = out.text().replace("\n", "").split("\0");
        for (int i = 0; i < items.length; i++) {
            String item = items[i];
            if (item.length() < 4) {
                continue;
            }
            String path = item.substring(3);
            String full = label.isEmpty() ? path : label + "/" + path;
            char index = item.charAt(0);
            char worktree = item.charAt(1);
            if (!WorkspaceFiles.sensitive(full)) {
                changes.add(new Change(
                        full, label.isEmpty() ? "workspace" : label, String.valueOf(index), String.valueOf(worktree)));
            }
            if (index == 'R' || index == 'C' || worktree == 'R' || worktree == 'C') {
                i++; // -z lists the original path of a rename or copy next
            }
        }
        return changes;
    }

    /** The diff against HEAD, without the sections of sensitive files. */
    String diff(String label, String relative) {
        Output out = relative.isEmpty()
                ? run(
                        label,
                        "diff",
                        "--no-ext-diff",
                        "--no-textconv",
                        "--no-color",
                        "--ignore-submodules=all",
                        "HEAD",
                        "--")
                : run(
                        label,
                        "diff",
                        "--no-ext-diff",
                        "--no-textconv",
                        "--no-color",
                        "--ignore-submodules=all",
                        "HEAD",
                        "--",
                        relative);
        return out.ok() ? withoutSensitive(out.text(), label) : "";
    }

    static String withoutSensitive(String diff, String label) {
        StringBuilder kept = new StringBuilder();
        boolean keep = true;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                int b = line.lastIndexOf(" b/");
                String path = b < 0 ? "" : line.substring(b + 3);
                String a = line.substring("diff --git ".length(), b < 0 ? line.length() : b);
                a = a.startsWith("a/") ? a.substring(2) : a;
                String prefix = label.isEmpty() ? "" : label + "/";
                keep = !WorkspaceFiles.sensitive(prefix + path) && !WorkspaceFiles.sensitive(prefix + a);
            }
            if (keep) {
                kept.append(line).append('\n');
            }
        }
        return kept.length() > 0 ? kept.substring(0, kept.length() - 1) : "";
    }

    /** The file at {@code ref} (HEAD or a commit id), or empty when it does not exist there. */
    Optional<String> blob(String label, String ref, String relative) {
        Output size = run(label, "cat-file", "-s", ref + ":" + relative);
        if (!size.ok()) {
            return Optional.empty();
        }
        long bytes;
        try {
            bytes = Long.parseLong(size.text().strip());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (bytes > WorkspaceFiles.FILE_LIMIT) {
            throw new WorkspaceFiles.WorkspaceException("file exceeds the 1 MiB preview limit", false);
        }
        Output out = run(label, "cat-file", "blob", ref + ":" + relative);
        if (!out.ok()) {
            return Optional.empty();
        }
        if (out.text().indexOf('\0') >= 0) {
            throw new WorkspaceFiles.WorkspaceException("binary files cannot be previewed", false);
        }
        // output arrives as lines: restore whether the file ended with a newline from its size
        String text = out.text();
        if (text.endsWith("\n") && text.getBytes(StandardCharsets.UTF_8).length == bytes + 1) {
            text = text.substring(0, text.length() - 1);
        }
        return Optional.of(text);
    }

    String branch(String label) {
        return run(label, "rev-parse", "--abbrev-ref", "HEAD").text().strip();
    }

    String head(String label) {
        return run(label, "rev-parse", "HEAD").text().strip();
    }

    List<Commit> log(String label) {
        Output out = run(
                label,
                "log",
                "--no-show-signature",
                "-n",
                Integer.toString(LOG_LIMIT),
                "--format=%H%x1f%P%x1f%s%x1f%an%x1f%at");
        List<Commit> commits = new ArrayList<>();
        if (!out.ok()) {
            return commits;
        }
        for (String line : out.text().split("\n")) {
            String[] parts = line.split("\u001f", -1);
            if (parts.length < 5) {
                continue;
            }
            List<String> parents =
                    parts[1].isBlank() ? List.of() : List.of(parts[1].strip().split("\\s+"));
            long at;
            try {
                at = Long.parseLong(parts[4].strip()) * 1000;
            } catch (NumberFormatException e) {
                at = 0;
            }
            commits.add(new Commit(parts[0].strip(), parents, parts[2], parts[3], at));
        }
        return commits;
    }

    List<CommitFile> commitFiles(String label, String id) {
        Output out = run(
                label,
                "diff-tree",
                "--root",
                "--no-commit-id",
                "--name-status",
                "-r",
                "-z",
                "--ignore-submodules=all",
                id);
        List<CommitFile> files = new ArrayList<>();
        if (!out.ok()) {
            return files;
        }
        String[] items = out.text().replace("\n", "").split("\0");
        for (int i = 0; i + 1 < items.length; i += 2) {
            String status = items[i];
            String path = items[i + 1];
            if (status.startsWith("R") || status.startsWith("C")) {
                path = i + 2 < items.length ? items[i + 2] : path;
                i++;
            }
            String full = path;
            if (!WorkspaceFiles.sensitive(full)) {
                files.add(new CommitFile(path, status.isEmpty() ? "M" : status.substring(0, 1)));
            }
        }
        return files;
    }

    /** A commit id (7 to 64 hex digits) or HEAD. */
    static boolean safeRef(String ref) {
        return ref != null && (ref.equals("HEAD") || ref.matches("[0-9a-fA-F]{7,64}"));
    }
}
