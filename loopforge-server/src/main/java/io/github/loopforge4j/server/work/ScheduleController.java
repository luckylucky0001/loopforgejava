package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.engine.RunNotFoundException;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Scheduled runs of a project; maintainers manage them. */
@Tag(name = "Schedules")
@RestController
@RequestMapping("/api/v1/projects/{projectId}/schedules")
class ScheduleController {

    private final ScheduleService schedules;
    private final ScheduleStore store;
    private final AccessPolicy access;

    ScheduleController(ScheduleService schedules, ScheduleStore store, AccessPolicy access) {
        this.schedules = schedules;
        this.store = store;
        this.access = access;
    }

    @GetMapping
    List<Schedule> list(@PathVariable String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return store.list(projectId);
    }

    @PostMapping
    ResponseEntity<Schedule> create(@PathVariable String projectId, @RequestBody Schedule body, Principal principal) {
        // a scheduled task keeps working with its saver's rights and credentials after the turn ends
        access.requireHuman(principal, "save scheduled tasks");
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        access.requireIsolatedSandbox(principal, body.workflowId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(schedules.save(
                        projectId, null, body, access.current(principal).username()));
    }

    @GetMapping("/{id}")
    Schedule get(@PathVariable String projectId, @PathVariable String id, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return find(projectId, id);
    }

    @PutMapping("/{id}")
    Schedule update(
            @PathVariable String projectId, @PathVariable String id, @RequestBody Schedule body, Principal principal) {
        access.requireHuman(principal, "save scheduled tasks");
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        find(projectId, id);
        access.requireIsolatedSandbox(principal, body.workflowId());
        return schedules.save(projectId, id, body, access.current(principal).username());
    }

    @Operation(summary = "Fire the schedule now, independent of its cron slot")
    @PostMapping("/{id}/run")
    Schedule runNow(@PathVariable String projectId, @PathVariable String id, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        Schedule schedule = find(projectId, id);
        access.requireIsolatedSandbox(principal, schedule.workflowId());
        schedules.runNow(schedule, access.current(principal).username());
        return find(projectId, id);
    }

    @Operation(summary = "Recent firings of the schedule (cron and manual), newest first")
    @GetMapping("/{id}/executions")
    List<ScheduleExecution> executions(
            @PathVariable String projectId,
            @PathVariable String id,
            @RequestParam(defaultValue = "50")
                    @jakarta.validation.constraints.Min(1)
                    @jakarta.validation.constraints.Max(ScheduleExecution.KEEP)
                    int limit,
            Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        find(projectId, id);
        return schedules.executions(id, limit);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String id, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        find(projectId, id);
        store.delete(id);
        return ResponseEntity.noContent().build();
    }

    private Schedule find(String projectId, String id) {
        return store.find(id)
                .filter(s -> s.projectId().equals(projectId))
                .orElseThrow(() -> new RunNotFoundException("schedule '" + id + "' not found"));
    }
}
