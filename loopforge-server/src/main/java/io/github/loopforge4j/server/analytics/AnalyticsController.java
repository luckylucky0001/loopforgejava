package io.github.loopforge4j.server.analytics;

import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.web.ApiException;
import java.security.Principal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Analytics dashboard and SOP operating metrics, limited to the projects the caller can see. */
@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private static final Duration DEFAULT_RANGE = Duration.ofDays(30);
    private static final Duration MAX_RANGE = Duration.ofDays(366);
    private static final Set<String> GRAINS = Set.of("day", "week", "month");

    private final AnalyticsService analytics;
    private final AccessPolicy access;
    private final Clock clock;

    public AnalyticsController(AnalyticsService analytics, AccessPolicy access, Clock clock) {
        this.analytics = analytics;
        this.access = access;
        this.clock = clock;
    }

    @GetMapping("/analytics")
    AnalyticsService.Report analytics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "day") String grain,
            @RequestParam(defaultValue = "UTC") String zone,
            Principal principal) {
        return analytics.report(filter(projectId, workflowId, from, to, grain, zone, principal));
    }

    @GetMapping("/workflow-metrics")
    List<AnalyticsService.WorkflowMetric> workflowMetrics(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            Principal principal) {
        return analytics.workflowMetrics(filter(projectId, null, from, to, "day", "UTC", principal));
    }

    private AnalyticsService.Filter filter(
            String projectId,
            String workflowId,
            String from,
            String to,
            String grain,
            String zone,
            Principal principal) {
        String project = projectId == null || projectId.isBlank() ? null : projectId.strip();
        if (project != null) {
            access.requireProject(principal, project, ProjectRole.VIEWER);
        }
        if (!GRAINS.contains(grain)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "grain must be day, week or month");
        }
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "zone must be an IANA time zone");
        }
        Instant end = parse(to, "to", clock.instant());
        Instant start = parse(from, "from", end.minus(DEFAULT_RANGE));
        if (!start.isBefore(end)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "from must be before to");
        }
        if (Duration.between(start, end).compareTo(MAX_RANGE) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "the range may span at most 366 days");
        }
        return new AnalyticsService.Filter(
                project,
                workflowId == null || workflowId.isBlank() ? null : workflowId.strip(),
                start,
                end,
                grain,
                zoneId,
                access.visibleProjects(principal));
    }

    private static Instant parse(String value, String name, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            try {
                return java.time.LocalDate.parse(value.strip())
                        .atStartOfDay(java.time.ZoneOffset.UTC)
                        .toInstant();
            } catch (DateTimeParseException e2) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, name + " must be an ISO-8601 instant or date");
            }
        }
    }
}
