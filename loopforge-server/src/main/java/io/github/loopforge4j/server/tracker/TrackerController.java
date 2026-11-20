package io.github.loopforge4j.server.tracker;

import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.work.Requirement;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tracker settings, sync, TAPD import and push of one project. */
@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}")
public class TrackerController {

    private final TrackerService trackers;
    private final AccessPolicy access;

    public TrackerController(TrackerService trackers, AccessPolicy access) {
        this.trackers = trackers;
        this.access = access;
    }

    @GetMapping("/tracker")
    TrackerSettings settings(@PathVariable String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return trackers.settings(projectId);
    }

    @PutMapping("/tracker")
    TrackerSettings save(@PathVariable String projectId, @RequestBody TrackerSettings body, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.MAINTAINER);
        return trackers.saveSettings(projectId, body, access.current(principal).username());
    }

    @PostMapping("/tracker/sync")
    List<TrackerService.SyncLog> sync(@PathVariable String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        return trackers.sync(projectId, access.current(principal).username());
    }

    @GetMapping("/tracker/logs")
    List<TrackerService.SyncLog> logs(@PathVariable String projectId, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.VIEWER);
        return trackers.logs(projectId);
    }

    record ImportRequest(@NotBlank @Size(max = 4000) String url) {}

    @PostMapping("/tracker/import-tapd")
    Requirement importTapd(@PathVariable String projectId, @RequestBody ImportRequest body, Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        return trackers.importTapd(
                projectId, body.url(), access.current(principal).username());
    }

    record PushRequest(@NotBlank @Size(max = 200) String target) {}

    @PostMapping("/requirements/{number}/external")
    Requirement push(
            @PathVariable String projectId,
            @PathVariable int number,
            @RequestBody PushRequest body,
            Principal principal) {
        access.requireProject(principal, projectId, ProjectRole.DEVELOPER);
        return trackers.push(
                projectId, number, body.target(), access.current(principal).username());
    }
}
