package io.github.loopforge4j.server.analytics;

import io.github.loopforge4j.core.domain.GateDecision;
import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.server.persistence.SqlTime;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Usage and delivery analytics over runs (upstream {@code /api/analytics} and {@code
 * /api/workflow-metrics}). Run counts and outcomes are about top-level runs; tokens and cost of the
 * child runs an {@code sop} node started are added to their root run.
 */
@Service
public class AnalyticsService {

    /** At most this many runs are read for one report; the report says when it hit the limit. */
    static final int MAX_RUNS = 5_000;

    /** Reports read whole run documents: only a few are computed at a time on one instance. */
    private final java.util.concurrent.Semaphore reports = new java.util.concurrent.Semaphore(2);

    private final JdbcClient jdbc;
    private final Clock clock;

    public AnalyticsService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param projects visible projects, {@code null} for all (administrators)
     * @param grain {@code day}, {@code week} or {@code month}
     */
    public record Filter(
            String projectId,
            String workflowId,
            Instant from,
            Instant to,
            String grain,
            ZoneId zone,
            Set<String> projects) {}

    public record Summary(
            int totalRuns,
            int activeRuns,
            int waitingRuns,
            int succeededRuns,
            int failedRuns,
            int cancelledRuns,
            int reworkedRuns,
            int totalReworks,
            int retriedNodes,
            double completionRate,
            double successRate,
            double cancelRate,
            double firstPassRate,
            long averageDurationMs,
            long p50DurationMs,
            long p95DurationMs,
            long p99DurationMs,
            long executionAverageDurationMs,
            long executionP95DurationMs,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheWriteTokens,
            long reasoningTokens,
            long totalTokens,
            double totalCostUsd,
            int costUnknownRuns,
            double costCoveragePct,
            double cacheHitRate,
            int toolCalls,
            int pullRequests,
            int mergedPullRequests,
            int openPullRequests) {}

    public record TrendPoint(
            String date,
            int runs,
            int succeeded,
            int failed,
            int cancelled,
            int reworks,
            long durationMs,
            long tokens,
            double costUsd,
            int mergedPullRequests) {}

    public record Breakdown(
            String key,
            int runs,
            int succeeded,
            int failed,
            int cancelled,
            int active,
            int reworks,
            double successRate,
            long averageDurationMs,
            long p95DurationMs,
            long tokens,
            double costUsd,
            double tokenSharePct,
            double costSharePct,
            int toolCalls) {}

    public record Failure(String message, int runs, String sampleRunId, String nodeId) {}

    public record HeatCell(int weekday, int hour, int runs, long tokens) {}

    public record Report(
            Instant from,
            Instant to,
            String grain,
            String zone,
            boolean truncated,
            Summary summary,
            List<TrendPoint> trend,
            List<Breakdown> byWorkflow,
            List<Breakdown> byProject,
            List<Breakdown> byExecutor,
            List<Breakdown> byModel,
            List<Failure> failures,
            List<HeatCell> heatmap,
            Instant generatedAt) {}

    public record NodeMetric(
            String nodeId,
            int executions,
            int succeeded,
            int failed,
            int reworks,
            int retries,
            long averageDurationMs,
            int gatePassed,
            int gateRejected,
            long tokens,
            double costUsd) {}

    public record WorkflowMetric(
            String workflowId,
            String name,
            int runs,
            int succeeded,
            int failed,
            int cancelled,
            int active,
            double successRate,
            double reworkRate,
            long averageDurationMs,
            long p95DurationMs,
            long tokens,
            double costUsd,
            Instant lastRunAt,
            List<NodeMetric> nodes) {}

    /** A top-level run with the usage of its child runs added. */
    private record Root(Run run, TokenUsage usage, int toolCalls) {}

    public Report report(Filter filter) {
        return bounded(() -> compute(filter));
    }

    private Report compute(Filter filter) {
        Loaded loaded = load(filter);
        List<Root> roots = loaded.roots();
        ZoneId zone = filter.zone();
        Summary summary = summarize(roots);

        TokenUsage all = roots.stream().map(Root::usage).reduce(TokenUsage.ZERO, TokenUsage::plus);
        List<Breakdown> byWorkflow = breakdown(roots, r -> r.run().workflowId(), all);
        List<Breakdown> byProject = breakdown(
                roots,
                r -> r.run().context().projectId() == null
                        ? "-"
                        : r.run().context().projectId(),
                all);
        List<Breakdown> byExecutor = nodeBreakdown(roots, n -> n.provider() == null ? "-" : n.provider(), all);
        List<Breakdown> byModel = nodeBreakdown(roots, n -> n.model() == null ? "-" : n.model(), all);

        return new Report(
                filter.from(),
                filter.to(),
                filter.grain(),
                zone.getId(),
                loaded.truncated(),
                summary,
                trend(roots, filter, zone),
                byWorkflow,
                byProject,
                byExecutor,
                byModel,
                failures(roots),
                heatmap(roots, zone),
                clock.instant());
    }

    public List<WorkflowMetric> workflowMetrics(Filter filter) {
        return bounded(() -> metrics(filter));
    }

    private <T> T bounded(java.util.function.Supplier<T> work) {
        boolean acquired;
        try {
            acquired = reports.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new io.github.loopforge4j.server.web.ApiException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "analytics is busy; try again shortly");
        }
        try {
            return work.get();
        } finally {
            reports.release();
        }
    }

    private List<WorkflowMetric> metrics(Filter filter) {
        Map<String, List<Root>> byWorkflow = new TreeMap<>();
        for (Root root : load(filter).roots()) {
            byWorkflow
                    .computeIfAbsent(root.run().workflowId(), k -> new ArrayList<>())
                    .add(root);
        }
        List<WorkflowMetric> out = new ArrayList<>();
        byWorkflow.forEach((workflowId, roots) -> {
            Counts c = counts(roots);
            List<Long> durations = durations(roots);
            TokenUsage usage = roots.stream().map(Root::usage).reduce(TokenUsage.ZERO, TokenUsage::plus);
            Run latest = roots.stream()
                    .map(Root::run)
                    .max(Comparator.comparing(Run::createdAt))
                    .orElseThrow();
            Map<String, NodeAcc> nodes = new LinkedHashMap<>();
            for (Root root : roots) {
                for (NodeRun n : root.run().nodeRuns()) {
                    if (n.attempt() == 0 && n.status() == NodeStatus.PENDING) {
                        continue;
                    }
                    NodeAcc acc = nodes.computeIfAbsent(n.nodeId(), k -> new NodeAcc());
                    acc.executions++;
                    acc.succeeded += n.status() == NodeStatus.SUCCEEDED ? 1 : 0;
                    acc.failed += n.status() == NodeStatus.FAILED ? 1 : 0;
                    acc.reworks += n.reworkCount();
                    acc.retries += Math.max(0, n.attempt() - 1);
                    if (n.startedAt() != null && n.finishedAt() != null) {
                        acc.durations.add(
                                Duration.between(n.startedAt(), n.finishedAt()).toMillis());
                    }
                    if (n.gateResult() != null) {
                        if (n.gateResult().decision() == GateDecision.PASS) {
                            acc.gatePassed++;
                        } else {
                            acc.gateRejected++;
                        }
                    }
                    acc.usage = acc.usage.plus(n.usage());
                }
            }
            List<NodeMetric> nodeMetrics = new ArrayList<>();
            nodes.forEach((id, acc) -> nodeMetrics.add(new NodeMetric(
                    id,
                    acc.executions,
                    acc.succeeded,
                    acc.failed,
                    acc.reworks,
                    acc.retries,
                    average(acc.durations),
                    acc.gatePassed,
                    acc.gateRejected,
                    acc.usage.totalTokens(),
                    round(acc.usage.costUsd()))));
            int reworked =
                    (int) roots.stream().filter(r -> reworks(r.run()) > 0).count();
            out.add(new WorkflowMetric(
                    workflowId,
                    latest.workflow().name(),
                    roots.size(),
                    c.succeeded,
                    c.failed,
                    c.cancelled,
                    c.active,
                    ratio(c.succeeded, c.succeeded + c.failed),
                    ratio(reworked, roots.size()),
                    average(durations),
                    percentile(durations, 95),
                    usage.totalTokens(),
                    round(usage.costUsd()),
                    latest.createdAt(),
                    nodeMetrics));
        });
        out.sort(Comparator.comparingInt(WorkflowMetric::runs).reversed());
        return out;
    }

    // ------------------------------------------------------------------ loading

    private record Loaded(List<Root> roots, boolean truncated) {}

    private Loaded load(Filter filter) {
        if (filter.projects() != null && filter.projects().isEmpty()) {
            return new Loaded(List.of(), false);
        }
        StringBuilder sql =
                new StringBuilder("SELECT payload FROM runs WHERE created_at >= :from AND created_at < :to");
        if (filter.projectId() != null) {
            sql.append(" AND project_id = :project");
        }
        if (filter.projects() != null) {
            sql.append(" AND project_id IN (:projects)");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        var statement = jdbc.sql(sql.toString())
                .param("from", SqlTime.of(filter.from()))
                .param("to", SqlTime.of(filter.to()))
                .param("limit", MAX_RUNS + 1);
        if (filter.projectId() != null) {
            statement = statement.param("project", filter.projectId());
        }
        if (filter.projects() != null) {
            statement = statement.param("projects", List.copyOf(filter.projects()));
        }
        List<Run> runs = statement.query(String.class).list().stream()
                .map(raw -> Json.read(raw, Run.class))
                .toList();
        boolean truncated = runs.size() > MAX_RUNS;
        if (truncated) {
            runs = runs.subList(0, MAX_RUNS);
        }
        Map<String, Run> byId = new HashMap<>();
        runs.forEach(r -> byId.put(r.id(), r));
        Map<String, TokenUsage> usage = new HashMap<>();
        Map<String, Integer> tools = new HashMap<>();
        for (Run run : runs) {
            String root = rootOf(run, byId);
            usage.merge(root, run.usage(), TokenUsage::plus);
            tools.merge(root, run.toolCalls(), Integer::sum);
        }
        List<Root> roots = new ArrayList<>();
        for (Run run : runs) {
            if (run.context().childRun()) {
                continue;
            }
            if (filter.workflowId() != null && !filter.workflowId().equals(run.workflowId())) {
                continue;
            }
            roots.add(new Root(run, usage.getOrDefault(run.id(), TokenUsage.ZERO), tools.getOrDefault(run.id(), 0)));
        }
        return new Loaded(roots, truncated);
    }

    private static String rootOf(Run run, Map<String, Run> byId) {
        Run current = run;
        for (int depth = 0; depth < 32 && current.context().childRun(); depth++) {
            Run parent = byId.get(current.context().parentRunId());
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current.id();
    }

    // ------------------------------------------------------------------ aggregation

    private static final class Counts {
        int succeeded;
        int failed;
        int cancelled;
        int active;
        int waiting;
    }

    private static final class NodeAcc {
        int executions;
        int succeeded;
        int failed;
        int reworks;
        int retries;
        int gatePassed;
        int gateRejected;
        final List<Long> durations = new ArrayList<>();
        TokenUsage usage = TokenUsage.ZERO;
    }

    private static Counts counts(Collection<Root> roots) {
        Counts c = new Counts();
        for (Root root : roots) {
            switch (root.run().status()) {
                case SUCCEEDED -> c.succeeded++;
                case FAILED -> c.failed++;
                case CANCELLED -> c.cancelled++;
                case WAITING -> c.waiting++;
                case QUEUED, RUNNING -> c.active++;
            }
        }
        return c;
    }

    private Summary summarize(List<Root> roots) {
        Counts c = counts(roots);
        int terminal = c.succeeded + c.failed + c.cancelled;
        int reworkedRuns = 0;
        int totalReworks = 0;
        int retriedNodes = 0;
        int firstPass = 0;
        for (Root root : roots) {
            int reworks = reworks(root.run());
            int retried = (int)
                    root.run().nodeRuns().stream().filter(n -> n.attempt() > 1).count();
            totalReworks += reworks;
            retriedNodes += retried;
            if (reworks > 0) {
                reworkedRuns++;
            }
            if (root.run().status() == RunStatus.SUCCEEDED && reworks == 0 && retried == 0) {
                firstPass++;
            }
        }
        List<Long> durations = durations(roots);
        List<Long> execution = new ArrayList<>();
        for (Root root : roots) {
            executionDuration(root.run()).ifPresent(execution::add);
        }
        TokenUsage usage = roots.stream().map(Root::usage).reduce(TokenUsage.ZERO, TokenUsage::plus);
        int withTokens = 0;
        int unknownCost = 0;
        int toolCalls = 0;
        for (Root root : roots) {
            toolCalls += root.toolCalls();
            if (root.usage().totalTokens() > 0) {
                withTokens++;
                if (root.usage().costUsd() <= 0 || "unknown".equals(root.usage().costStatus())) {
                    unknownCost++;
                }
            }
        }
        int prs = 0;
        int merged = 0;
        int open = 0;
        for (Root root : roots) {
            for (PullRequest pr : root.run().deliveries()) {
                prs++;
                if ("merged".equals(pr.state())) {
                    merged++;
                } else if (pr.open()) {
                    open++;
                }
            }
        }
        long cacheBase = usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens();
        return new Summary(
                roots.size(),
                c.active,
                c.waiting,
                c.succeeded,
                c.failed,
                c.cancelled,
                reworkedRuns,
                totalReworks,
                retriedNodes,
                ratio(c.succeeded, terminal),
                ratio(c.succeeded, c.succeeded + c.failed),
                ratio(c.cancelled, terminal),
                ratio(firstPass, terminal),
                average(durations),
                percentile(durations, 50),
                percentile(durations, 95),
                percentile(durations, 99),
                average(execution),
                percentile(execution, 95),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.reasoningTokens(),
                usage.totalTokens(),
                round(usage.costUsd()),
                unknownCost,
                withTokens == 0 ? 100 : round(100.0 * (withTokens - unknownCost) / withTokens),
                cacheBase == 0 ? 0 : round((double) usage.cacheReadTokens() / cacheBase),
                toolCalls,
                prs,
                merged,
                open);
    }

    /** Every bucket of the range, empty ones included, so the time axis is continuous. */
    private static List<TrendPoint> trend(List<Root> roots, Filter filter, ZoneId zone) {
        String grain = filter.grain();
        Map<String, List<Root>> buckets = new TreeMap<>();
        LocalDate day = filter.from().atZone(zone).toLocalDate();
        LocalDate last = filter.to().minusMillis(1).atZone(zone).toLocalDate();
        for (int i = 0; !day.isAfter(last) && i < 400; i++) {
            buckets.put(bucket(day.atStartOfDay(zone).toInstant(), grain, zone), new ArrayList<>());
            day = switch (grain) {
                case "week" -> day.plusWeeks(1);
                case "month" -> day.plusMonths(1);
                default -> day.plusDays(1);
            };
        }
        buckets.putIfAbsent(bucket(filter.to().minusMillis(1), grain, zone), new ArrayList<>());
        for (Root root : roots) {
            buckets.computeIfAbsent(bucket(root.run().createdAt(), grain, zone), k -> new ArrayList<>())
                    .add(root);
        }
        List<TrendPoint> out = new ArrayList<>();
        buckets.forEach((date, list) -> {
            Counts c = counts(list);
            TokenUsage usage = list.stream().map(Root::usage).reduce(TokenUsage.ZERO, TokenUsage::plus);
            int merged = (int) list.stream()
                    .flatMap(r -> r.run().deliveries().stream())
                    .filter(p -> "merged".equals(p.state()))
                    .count();
            out.add(new TrendPoint(
                    date,
                    list.size(),
                    c.succeeded,
                    c.failed,
                    c.cancelled,
                    list.stream().mapToInt(r -> reworks(r.run())).sum(),
                    average(durations(list)),
                    usage.totalTokens(),
                    round(usage.costUsd()),
                    merged));
        });
        return out;
    }

    static String bucket(Instant at, String grain, ZoneId zone) {
        LocalDate date = at.atZone(zone).toLocalDate();
        return switch (grain) {
            case "week" ->
                date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
            case "month" -> date.withDayOfMonth(1).toString().substring(0, 7);
            default -> date.toString();
        };
    }

    private static List<Breakdown> breakdown(List<Root> roots, Function<Root, String> key, TokenUsage all) {
        Map<String, List<Root>> groups = new TreeMap<>();
        roots.forEach(r ->
                groups.computeIfAbsent(key.apply(r), k -> new ArrayList<>()).add(r));
        List<Breakdown> out = new ArrayList<>();
        groups.forEach((k, list) -> {
            Counts c = counts(list);
            List<Long> durations = durations(list);
            TokenUsage usage = list.stream().map(Root::usage).reduce(TokenUsage.ZERO, TokenUsage::plus);
            out.add(new Breakdown(
                    k,
                    list.size(),
                    c.succeeded,
                    c.failed,
                    c.cancelled,
                    c.active + c.waiting,
                    list.stream().mapToInt(r -> reworks(r.run())).sum(),
                    ratio(c.succeeded, c.succeeded + c.failed),
                    average(durations),
                    percentile(durations, 95),
                    usage.totalTokens(),
                    round(usage.costUsd()),
                    share(usage.totalTokens(), all.totalTokens()),
                    share(usage.costUsd(), all.costUsd()),
                    list.stream().mapToInt(Root::toolCalls).sum()));
        });
        out.sort(Comparator.comparingInt(Breakdown::runs).reversed());
        return out;
    }

    /** Executor and model breakdowns count node executions of top-level runs. */
    private static List<Breakdown> nodeBreakdown(List<Root> roots, Function<NodeRun, String> key, TokenUsage all) {
        Map<String, NodeAcc> groups = new TreeMap<>();
        Map<String, Integer> active = new HashMap<>();
        Map<String, Integer> cancelled = new HashMap<>();
        Map<String, Integer> tools = new HashMap<>();
        for (Root root : roots) {
            for (NodeRun n : root.run().nodeRuns()) {
                if (n.provider() == null && n.model() == null) {
                    continue; // scripts, gates and nodes that never ran an agent
                }
                String k = key.apply(n);
                NodeAcc acc = groups.computeIfAbsent(k, x -> new NodeAcc());
                acc.executions++;
                acc.succeeded += n.status() == NodeStatus.SUCCEEDED ? 1 : 0;
                acc.failed += n.status() == NodeStatus.FAILED ? 1 : 0;
                acc.reworks += n.reworkCount();
                if (n.status() == NodeStatus.CANCELLED) {
                    cancelled.merge(k, 1, Integer::sum);
                }
                if (n.status() == NodeStatus.RUNNING || n.status() == NodeStatus.WAITING_APPROVAL) {
                    active.merge(k, 1, Integer::sum);
                }
                if (n.startedAt() != null && n.finishedAt() != null) {
                    acc.durations.add(
                            Duration.between(n.startedAt(), n.finishedAt()).toMillis());
                }
                acc.usage = acc.usage.plus(n.usage());
                tools.merge(k, n.toolCalls(), Integer::sum);
            }
        }
        List<Breakdown> out = new ArrayList<>();
        groups.forEach((k, acc) -> out.add(new Breakdown(
                k,
                acc.executions,
                acc.succeeded,
                acc.failed,
                cancelled.getOrDefault(k, 0),
                active.getOrDefault(k, 0),
                acc.reworks,
                ratio(acc.succeeded, acc.succeeded + acc.failed),
                average(acc.durations),
                percentile(acc.durations, 95),
                acc.usage.totalTokens(),
                round(acc.usage.costUsd()),
                share(acc.usage.totalTokens(), all.totalTokens()),
                share(acc.usage.costUsd(), all.costUsd()),
                tools.getOrDefault(k, 0))));
        out.sort(Comparator.comparingLong(Breakdown::tokens).reversed());
        return out;
    }

    private static List<Failure> failures(List<Root> roots) {
        Map<String, List<String[]>> groups = new LinkedHashMap<>();
        for (Root root : roots) {
            if (root.run().status() != RunStatus.FAILED) {
                continue;
            }
            NodeRun failed = root.run().nodeRuns().stream()
                    .filter(n -> n.status() == NodeStatus.FAILED)
                    .findFirst()
                    .orElse(null);
            String message =
                    failed == null || failed.error() == null ? "(no error recorded)" : firstLine(failed.error());
            groups.computeIfAbsent(message, k -> new ArrayList<>())
                    .add(new String[] {root.run().id(), failed == null ? null : failed.nodeId()});
        }
        List<Failure> out = new ArrayList<>();
        groups.forEach((message, list) -> out.add(new Failure(message, list.size(), list.get(0)[0], list.get(0)[1])));
        out.sort(Comparator.comparingInt(Failure::runs).reversed());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    private static List<HeatCell> heatmap(List<Root> roots, ZoneId zone) {
        Map<Integer, int[]> runs = new TreeMap<>();
        Map<Integer, Long> tokens = new HashMap<>();
        for (Root root : roots) {
            ZonedDateTime at = root.run().createdAt().atZone(zone);
            int key = at.getDayOfWeek().getValue() * 100 + at.getHour();
            runs.computeIfAbsent(key, k -> new int[1])[0]++;
            tokens.merge(key, root.usage().totalTokens(), Long::sum);
        }
        List<HeatCell> out = new ArrayList<>();
        runs.forEach((key, count) -> out.add(new HeatCell(key / 100, key % 100, count[0], tokens.get(key))));
        return out;
    }

    private static List<Long> durations(Collection<Root> roots) {
        List<Long> out = new ArrayList<>();
        for (Root root : roots) {
            Run run = root.run();
            if (run.status().terminal() && run.createdAt() != null && run.updatedAt() != null) {
                out.add(Math.max(
                        0, Duration.between(run.createdAt(), run.updatedAt()).toMillis()));
            }
        }
        return out;
    }

    /** From the first node start to the last node finish: time spent executing, not queued. */
    private static java.util.Optional<Long> executionDuration(Run run) {
        if (!run.status().terminal()) {
            return java.util.Optional.empty();
        }
        Instant start = null;
        Instant end = null;
        for (NodeRun n : run.nodeRuns()) {
            if (n.startedAt() != null && (start == null || n.startedAt().isBefore(start))) {
                start = n.startedAt();
            }
            if (n.finishedAt() != null && (end == null || n.finishedAt().isAfter(end))) {
                end = n.finishedAt();
            }
        }
        return start == null || end == null || end.isBefore(start)
                ? java.util.Optional.empty()
                : java.util.Optional.of(Duration.between(start, end).toMillis());
    }

    private static int reworks(Run run) {
        return run.nodeRuns().stream().mapToInt(NodeRun::reworkCount).sum();
    }

    static long average(List<Long> values) {
        return values.isEmpty()
                ? 0
                : Math.round(
                        values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    /** Nearest-rank percentile. */
    static long percentile(List<Long> values, int p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.min(sorted.size(), Math.max(1, rank)) - 1);
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 0 : round((double) part / whole);
    }

    private static double share(double part, double whole) {
        return whole <= 0 ? 0 : round(100.0 * part / whole);
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static String firstLine(String s) {
        String line = s.strip().lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }
}
