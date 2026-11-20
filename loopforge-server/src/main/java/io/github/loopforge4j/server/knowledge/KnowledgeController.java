package io.github.loopforge4j.server.knowledge;

import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.store.RunStore;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.web.ApiException;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Team knowledge base (upstream 知识库 pages): connection settings for administrators, project-scoped
 * search and reading, and the review queue of distilled knowledge.
 */
@RestController
@Validated
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final KnowledgeStore store;
    private final AccessPolicy access;
    private final RunStore runs;

    public KnowledgeController(KnowledgeService knowledge, KnowledgeStore store, AccessPolicy access, RunStore runs) {
        this.knowledge = knowledge;
        this.store = store;
        this.access = access;
        this.runs = runs;
    }

    @GetMapping("/settings")
    Map<String, Object> settings(Principal principal) {
        access.requireAdmin(principal);
        return Map.of("settings", knowledge.publicSettings(), "ready", knowledge.ready());
    }

    @PutMapping("/settings")
    KnowledgeSettings updateSettings(@RequestBody KnowledgeService.SettingsInput body, Principal principal) {
        access.requireAdmin(principal);
        return knowledge.updateSettings(body, access.current(principal).username());
    }

    record CheckRequest(
            @Size(max = 2000) String url, @Size(max = 4096) String token) {}

    @PostMapping("/check")
    Map<String, Object> check(@RequestBody(required = false) CheckRequest body, Principal principal) {
        access.requireAdmin(principal);
        return knowledge.check(body == null ? null : body.url(), body == null ? null : body.token());
    }

    @GetMapping("/sources")
    JsonNode sources(@RequestParam(required = false) String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return knowledge.read(projectId, KnowledgeService.TOOL_LIST_SOURCES, Map.of(), caller(principal));
    }

    @GetMapping("/search")
    JsonNode search(
            @RequestParam(required = false) String projectId,
            @RequestParam @Size(min = 1, max = 500) String q,
            @RequestParam(required = false) @Size(max = 2000) String sources,
            Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", q);
        if (sources != null && !sources.isBlank()) {
            args.put("sources", List.of(sources.split(",")));
        }
        return knowledge.read(projectId, KnowledgeService.TOOL_SEARCH, args, caller(principal));
    }

    @GetMapping("/document")
    JsonNode document(
            @RequestParam(required = false) String projectId,
            @RequestParam @Size(min = 1, max = 200) String source,
            @RequestParam @Size(min = 1, max = 1000) String path,
            @RequestParam(defaultValue = "0") int start,
            @RequestParam(defaultValue = "0") int end,
            Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("source", source);
        args.put("path", path);
        if (start > 0) {
            args.put("start_line", start);
        }
        if (end > 0) {
            args.put("end_line", end);
        }
        return knowledge.read(projectId, KnowledgeService.TOOL_FETCH, args, caller(principal));
    }

    @GetMapping("/activity")
    List<KnowledgeService.Activity> activity(@RequestParam(required = false) String projectId, Principal principal) {
        Set<String> visible = access.visibleProjects(principal);
        if (projectId != null && !projectId.isBlank()) {
            access.requireProject(principal, projectId, ProjectRole.VIEWER);
            visible = Set.of(projectId);
        }
        return knowledge.activity(visible);
    }

    @GetMapping("/usage")
    List<Map<String, String>> usage(java.security.Principal principal) {
        // project roles only for the project's members
        return knowledge.usage(project -> access.canSee(principal, project));
    }

    @GetMapping("/candidates")
    List<KnowledgeStore.Candidate> candidates(@RequestParam(required = false) String projectId, Principal principal) {
        if (projectId != null && !projectId.isBlank()) {
            access.requireProject(principal, projectId, ProjectRole.VIEWER);
        }
        return store.candidates(projectId, access.visibleProjects(principal), 100);
    }

    @GetMapping("/submissions")
    List<KnowledgeSubmission> submissions(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String status,
            Principal principal) {
        if (projectId != null && !projectId.isBlank()) {
            access.requireProject(principal, projectId, ProjectRole.VIEWER);
        }
        if (status != null && !status.isBlank() && !KnowledgeSubmission.STATUSES.contains(status)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "status must be one of " + new java.util.TreeSet<>(KnowledgeSubmission.STATUSES));
        }
        return store.submissions(projectId, status, access.visibleProjects(principal), 200).stream()
                .map(KnowledgeController::summary)
                .toList();
    }

    @PostMapping("/submissions")
    ResponseEntity<KnowledgeSubmission> submit(
            @RequestBody KnowledgeService.SubmissionInput body, Principal principal) {
        Run run = null;
        if (body.artifactId() != null && !body.artifactId().isBlank()) {
            if (body.runId() == null || body.runId().isBlank()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "runId is required with artifactId");
            }
            run = runs.find(body.runId()).orElseThrow(() -> new RunNotFoundException("run not found"));
            access.requireReadRun(principal, run);
            access.requireProject(principal, run.context().projectId(), ProjectRole.DEVELOPER);
        } else {
            access.requireProject(principal, body.projectId(), ProjectRole.DEVELOPER);
        }
        KnowledgeSubmission created =
                knowledge.submit(body, run, access.current(principal).username());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/submissions/{id}")
    KnowledgeSubmission submission(@PathVariable String id, Principal principal) {
        return require(id, principal, ProjectRole.VIEWER);
    }

    @PutMapping("/submissions/{id}")
    KnowledgeSubmission edit(
            @PathVariable String id, @RequestBody KnowledgeService.SubmissionEdit body, Principal principal) {
        return knowledge.edit(require(id, principal, ProjectRole.MAINTAINER), body);
    }

    @DeleteMapping("/submissions/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, Principal principal) {
        knowledge.delete(require(id, principal, ProjectRole.MAINTAINER));
        return ResponseEntity.noContent().build();
    }

    record RejectRequest(@Size(max = 2000) String note) {}

    @PostMapping("/submissions/{id}/reject")
    KnowledgeSubmission reject(
            @PathVariable String id, @RequestBody(required = false) RejectRequest body, Principal principal) {
        return knowledge.reject(
                require(id, principal, ProjectRole.MAINTAINER),
                body == null ? null : body.note(),
                access.current(principal).username());
    }

    @PostMapping("/submissions/{id}/publish")
    KnowledgeSubmission publish(@PathVariable String id, Principal principal) {
        return knowledge.publish(
                require(id, principal, ProjectRole.MAINTAINER),
                access.current(principal).username());
    }

    private KnowledgeSubmission require(String id, Principal principal, ProjectRole role) {
        KnowledgeSubmission item =
                store.submission(id).orElseThrow(() -> new RunNotFoundException("submission " + id + " not found"));
        if (!access.canSee(principal, item.projectId())
                || item.projectId() == null && !access.current(principal).admin()) {
            throw new RunNotFoundException("submission " + id + " not found");
        }
        access.requireProject(principal, item.projectId(), role);
        return item;
    }

    /** Listings leave the body out. */
    private static KnowledgeSubmission summary(KnowledgeSubmission s) {
        return new KnowledgeSubmission(
                s.id(),
                s.projectId(),
                s.runId(),
                s.nodeId(),
                s.artifactId(),
                s.workflowId(),
                s.title(),
                s.source(),
                s.path(),
                null,
                s.status(),
                s.commit(),
                s.error(),
                s.reviewNote(),
                s.createdBy(),
                s.reviewedBy(),
                s.createdAt(),
                s.updatedAt(),
                s.publishedAt());
    }

    private String caller(Principal principal) {
        CurrentUser user = access.current(principal);
        return user.username();
    }
}
