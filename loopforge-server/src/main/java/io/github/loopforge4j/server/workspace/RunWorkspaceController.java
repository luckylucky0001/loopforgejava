package io.github.loopforge4j.server.workspace;

import io.github.loopforge4j.core.domain.RepositoryContext;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A run's workspace for people (upstream run workspace): files, Git changes, diffs, history,
 * search, and text edits that never overwrite what changed meanwhile.
 */
@Tag(name = "runs")
@RestController
@RequestMapping("/api/v1/runs/{runId}/workspace")
public class RunWorkspaceController {

    private final RunEngine engine;
    private final AccessPolicy access;

    public RunWorkspaceController(RunEngine engine, AccessPolicy access) {
        this.engine = engine;
        this.access = access;
    }

    @ExceptionHandler(WorkspaceFiles.WorkspaceException.class)
    ResponseEntity<Map<String, Object>> rejected(WorkspaceFiles.WorkspaceException e) {
        HttpStatus status = e.missing ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of(
                        "type",
                        "about:blank",
                        "title",
                        status.getReasonPhrase(),
                        "status",
                        status.value(),
                        "detail",
                        e.getMessage()));
    }

    private Run readable(String runId, Principal principal) {
        Run run = engine.require(runId);
        access.requireReadRun(principal, run);
        return run;
    }

    /** The workspace root, or a 409 explaining why it cannot be opened from here. */
    private Path root(String runId) {
        RunEngine.WorkspaceLocation location = engine.workspace(runId);
        if (!location.available()) {
            throw new UnavailableException(location.unavailable());
        }
        return location.root();
    }

    static final class UnavailableException extends RuntimeException {
        UnavailableException(String message) {
            super(message);
        }
    }

    @ExceptionHandler(UnavailableException.class)
    ResponseEntity<Map<String, Object>> unavailable(UnavailableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(Map.of("type", "about:blank", "title", "Conflict", "status", 409, "detail", e.getMessage()));
    }

    /** A repository of the workspace: its directory and the label it is shown with. */
    record Repo(Path path, String label) {}

    static List<Repo> repositories(Path root, Run run) {
        List<Repo> out = new ArrayList<>();
        if (run.repositories().isEmpty()) {
            out.add(new Repo(root, ""));
            return out;
        }
        for (RepositoryContext rc : run.repositories()) {
            String dir = rc.workspaceDir() == null ? "" : WorkspaceFiles.relative(rc.workspaceDir());
            out.add(new Repo(dir.isEmpty() ? root : root.resolve(dir), dir));
        }
        return out;
    }

    /** People edit a settled run's workspace, or a live one (writes check what they read). */
    static boolean humanEditable(Run run) {
        return run.status().terminal()
                || (run.status() == RunStatus.RUNNING || run.status() == RunStatus.WAITING) && !run.cancelRequested();
    }

    @Operation(summary = "The run's workspace: availability, Git changes, repositories and whether you may edit it")
    @GetMapping
    Map<String, Object> summary(@PathVariable String runId, Principal principal) {
        Run run = readable(runId, principal);
        Map<String, Object> body = new LinkedHashMap<>();
        RunEngine.WorkspaceLocation location = engine.workspace(runId);
        body.put("available", location.available());
        body.put("runStatus", run.status().wire());
        body.put("shared", location.shared());
        if (!location.available()) {
            body.put("reason", location.unavailable());
            // a released workspace may have left a snapshot: replaying a node restores it
            engine.snapshotInfo(run.workspaceOwnerId()).ifPresent(info -> {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("savedAt", info.savedAt());
                snapshot.put("files", info.files());
                snapshot.put("bytes", info.bytes());
                snapshot.put("archiveBytes", info.size());
                body.put("snapshot", snapshot);
            });
            return body;
        }
        boolean writable = !location.shared() && humanEditable(run) && access.canOperateRun(principal, run);
        body.put("readOnly", !writable);
        List<WorkspaceGit.Change> changes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        WorkspaceGit git = new WorkspaceGit(engine, runId, location.root());
        for (Repo repo : repositories(location.root(), run)) {
            Optional<String> unsafe = git.unsafe(repo.label());
            String name = repo.label().isEmpty() ? "workspace" : repo.label();
            if (unsafe.isPresent()) {
                if (!unsafe.get().equals("不是 Git 仓库")) {
                    warnings.add(name + "：" + unsafe.get());
                }
                continue;
            }
            names.add(name);
            changes.addAll(git.changes(repo.label()));
        }
        changes.sort(java.util.Comparator.comparing(WorkspaceGit.Change::path));
        body.put("changes", changes);
        body.put("repositories", names);
        body.put(
                "repositoryDetails",
                run.repositories().stream()
                        .map(rc -> Map.of(
                                "name", rc.name(),
                                "workspaceDir", rc.workspaceDir() == null ? "" : rc.workspaceDir(),
                                "baseBranch", rc.baseBranch() == null ? "" : rc.baseBranch(),
                                "workBranch", rc.workBranch() == null ? "" : rc.workBranch()))
                        .toList());
        if (!warnings.isEmpty()) {
            body.put("warning", String.join("；", warnings));
        }
        return body;
    }

    @Operation(summary = "Download the snapshot of a released workspace (zip, sensitive files left out)")
    @GetMapping("/snapshot")
    void snapshot(@PathVariable String runId, Principal principal, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        Run run = readable(runId, principal);
        if (engine.snapshotInfo(run.workspaceOwnerId()).isEmpty()) {
            throw new io.github.loopforge4j.server.web.ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "该运行没有工作区快照");
        }
        response.setContentType("application/zip");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(
                "Content-Disposition",
                org.springframework.http.ContentDisposition.attachment()
                        .filename("workspace-" + runId.replaceAll("[^A-Za-z0-9_-]", "_") + ".zip")
                        .build()
                        .toString());
        engine.readSnapshot(runId, in -> {
            // the same paths the workspace page never shows (keys, .env, .npmrc, ...)
            io.github.loopforge4j.core.runtime.WorkspaceSnapshot.filter(
                    in, response.getOutputStream(), WorkspaceFiles::sensitive);
            return Boolean.TRUE;
        });
    }

    @Operation(summary = "Files and directories under a path (sensitive paths and dependency folders are hidden)")
    @GetMapping("/tree")
    WorkspaceFiles.Tree tree(
            @PathVariable String runId, @RequestParam(defaultValue = "") String path, Principal principal) {
        readable(runId, principal);
        return WorkspaceFiles.tree(root(runId), path);
    }

    @Operation(summary = "A text file (up to 1 MiB), or its content at a commit with ref")
    @GetMapping("/file")
    Map<String, Object> file(
            @PathVariable String runId,
            @RequestParam String path,
            @RequestParam(required = false) String ref,
            Principal principal) {
        Run run = readable(runId, principal);
        Path root = root(runId);
        if (ref == null || ref.isBlank()) {
            WorkspaceFiles.Content content = WorkspaceFiles.read(root, path);
            return Map.of(
                    "path",
                    content.path(),
                    "content",
                    content.content(),
                    "size",
                    content.size(),
                    "sha256",
                    content.sha256());
        }
        if (!WorkspaceGit.safeRef(ref.strip())) {
            throw new WorkspaceFiles.WorkspaceException("ref must be HEAD or a commit id", false);
        }
        String rel = WorkspaceFiles.relative(path);
        WorkspaceGit git = new WorkspaceGit(engine, runId, root);
        Repo repo = repoOf(git, root, run, rel);
        String text = git.blob(repo.label(), ref.strip(), inRepo(repo, rel))
                .orElseThrow(() -> new WorkspaceFiles.WorkspaceException("no such file at " + ref, true));
        return Map.of(
                "path",
                rel,
                "content",
                text,
                "size",
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                "ref",
                ref.strip());
    }

    @Operation(summary = "Changes against HEAD: the whole diff, or one file's diff with its original and current text")
    @GetMapping("/diff")
    Map<String, Object> diff(
            @PathVariable String runId, @RequestParam(defaultValue = "") String path, Principal principal) {
        Run run = readable(runId, principal);
        Path root = root(runId);
        String rel = WorkspaceFiles.relative(path);
        StringBuilder combined = new StringBuilder();
        boolean truncated = false;
        WorkspaceGit git = new WorkspaceGit(engine, runId, root);
        if (rel.isEmpty()) {
            for (Repo repo : repositories(root, run)) {
                if (git.unsafe(repo.label()).isEmpty()) {
                    combined.append(git.diff(repo.label(), ""));
                }
                if (combined.length() > WorkspaceFiles.FILE_LIMIT) {
                    combined.setLength(WorkspaceFiles.FILE_LIMIT);
                    truncated = true;
                    break;
                }
            }
            return Map.of("diff", combined.toString(), "truncated", truncated);
        }
        Repo repo = repoOf(git, root, run, rel);
        String inRepo = inRepo(repo, rel);
        String diff = git.diff(repo.label(), inRepo);
        if (diff.length() > WorkspaceFiles.FILE_LIMIT) {
            diff = diff.substring(0, WorkspaceFiles.FILE_LIMIT);
            truncated = true;
        }
        String original = git.blob(repo.label(), "HEAD", inRepo).orElse("");
        String modified;
        try {
            modified = WorkspaceFiles.read(root, rel).content();
        } catch (WorkspaceFiles.WorkspaceException e) {
            if (!e.missing) {
                throw e;
            }
            modified = ""; // deleted in the worktree
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diff", diff);
        out.put("original", original);
        out.put("modified", modified);
        out.put("truncated", truncated);
        return out;
    }

    @Operation(summary = "Recent commits of a workspace repository")
    @GetMapping("/log")
    Map<String, Object> log(
            @PathVariable String runId, @RequestParam(defaultValue = "") String repository, Principal principal) {
        Run run = readable(runId, principal);
        Path root = root(runId);
        WorkspaceGit git = new WorkspaceGit(engine, runId, root);
        Repo repo = named(git, root, run, repository);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repository", repo.label());
        out.put("branch", git.branch(repo.label()));
        out.put("head", git.head(repo.label()));
        out.put("commits", git.log(repo.label()));
        return out;
    }

    @Operation(summary = "Files a commit changed")
    @GetMapping("/commit")
    Map<String, Object> commit(
            @PathVariable String runId,
            @RequestParam(defaultValue = "") String repository,
            @RequestParam String id,
            Principal principal) {
        Run run = readable(runId, principal);
        if (!WorkspaceGit.safeRef(id.strip()) || id.strip().equals("HEAD")) {
            throw new WorkspaceFiles.WorkspaceException("id must be a commit id", false);
        }
        Path root = root(runId);
        WorkspaceGit git = new WorkspaceGit(engine, runId, root);
        Repo repo = named(git, root, run, repository);
        return Map.of("id", id.strip(), "files", git.commitFiles(repo.label(), id.strip()));
    }

    @Operation(summary = "Search text files of the workspace (case-insensitive, at least 2 characters)")
    @GetMapping("/search")
    Map<String, Object> search(@PathVariable String runId, @RequestParam String q, Principal principal) {
        readable(runId, principal);
        if (q.strip().codePointCount(0, q.strip().length()) < 2) {
            throw new WorkspaceFiles.WorkspaceException("q must contain at least 2 characters", false);
        }
        boolean[] truncated = {false};
        List<WorkspaceFiles.Hit> hits = WorkspaceFiles.search(root(runId), q.strip(), truncated);
        return Map.of("results", hits, "truncated", truncated[0]);
    }

    /** An edit: the new text and the sha256 of the text it replaces ({@code missing} for a new file). */
    record WriteRequest(String path, String content, String expectedSha256) {}

    @Operation(summary = "Write a text file; refused when it changed since you read it (expectedSha256)")
    @PutMapping("/file")
    Map<String, Object> write(@PathVariable String runId, @RequestBody WriteRequest body, Principal principal) {
        Run run = readable(runId, principal);
        access.requireOperateRun(principal, run);
        RunEngine.WorkspaceLocation location = engine.workspace(runId);
        if (location.shared()) {
            throw new UnavailableException("子 SOP 共享父任务工作区，请从父任务操作");
        }
        if (!humanEditable(run)) {
            throw new UnavailableException("运行正在取消或排队，暂不能编辑工作区");
        }
        String sha = WorkspaceFiles.write(root(runId), body.path(), body.content(), body.expectedSha256());
        engine.note(
                runId,
                "workspace.changed",
                "工作区文件 " + WorkspaceFiles.relative(body.path()) + " 已被 "
                        + access.current(principal).username() + " 修改，之前的检查仅供历史参考");
        return Map.of("sha256", sha);
    }

    private static Repo named(WorkspaceGit git, Path root, Run run, String name) {
        String wanted = name == null || name.isBlank() || name.equals("workspace") ? "" : name.strip();
        List<Repo> repos = repositories(root, run);
        Repo repo = repos.stream()
                .filter(r -> r.label().equals(wanted))
                .findFirst()
                .orElseGet(() -> wanted.isEmpty() && repos.size() == 1 ? repos.getFirst() : null);
        if (repo == null) {
            throw new WorkspaceFiles.WorkspaceException("repository not found", true);
        }
        guard(git, repo);
        return repo;
    }

    /** The repository holding a workspace path. */
    private static Repo repoOf(WorkspaceGit git, Path root, Run run, String rel) {
        Repo best = null;
        for (Repo repo : repositories(root, run)) {
            if (repo.label().isEmpty() || rel.equals(repo.label()) || rel.startsWith(repo.label() + "/")) {
                if (best == null || repo.label().length() > best.label().length()) {
                    best = repo;
                }
            }
        }
        if (best == null) {
            throw new WorkspaceFiles.WorkspaceException("the path is in no repository of the workspace", true);
        }
        guard(git, best);
        return best;
    }

    private static void guard(WorkspaceGit git, Repo repo) {
        git.unsafe(repo.label()).ifPresent(reason -> {
            throw new WorkspaceFiles.WorkspaceException(reason, false);
        });
    }

    private static String inRepo(Repo repo, String rel) {
        return repo.label().isEmpty()
                ? rel
                : rel.substring(Math.min(rel.length(), repo.label().length() + 1));
    }
}
