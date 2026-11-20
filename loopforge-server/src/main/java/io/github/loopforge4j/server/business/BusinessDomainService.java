package io.github.loopforge4j.server.business;

import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.server.account.AccessPolicy;
import io.github.loopforge4j.server.account.CurrentUser;
import io.github.loopforge4j.server.account.Project;
import io.github.loopforge4j.server.account.ProjectRole;
import io.github.loopforge4j.server.account.ProjectStore;
import io.github.loopforge4j.server.account.UserStore;
import io.github.loopforge4j.server.analytics.AnalyticsService;
import io.github.loopforge4j.server.web.ApiException;
import io.github.loopforge4j.server.work.Requirement;
import io.github.loopforge4j.server.work.RequirementService;
import io.github.loopforge4j.server.work.RequirementSnapshot;
import io.github.loopforge4j.server.work.RequirementStore;
import java.security.Principal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Business domains, project assignment and the business-domain delivery report (upstream). */
@Service
public class BusinessDomainService {

    static final Pattern ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

    /** Requirements read per project and report. */
    static final int MAX_REQUIREMENTS = 500;

    private final BusinessDomainStore store;
    private final ProjectStore projects;
    private final UserStore users;
    private final AccessPolicy access;
    private final AnalyticsService analytics;
    private final RequirementStore requirements;
    private final RequirementService requirementService;
    private final Clock clock;

    public BusinessDomainService(
            BusinessDomainStore store,
            ProjectStore projects,
            UserStore users,
            AccessPolicy access,
            AnalyticsService analytics,
            RequirementStore requirements,
            RequirementService requirementService,
            Clock clock) {
        this.store = store;
        this.projects = projects;
        this.users = users;
        this.access = access;
        this.analytics = analytics;
        this.requirements = requirements;
        this.requirementService = requirementService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ domains

    public Map<String, Object> list(Principal principal) {
        CurrentUser user = access.current(principal);
        List<Map<String, Object>> items = new ArrayList<>();
        for (BusinessDomain d : store.list()) {
            items.add(Map.of(
                    "domain", d,
                    "canManage", user.admin() || d.manages(user.id()),
                    "canView", user.admin() || d.views(user.id())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("canAdmin", user.admin());
        out.put("userId", user.id() == null ? "" : user.id());
        return out;
    }

    /**
     * Creates or updates a domain. Administrators manage everything; a domain's managers may
     * change its name, description and viewers, but not its state or managers.
     */
    public BusinessDomain save(BusinessDomain body, boolean createOnly, Principal principal) {
        CurrentUser user = access.requireHuman(principal, "maintain business domains");
        String id = body.id() == null ? "" : body.id().strip();
        String name = body.name() == null ? "" : body.name().strip();
        String description =
                body.description() == null ? "" : body.description().strip();
        if (!ID.matcher(id).matches()
                || id.equals(BusinessDomain.ALL)
                || id.equals(BusinessDomain.UNCLASSIFIED)
                || name.isEmpty()
                || name.codePointCount(0, name.length()) > 100
                || description.codePointCount(0, description.length()) > 2000) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "标识须为 1–64 位字母、数字、下划线或短横线；名称须为 1–100 字，说明不超过 2000 字");
        }
        var existing = store.find(id);
        if (createOnly && existing.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "业务域标识已存在");
        }
        if (!user.admin()) {
            if (existing.isEmpty() || !existing.get().manages(user.id())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "只有平台管理员或本业务域管理员可以维护业务域");
            }
            if (body.active() != existing.get().active()
                    || !new HashSet<>(body.managers())
                            .equals(new HashSet<>(existing.get().managers()))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "只有平台管理员可以调整业务域状态和管理员");
            }
        }
        List<String> managers = resolve(body.managers());
        List<String> viewers = resolve(body.viewers());
        Set<String> seen = new HashSet<>();
        for (String uid : concat(managers, viewers)) {
            if (!seen.add(uid)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "管理员和查看者必须为不重复的有效用户");
            }
        }
        if (store.nameTaken(name, id)) {
            throw new ApiException(HttpStatus.CONFLICT, "业务域名称已存在");
        }
        BusinessDomain clean = new BusinessDomain(id, name, description, body.active(), managers, viewers, null, null);
        if (existing.isEmpty()) {
            return store.insert(clean);
        }
        Instant expected =
                body.updatedAt() != null ? body.updatedAt() : existing.get().updatedAt();
        if (!store.update(clean, expected)) {
            throw new ApiException(HttpStatus.CONFLICT, "业务域已被他人修改，请刷新后再保存");
        }
        return store.find(id).orElseThrow();
    }

    /** User ids, or usernames resolved to ids; unknown users are refused. */
    private List<String> resolve(List<String> refs) {
        List<String> out = new ArrayList<>();
        for (String ref : refs) {
            if (ref == null || ref.isBlank()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "管理员和查看者必须为不重复的有效用户");
            }
            String id = users.byId(ref.strip())
                    .or(() -> users.byUsername(ref.strip()))
                    .map(io.github.loopforge4j.server.account.User::id)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "授权用户不存在：" + ref.strip()));
            out.add(id);
        }
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * A project owner sets the first domain; moving or clearing it is for administrators (upstream).
     */
    public Project assign(
            String projectId, String domainId, String expectedDomainId, String reason, Principal principal) {
        CurrentUser user = access.requireHuman(principal, "assign business domains");
        Project project = projects.find(projectId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "项目不存在"));
        String to = domainId == null || domainId.isBlank() ? null : domainId.strip();
        String expected = expectedDomainId == null || expectedDomainId.isBlank() ? null : expectedDomainId.strip();
        String why = reason == null ? "" : reason.strip();
        if (why.codePointCount(0, why.length()) > 500) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "归属调整原因不能超过 500 字");
        }
        if (!user.admin() && (!owner(user, projectId) || project.businessDomainId() != null)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "项目 Owner 只能设置首次归属；迁移或清除归属需要平台管理员");
        }
        if (to != null) {
            BusinessDomain target = store.find(to).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "业务域不存在"));
            if (!target.active()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "业务域已停用");
            }
        } else if (!user.admin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "清除归属需要平台管理员");
        }
        if (!store.assign(projectId, expected, to, user.username(), why)) {
            throw new ApiException(HttpStatus.CONFLICT, "项目的业务归属已变化，请刷新后再试");
        }
        return projects.find(projectId).orElseThrow();
    }

    public List<BusinessDomainStore.Assignment> history(String projectId, Principal principal) {
        CurrentUser user = access.current(principal);
        projects.find(projectId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "项目不存在"));
        if (!user.admin() && !owner(user, projectId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "只有平台管理员和项目 Owner 可以查看归属历史");
        }
        return store.history(projectId);
    }

    private boolean owner(CurrentUser user, String projectId) {
        return user.id() != null && projects.rolesOf(user.id()).get(projectId) == ProjectRole.OWNER;
    }

    // ------------------------------------------------------------------ report

    /** Delivery facts of completed work, independent of when runs were created (upstream). */
    public record DeliveryMetrics(
            int completedIssues,
            int estimatedCompletionIssues,
            int mergedMrs,
            int mergedMrsMissingTime,
            int completionToMergeSamples,
            long averageCompletionToMergeMs,
            int completionToMergeMissingIssues,
            int mergedBeforeCompletionIssues,
            int mergedBeforeCompletionMrs,
            int cycleSamples,
            long averageCycleDurationMs,
            long p50CycleDurationMs,
            long p95CycleDurationMs,
            int executionSamples,
            long averageExecutionDurationMs,
            long completedTokens,
            long averageTokensPerCompletedIssue,
            Instant latestCompletedAt) {}

    public record DeliveryTrendPoint(
            String date,
            int completedIssues,
            int mergedMrs,
            int cycleSamples,
            long averageCycleDurationMs,
            int executionSamples,
            long averageExecutionDurationMs) {}

    public record ProjectUsage(
            String projectId,
            String name,
            String businessDomainId,
            int runs,
            int succeeded,
            int failed,
            long tokens,
            double costUsd,
            DeliveryMetrics deliveryMetrics) {}

    public record DomainUsage(
            String id,
            String name,
            boolean active,
            int projectCount,
            int runs,
            int success,
            int failed,
            long tokens,
            double costUsd,
            int uniqueIssues,
            int delivered,
            DeliveryMetrics deliveryMetrics) {}

    public record Report(
            String attribution,
            Instant generatedAt,
            Instant from,
            Instant to,
            String timeZone,
            String grain,
            String businessDomainId,
            AnalyticsService.Summary summary,
            List<AnalyticsService.TrendPoint> trend,
            List<ProjectUsage> projects,
            List<DomainUsage> businessDomains,
            DeliveryMetrics deliveryMetrics,
            List<DeliveryTrendPoint> deliveryTrend) {}

    public Report report(String requested, Instant from, Instant to, String grain, ZoneId zone, Principal principal) {
        CurrentUser user = access.current(principal);
        String wanted = requested == null || requested.isBlank() ? BusinessDomain.ALL : requested.strip();
        List<BusinessDomain> domains = store.list();
        Map<String, BusinessDomain> byId = new LinkedHashMap<>();
        domains.forEach(d -> byId.put(d.id(), d));
        Set<String> visible = new HashSet<>();
        for (BusinessDomain d : domains) {
            if (user.admin() || d.views(user.id())) {
                visible.add(d.id());
            }
        }
        if (!wanted.equals(BusinessDomain.ALL) && !wanted.equals(BusinessDomain.UNCLASSIFIED)) {
            if (!byId.containsKey(wanted)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "业务域不存在");
            }
            if (!visible.contains(wanted)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "未授权查看该业务域汇总");
            }
        }
        Map<String, ProjectRole> roles = user.id() == null ? Map.of() : projects.rolesOf(user.id());
        List<Project> selected = new ArrayList<>();
        for (Project p : projects.list()) {
            String domain = p.businessDomainId();
            boolean inScope = wanted.equals(BusinessDomain.ALL)
                    || wanted.equals(domain)
                    || wanted.equals(BusinessDomain.UNCLASSIFIED) && domain == null;
            boolean allowed = user.admin()
                    || domain != null && visible.contains(domain)
                    || domain == null && roles.get(p.id()) == ProjectRole.OWNER;
            if (inScope && allowed) {
                selected.add(p);
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        selected.forEach(p -> ids.add(p.id()));
        AnalyticsService.Report usage =
                analytics.report(new AnalyticsService.Filter(null, null, from, to, grain, zone, ids));
        Map<String, AnalyticsService.Breakdown> byProject = new LinkedHashMap<>();
        usage.byProject().forEach(b -> byProject.put(b.key(), b));

        Map<String, List<Work>> work = new LinkedHashMap<>();
        for (Project p : selected) {
            work.put(p.id(), work(p.id(), from, to));
        }
        List<ProjectUsage> projectRows = new ArrayList<>();
        for (Project p : selected) {
            AnalyticsService.Breakdown b = byProject.get(p.id());
            projectRows.add(new ProjectUsage(
                    p.id(),
                    p.name(),
                    p.businessDomainId(),
                    b == null ? 0 : b.runs(),
                    b == null ? 0 : b.succeeded(),
                    b == null ? 0 : b.failed(),
                    b == null ? 0 : b.tokens(),
                    b == null ? 0 : b.costUsd(),
                    metrics(work.get(p.id()), from, to)));
        }
        Map<String, List<Project>> grouped = new LinkedHashMap<>();
        for (Project p : selected) {
            grouped.computeIfAbsent(
                            p.businessDomainId() == null ? BusinessDomain.UNCLASSIFIED : p.businessDomainId(),
                            k -> new ArrayList<>())
                    .add(p);
        }
        List<DomainUsage> domainRows = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            BusinessDomain d = byId.get(entry.getKey());
            int runs = 0;
            int success = 0;
            int failed = 0;
            long tokens = 0;
            double cost = 0;
            List<Work> items = new ArrayList<>();
            for (Project p : entry.getValue()) {
                AnalyticsService.Breakdown b = byProject.get(p.id());
                if (b != null) {
                    runs += b.runs();
                    success += b.succeeded();
                    failed += b.failed();
                    tokens += b.tokens();
                    cost += b.costUsd();
                }
                items.addAll(work.get(p.id()));
            }
            DeliveryMetrics m = metrics(items, from, to);
            domainRows.add(new DomainUsage(
                    entry.getKey(),
                    d == null ? "未归属" : d.name(),
                    d == null || d.active(),
                    entry.getValue().size(),
                    runs,
                    success,
                    failed,
                    tokens,
                    cost,
                    (int) items.stream().filter(w -> w.ranInWindow).count(),
                    m.completedIssues(),
                    m));
        }
        List<Work> all = work.values().stream().flatMap(List::stream).toList();
        return new Report(
                "按项目当前的业务域归属统计",
                clock.instant(),
                from,
                to,
                zone.getId(),
                grain,
                wanted,
                usage.summary(),
                usage.trend(),
                projectRows,
                domainRows,
                metrics(all, from, to),
                deliveryTrend(all, from, to, grain, zone));
    }

    /** A requirement's delivery facts. */
    record Work(
            Instant completedAt,
            boolean estimated,
            Long cycleMs,
            long executionMs,
            long tokens,
            List<PullRequest> merged,
            boolean ranInWindow) {}

    private List<Work> work(String projectId, Instant from, Instant to) {
        List<Work> out = new ArrayList<>();
        Instant now = clock.instant();
        int loaded = 0;
        for (Requirement r : requirements.list(projectId, null)) {
            // nothing completes, merges or runs in the window without touching the requirement
            if (r.updatedAt() != null && r.updatedAt().isBefore(from)) {
                continue;
            }
            if (++loaded > MAX_REQUIREMENTS) {
                break;
            }
            List<Run> runs = requirementService.runs(r);
            boolean ran = runs.stream()
                    .anyMatch(run ->
                            !run.createdAt().isBefore(from) && run.createdAt().isBefore(to));
            Instant completedAt = null;
            boolean estimated = false;
            if (List.of("in_review", "done").contains(r.status())) {
                completedAt = runs.stream()
                        .filter(run -> run.status() == RunStatus.SUCCEEDED)
                        .map(Run::updatedAt)
                        .max(Instant::compareTo)
                        .orElse(null);
                if (completedAt == null) {
                    completedAt = r.updatedAt();
                    estimated = true;
                }
            }
            Long cycle = null;
            long execution = 0;
            long tokens = 0;
            if (completedAt != null && !runs.isEmpty()) {
                Instant first = runs.stream()
                        .map(Run::createdAt)
                        .min(Instant::compareTo)
                        .orElseThrow();
                cycle = completedAt.isAfter(first)
                        ? Duration.between(first, completedAt).toMillis()
                        : null;
                for (Run run : runs) {
                    execution += RequirementSnapshot.executionMillis(run, now);
                    tokens += run.usage().totalTokens();
                }
            }
            List<PullRequest> merged =
                    r.deliveries().stream().filter(PullRequest::merged).toList();
            out.add(new Work(completedAt, estimated, cycle, execution, tokens, merged, ran));
        }
        return out;
    }

    static DeliveryMetrics metrics(List<Work> items, Instant from, Instant to) {
        int completed = 0;
        int estimated = 0;
        int merged = 0;
        int mergedMissing = 0;
        List<Long> toMerge = new ArrayList<>();
        int missingMergeIssues = 0;
        int mergedBeforeIssues = 0;
        int mergedBeforeMrs = 0;
        List<Long> cycles = new ArrayList<>();
        List<Long> executions = new ArrayList<>();
        long tokens = 0;
        Instant latest = null;
        for (Work w : items) {
            boolean done = w.completedAt() != null && in(w.completedAt(), from, to);
            if (done) {
                completed++;
                if (w.estimated()) {
                    estimated++;
                }
                latest = latest == null || w.completedAt().isAfter(latest) ? w.completedAt() : latest;
                if (w.cycleMs() != null) {
                    cycles.add(w.cycleMs());
                }
                if (w.executionMs() > 0) {
                    executions.add(w.executionMs());
                }
                tokens += w.tokens();
                if (w.merged().isEmpty()) {
                    missingMergeIssues++;
                }
            }
            boolean before = false;
            for (PullRequest pr : w.merged()) {
                if (pr.mergedAt() == null) {
                    if (done) {
                        mergedMissing++;
                    }
                    continue;
                }
                if (!in(pr.mergedAt(), from, to)) {
                    continue;
                }
                merged++;
                if (w.completedAt() != null) {
                    long d = Duration.between(w.completedAt(), pr.mergedAt()).toMillis();
                    if (d < 0) {
                        mergedBeforeMrs++;
                        before = true;
                    } else {
                        toMerge.add(d);
                    }
                }
            }
            if (before) {
                mergedBeforeIssues++;
            }
        }
        return new DeliveryMetrics(
                completed,
                estimated,
                merged,
                mergedMissing,
                toMerge.size(),
                average(toMerge),
                missingMergeIssues,
                mergedBeforeIssues,
                mergedBeforeMrs,
                cycles.size(),
                average(cycles),
                percentile(cycles, 50),
                percentile(cycles, 95),
                executions.size(),
                average(executions),
                tokens,
                completed == 0 ? 0 : tokens / completed,
                latest);
    }

    static List<DeliveryTrendPoint> deliveryTrend(
            List<Work> items, Instant from, Instant to, String grain, ZoneId zone) {
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, List<Long>> cycles = new TreeMap<>();
        Map<String, List<Long>> executions = new TreeMap<>();
        for (Work w : items) {
            if (w.completedAt() != null && in(w.completedAt(), from, to)) {
                String key = bucket(w.completedAt(), grain, zone);
                counts.computeIfAbsent(key, k -> new int[2])[0]++;
                if (w.cycleMs() != null) {
                    cycles.computeIfAbsent(key, k -> new ArrayList<>()).add(w.cycleMs());
                }
                if (w.executionMs() > 0) {
                    executions.computeIfAbsent(key, k -> new ArrayList<>()).add(w.executionMs());
                }
            }
            for (PullRequest pr : w.merged()) {
                if (pr.mergedAt() != null && in(pr.mergedAt(), from, to)) {
                    counts.computeIfAbsent(bucket(pr.mergedAt(), grain, zone), k -> new int[2])[1]++;
                }
            }
        }
        List<DeliveryTrendPoint> out = new ArrayList<>();
        for (var e : counts.entrySet()) {
            List<Long> c = cycles.getOrDefault(e.getKey(), List.of());
            List<Long> x = executions.getOrDefault(e.getKey(), List.of());
            out.add(new DeliveryTrendPoint(
                    e.getKey(), e.getValue()[0], e.getValue()[1], c.size(), average(c), x.size(), average(x)));
        }
        return out;
    }

    static String bucket(Instant at, String grain, ZoneId zone) {
        ZonedDateTime t = at.atZone(zone);
        return switch (grain) {
            case "week" ->
                t.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .toString();
            case "month" -> t.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            default -> t.toLocalDate().toString();
        };
    }

    private static boolean in(Instant at, Instant from, Instant to) {
        return !at.isBefore(from) && at.isBefore(to);
    }

    private static long average(List<Long> values) {
        return values.isEmpty()
                ? 0
                : Math.round(
                        values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}
