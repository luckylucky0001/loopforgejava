package io.github.loopforge4j.server.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Read-only Git views of a workspace repository (upstream status, diff, log and commit views).
 *
 * <p>The workspace belongs to agents that may run in an isolated sandbox, so the server never runs
 * Git in a repository whose configuration can start a program (fsmonitor, hooks, filters, textconv
 * and diff drivers, pagers, editors, credential helpers, includes) or point outside it (worktree,
 * common dir, alternates).
 */
final class WorkspaceGit {

    static final int LOG_LIMIT = 50;
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final List<String> RISKY = List.of(
            "fsmonitor",
            "hookspath",
            "[filter",
            "textconv",
            "external",
            "command",
            "askpass",
            "helper",
            "editor",
            "pager",
            "[include",
            "sshcommand",
            "gitproxy",
            "[diff ",
            "[merge ",
            "worktree",
            "attributesfile",
            "excludesfile",
            "[url ",
            "insteadof");

    /** A Git result; {@code ok} is false when Git failed. */
    record Output(boolean ok, byte[] stdout) {
        String text() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }

    record Change(String path, String repository, String index, String worktree) {}

    record Commit(String id, List<String> parentIds, String message, String author, long timestamp) {}

    record CommitFile(String path, String status) {}

    private WorkspaceGit() {}

    /** Why Git is not run in this repository, or empty when it is safe to. */
    static Optional<String> unsafe(Path repo) {
        Path git = repo.resolve(".git");
        if (!Files.isDirectory(git, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("不是 Git 仓库");
        }
        if (Files.exists(git.resolve("commondir"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(git.resolve("objects/info/alternates"), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.of("Git 仓库引用了工作区外的数据，已跳过");
        }
        Path config = git.resolve("config");
        if (Files.isSymbolicLink(config)) {
            return Optional.of("Git 配置是链接，已跳过");
        }
        try {
            if (Files.exists(config, LinkOption.NOFOLLOW_LINKS)) {
                String text = Files.readString(config, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                for (String key : RISKY) {
                    if (text.contains(key)) {
                        return Optional.of(
                                "Git 配置包含会执行程序或指向工作区外的项（" + key.replace("[", "").strip() + "），已跳过");
                    }
                }
            }
        } catch (IOException e) {
            return Optional.of("Git 配置无法读取");
        }
        return Optional.empty();
    }

    static Output run(Path repo, String... args) {
        List<String> command = new ArrayList<>(List.of(
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
                "safe.directory=" + repo,
                "-C",
                repo.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("GIT_"));
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_OPTIONAL_LOCKS", "0");
        env.put("HOME", repo.toString());
        Process process;
        try {
            process = pb.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            return new Output(false, new byte[0]);
        }
        try (InputStream out = process.getInputStream()) {
            process.getOutputStream().close();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            for (int n; (n = out.read(chunk)) > 0; ) {
                if (buffer.size() < WorkspaceFiles.FILE_LIMIT + 1) {
                    buffer.write(chunk, 0, Math.min(n, WorkspaceFiles.FILE_LIMIT + 1 - buffer.size()));
                }
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    return new Output(false, new byte[0]);
                }
            }
            boolean done =
                    process.waitFor(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                return new Output(false, new byte[0]);
            }
            return new Output(process.exitValue() == 0, buffer.toByteArray());
        } catch (IOException e) {
            process.destroyForcibly();
            return new Output(false, new byte[0]);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Output(false, new byte[0]);
        }
    }

    static List<Change> changes(Path repo, String label) {
        Output out = run(repo, "status", "--porcelain=v1", "-z", "--untracked-files=all");
        List<Change> changes = new ArrayList<>();
        if (!out.ok()) {
            return changes;
        }
        String[] items = out.text().split("\0");
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

    static Output diff(Path repo, String relative) {
        return relative.isEmpty()
                ? run(repo, "diff", "--no-ext-diff", "--no-textconv", "--no-color", "HEAD", "--")
                : run(repo, "diff", "--no-ext-diff", "--no-textconv", "--no-color", "HEAD", "--", relative);
    }

    /** The file at {@code ref} (HEAD or a commit id), or empty when it does not exist there. */
    static Optional<byte[]> blob(Path repo, String ref, String relative) {
        if (!run(repo, "cat-file", "-e", ref + ":" + relative).ok()) {
            return Optional.empty();
        }
        Output out = run(repo, "cat-file", "blob", ref + ":" + relative);
        return out.ok() ? Optional.of(out.stdout()) : Optional.empty();
    }

    static String branch(Path repo) {
        return run(repo, "rev-parse", "--abbrev-ref", "HEAD").text().strip();
    }

    static String head(Path repo) {
        return run(repo, "rev-parse", "HEAD").text().strip();
    }

    static List<Commit> log(Path repo) {
        Output out = run(repo, "log", "-n", Integer.toString(LOG_LIMIT), "--format=%H%x1f%P%x1f%s%x1f%an%x1f%at");
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

    static List<CommitFile> commitFiles(Path repo, String id) {
        Output out = run(repo, "diff-tree", "--root", "--no-commit-id", "--name-status", "-r", "-z", id);
        List<CommitFile> files = new ArrayList<>();
        if (!out.ok()) {
            return files;
        }
        String[] items = out.text().split("\0");
        for (int i = 0; i + 1 < items.length; i += 2) {
            String status = items[i];
            String path = items[i + 1];
            if (status.startsWith("R") || status.startsWith("C")) {
                path = i + 2 < items.length ? items[i + 2] : path;
                i++;
            }
            files.add(new CommitFile(path, status.isEmpty() ? "M" : status.substring(0, 1)));
        }
        return files;
    }

    /** A commit id (7 to 64 hex digits) or HEAD. */
    static boolean safeRef(String ref) {
        return ref != null && (ref.equals("HEAD") || ref.matches("[0-9a-fA-F]{7,64}"));
    }
}
