package io.github.loopforge4j.server.business;

import io.github.loopforge4j.server.account.Project;
import io.github.loopforge4j.server.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.Principal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Business domains, project assignment and the business-domain report (upstream). */
@Tag(name = "business domains")
@RestController
@RequestMapping("/api/v1")
public class BusinessDomainController {

    private static final Set<String> GRAINS = Set.of("day", "week", "month");

    private final BusinessDomainService service;
    private final Clock clock;

    public BusinessDomainController(BusinessDomainService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Operation(summary = "Business domains with what you may do with each")
    @GetMapping("/business-domains")
    Map<String, Object> list(Principal principal) {
        return service.list(principal);
    }

    @Operation(summary = "Create or update a business domain (createOnly refuses an existing id)")
    @PutMapping("/business-domains")
    BusinessDomain save(
            @RequestBody BusinessDomain body,
            @RequestParam(defaultValue = "false") boolean createOnly,
            Principal principal) {
        return service.save(body, createOnly, principal);
    }

    /** A project's new domain; {@code expectedDomainId} guards against a concurrent change. */
    record Assignment(String businessDomainId, String expectedDomainId, String reason) {}

    @Operation(
            summary = "Assign a project to a business domain (owners set the first one; moves need an administrator)")
    @PutMapping("/projects/{projectId}/business-domain")
    Project assign(@PathVariable String projectId, @RequestBody Assignment body, Principal principal) {
        return service.assign(projectId, body.businessDomainId(), body.expectedDomainId(), body.reason(), principal);
    }

    @Operation(summary = "A project's business domain assignments, newest first")
    @GetMapping("/projects/{projectId}/business-domain/history")
    List<BusinessDomainStore.Assignment> history(@PathVariable String projectId, Principal principal) {
        return service.history(projectId, principal);
    }

    @Operation(summary = "Usage and delivery of the projects of a business domain (all, unclassified or an id)")
    @GetMapping("/business-domain-report")
    BusinessDomainService.Report report(
            @RequestParam(defaultValue = "all") String businessDomainId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "day") String grain,
            @RequestParam(defaultValue = "UTC") String timeZone,
            Principal principal) {
        if (!GRAINS.contains(grain)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "grain 必须为 day、week 或 month");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "timeZone 须为有效的 IANA 时区");
        }
        Instant end = parse(to, clock.instant());
        Instant start = parse(from, end.minus(Duration.ofDays(30)));
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "时间范围须为不超过 366 天的有效区间");
        }
        return service.report(businessDomainId, start, end, grain, zone, principal);
    }

    private static Instant parse(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "时间须使用 RFC3339 格式");
        }
    }
}
