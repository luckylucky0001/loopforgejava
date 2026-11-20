package io.github.loopforge4j.server.web;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunEngine;
import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.core.store.RunQuery;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Runs: start, inspect and steer SOP executions (upstream {@code runsHandlers}). */
// No class-level @Validated: Spring MVC's built-in method validation then turns @Min/@Max violations
// on parameters into 400 problems instead of unhandled ConstraintViolationExceptions (500).
@Tag(name = "Runs")
@RestController
@RequestMapping("/api/v1/runs")
class RunController {

    private final RunEngine engine;
    private final AccessPolicy access;

    RunController(RunEngine engine, AccessPolicy access) {
        this.engine = engine;
        this.access = access;
    }

    private Run readable(String runId, Principal principal) {
        Run run = engine.require(runId);
        access.requireReadRun(principal, run);
        return run;
    }

    private void operable(String runId, Principal principal) {
        access.requireOperateRun(principal, engine.require(runId));
    }

    @Operation(summary = "Start a run of a catalog SOP")
    @PostMapping
    ResponseEntity<Run> start(@Valid @RequestBody ApiRequests.StartRun request, Principal principal) {
        access.requireProject(principal, request.projectId(), ProjectRole.DEVELOPER);
        access.requireIsolatedSandbox(principal, request.workflowId());
        ExecutionContext context = new ExecutionContext(request.projectId(), actor(principal), null, null, null);
        Run run = engine.start(request.workflowId(), request.input(), context, request.selections());
        return ResponseEntity.created(URI.create("/api/v1/runs/" + run.id())).body(run);
    }

    @Operation(summary = "List runs, newest first")
    @GetMapping
    List<RunSummary> list(
            @RequestParam(required = false) Set<RunStatus> status,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String parentRunId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit,
            Principal principal) {
        RunQuery query = new RunQuery(status, workflowId, parentRunId, limit);
        java.util.Set<String> projects = access.visibleProjects(principal);
        if (projects != null) {
            query = query.visibleTo(projects, null);
        }
        return engine.list(query).stream().map(RunSummary::of).toList();
    }

    @GetMapping("/{runId}")
    Run get(@PathVariable String runId, Principal principal) {
        return readable(runId, principal);
    }

    @Operation(summary = "Events after a sequence number (audit log and executor telemetry)")
    @GetMapping("/{runId}/events")
    List<RunEvent> events(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int limit,
            Principal principal) {
        readable(runId, principal);
        return engine.events(runId, after, limit);
    }

    @Operation(summary = "Artifact metadata of every node attempt")
    @GetMapping("/{runId}/artifacts")
    List<Artifact> artifacts(@PathVariable String runId, Principal principal) {
        readable(runId, principal);
        return engine.artifacts(runId).stream().map(Artifact::withoutContent).toList();
    }

    @Operation(summary = "Raw artifact content")
    @GetMapping("/{runId}/artifacts/{artifactId}")
    ResponseEntity<byte[]> artifact(@PathVariable String runId, @PathVariable String artifactId, Principal principal) {
        readable(runId, principal);
        Artifact artifact = engine.artifact(runId, artifactId)
                .orElseThrow(() -> new RunNotFoundException("artifact '" + artifactId + "' not found"));
        MediaType type = safeMediaType(artifact.mediaType());
        return ResponseEntity.ok()
                .contentType(type)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("X-Artifact-Sha256", artifact.sha256())
                .body(
                        artifact.content() == null
                                ? new byte[0]
                                : artifact.content().getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Pull requests delivering the run's work branches")
    @GetMapping("/{runId}/deliveries")
    List<PullRequest> deliveries(@PathVariable String runId, Principal principal) {
        return readable(runId, principal).deliveries();
    }

    @Operation(summary = "Re-query the Git host for the run's pull requests (e.g. after a merge)")
    @PostMapping("/{runId}/deliveries/refresh")
    List<PullRequest> refreshDeliveries(@PathVariable String runId, Principal principal) {
        operable(runId, principal);
        return engine.refreshDeliveries(runId).deliveries();
    }

    @PostMapping("/{runId}/cancel")
    Run cancel(@PathVariable String runId, Principal principal) {
        operable(runId, principal);
        return engine.cancel(runId);
    }

    @PostMapping("/{runId}/pause")
    Run pause(@PathVariable String runId, Principal principal) {
        operable(runId, principal);
        return engine.pause(runId);
    }

    @PostMapping("/{runId}/resume")
    Run resume(
            @PathVariable String runId,
            @Valid @RequestBody(required = false) ApiRequests.HumanContext body,
            Principal principal) {
        operable(runId, principal);
        return engine.resume(runId, body == null ? null : body.humanContext());
    }

    @Operation(summary = "Retry from a node (default: where the run stopped)")
    @PostMapping("/{runId}/retry")
    Run retry(
            @PathVariable String runId,
            @Valid @RequestBody(required = false) ApiRequests.Retry body,
            Principal principal) {
        operable(runId, principal);
        ApiRequests.Retry request = body == null ? new ApiRequests.Retry(null, null, null) : body;
        return engine.retry(runId, request.nodeId(), request.reason(), request.humanContext());
    }

    @Operation(summary = "Approve a node waiting at a human gate")
    @PostMapping("/{runId}/nodes/{nodeId}/approve")
    Run approve(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @Valid @RequestBody(required = false) ApiRequests.HumanContext body,
            Principal principal) {
        operable(runId, principal);
        return engine.approve(runId, nodeId, body == null ? null : body.humanContext());
    }

    @Operation(summary = "Send work back to an upstream node")
    @PostMapping("/{runId}/nodes/{nodeId}/reject")
    Run reject(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @Valid @RequestBody ApiRequests.Reject body,
            Principal principal) {
        operable(runId, principal);
        return engine.reject(runId, nodeId, body.targetId(), body.reason(), body.humanContext());
    }

    @Operation(summary = "Mark a failed script node as passed without re-running it")
    @PostMapping("/{runId}/nodes/{nodeId}/pass")
    Run pass(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @Valid @RequestBody(required = false) ApiRequests.HumanContext body,
            Principal principal) {
        operable(runId, principal);
        return engine.pass(runId, nodeId, body == null ? null : body.humanContext());
    }

    @Operation(summary = "Decline retrying a node that ran out of turns")
    @PostMapping("/{runId}/nodes/{nodeId}/decline-retry")
    Run declineRetry(@PathVariable String runId, @PathVariable String nodeId, Principal principal) {
        operable(runId, principal);
        return engine.declineRetry(runId, nodeId);
    }

    /** Untrusted artifact bodies are only served as inert types. */
    private static MediaType safeMediaType(String declared) {
        try {
            MediaType type = MediaType.parseMediaType(declared);
            if (type.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                return MediaType.APPLICATION_JSON;
            }
        } catch (IllegalArgumentException e) {
            // fall through to plain text
        }
        return new MediaType("text", "plain", StandardCharsets.UTF_8);
    }

    private static String actor(Principal principal) {
        return principal == null ? "anonymous" : principal.getName();
    }
}
