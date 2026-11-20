package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.engine.RunListener;
import io.github.loopforge4j.core.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends run events to webhooks. Transitions are detected from committed run changes on the instance
 * that made them, so each event is sent once per channel; deliveries run off the engine thread with
 * three attempts and exponential backoff.
 */
@Component
public class Notifier implements RunListener {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    private static final List<Duration> BACKOFF = List.of(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(10));

    private final NotificationStore channels;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /** Bounds concurrent deliveries; excess events wait for a slot on their own virtual thread. */
    private final Semaphore slots = new Semaphore(32);

    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    private record Seen(RunStatus status, int merged) {}

    private final boolean allowPrivate;
    private final String publicUrl;
    private final org.springframework.beans.factory.ObjectProvider<RunReports> reports;

    public Notifier(
            NotificationStore channels,
            Clock clock,
            io.github.loopforge4j.server.config.LoopForgeProperties properties,
            org.springframework.beans.factory.ObjectProvider<RunReports> reports) {
        this.channels = channels;
        this.clock = clock;
        this.reports = reports;
        this.allowPrivate = properties.notifications().allowPrivateTargets();
        this.publicUrl = properties.publicUrl();
    }

    @Override
    public void runChanged(Run run) {
        int merged = (int) run.deliveries().stream()
                .filter(p -> "merged".equals(p.state()))
                .count();
        Seen before = seen.put(run.id(), new Seen(run.status(), merged));
        if (before == null || before.status() != run.status()) {
            String event = switch (run.status()) {
                case RUNNING -> before == null || before.status() == RunStatus.QUEUED ? "run.started" : null;
                case WAITING -> "run.waiting";
                case SUCCEEDED -> "run.succeeded";
                case FAILED -> "run.failed";
                case CANCELLED -> "run.cancelled";
                default -> null;
            };
            // a run seen for the first time (e.g. after a restart) has no known transition except a fresh start
            if (event != null && (before != null || ("run.started".equals(event) && isFresh(run)))) {
                publish(event, run);
            }
        }
        if (before != null && merged > before.merged()) {
            publish("delivery.merged", run);
        }
        if (run.status().terminal() && run.deliveries().stream().noneMatch(PullRequest::open)) {
            seen.remove(run.id());
        }
    }

    /** A run first seen while running is new only if it was created moments ago. */
    private boolean isFresh(Run run) {
        return run.createdAt() != null
                && run.createdAt().isAfter(clock.instant().minusSeconds(30));
    }

    void publish(String event, Run run) {
        List<NotificationChannel> targets;
        try {
            targets = channels.forProject(run.context().projectId()).stream()
                    .filter(c -> c.wants(event))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("cannot load notification channels: {}", e.toString());
            return;
        }
        if (targets.isEmpty()) {
            return;
        }
        // channels asking for the same report share one generation
        Map<NotificationChannel.Report, java.util.concurrent.CompletableFuture<RunReports.Report>> generated =
                new ConcurrentHashMap<>();
        for (NotificationChannel channel : targets) {
            Thread.ofVirtual().name("notify-" + channel.id()).start(() -> {
                RunReports.Report report = channel.reports(event) ? report(generated, channel.report(), run) : null;
                if ("webhook".equals(channel.kind())) {
                    deliver(channel, event, Json.write(payload(event, run, report)), report);
                } else {
                    deliverBot(channel, event, run, report);
                }
            });
        }
    }

    private RunReports.Report report(
            Map<NotificationChannel.Report, java.util.concurrent.CompletableFuture<RunReports.Report>> generated,
            NotificationChannel.Report settings,
            Run run) {
        java.util.concurrent.CompletableFuture<RunReports.Report> mine = new java.util.concurrent.CompletableFuture<>();
        var existing = generated.putIfAbsent(settings, mine);
        if (existing != null) {
            return existing.join();
        }
        RunReports.Report report = RunReports.Report.fallback("整体汇报生成失败");
        try {
            report = reports.getObject().generate(run, settings);
        } catch (RuntimeException e) {
            log.warn("cannot generate the report of run {}: {}", run.id(), e.toString());
        } finally {
            // channels waiting on the same report must never wait forever
            mine.complete(report);
        }
        return report;
    }

    Map<String, Object> payload(String event, Run run, RunReports.Report report) {
        Map<String, Object> payload = payload(event, run);
        if (report != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("state", report.state());
            if (report.generated()) {
                out.put("title", report.title());
                out.put("summary", report.summary());
                out.put("body", report.body());
            } else {
                out.put("error", report.error());
            }
            payload.put("report", out);
        }
        return payload;
    }

    private static String suffix(RunReports.Report report) {
        if (report == null) {
            return "";
        }
        return report.generated() ? " · AI 汇报" : " · 汇报回退：" + report.error();
    }

    Map<String, Object> payload(String event, Run run) {
        Map<String, Object> runInfo = new LinkedHashMap<>();
        runInfo.put("id", run.id());
        runInfo.put("workflowId", run.workflowId());
        runInfo.put("status", run.status().wire());
        runInfo.put("projectId", run.context().projectId());
        runInfo.put("triggeredBy", run.context().triggeredBy());
        runInfo.put("deliveries", run.deliveries());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        payload.put("event", event);
        payload.put("occurredAt", clock.instant().toString());
        payload.put("run", runInfo);
        return payload;
    }

    void deliver(NotificationChannel channel, String event, String body) {
        deliver(channel, event, body, null);
    }

    void deliver(NotificationChannel channel, String event, String body, RunReports.Report report) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        String status = "failed";
        try {
            String blocked = WebhookTargets.problem(channel.url(), allowPrivate);
            if (blocked != null) {
                status = "blocked";
                return;
            }
            for (Duration wait : BACKOFF) {
                if (!wait.isZero()) {
                    Thread.sleep(wait.toMillis());
                }
                try {
                    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(channel.url()))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .header("User-Agent", "loopforge4j-webhook")
                            .header("X-LoopForge-Event", event)
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                    if (channel.hasSecret()) {
                        request.header("X-LoopForge-Signature", "sha256=" + sign(channel.secret(), body));
                    }
                    int code = http.send(request.build(), HttpResponse.BodyHandlers.discarding())
                            .statusCode();
                    status = "http " + code;
                    if (code >= 200 && code < 300) {
                        break;
                    }
                    if (code >= 400 && code < 500 && code != 429) {
                        break; // the receiver rejects it; retrying will not help
                    }
                } catch (java.io.IOException e) {
                    // no exception detail: it would turn the status into a network probe
                    status = "error";
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            slots.release();
            record(channel, event, status + suffix(report));
        }
    }

    /** A chat robot message in the platform's own format; the platform's error code decides success. */
    void deliverBot(NotificationChannel channel, String event, Run run) {
        deliverBot(channel, event, run, null);
    }

    void deliverBot(NotificationChannel channel, String event, Run run, RunReports.Report report) {
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        String status = "failed";
        try {
            if (WebhookTargets.problem(channel.url(), allowPrivate) != null) {
                status = "blocked";
                return;
            }
            for (Duration wait : BACKOFF) {
                if (!wait.isZero()) {
                    Thread.sleep(wait.toMillis());
                }
                // rendered per attempt: signatures carry a timestamp
                BotMessages.Outgoing message =
                        BotMessages.render(channel, event, run, publicUrl, clock.instant(), report);
                try {
                    HttpResponse<java.io.InputStream> response = http.send(
                            HttpRequest.newBuilder(message.uri())
                                    .timeout(Duration.ofSeconds(15))
                                    .header("Content-Type", "application/json")
                                    .header("User-Agent", "loopforge4j-notifier")
                                    .POST(HttpRequest.BodyPublishers.ofString(message.body(), StandardCharsets.UTF_8))
                                    .build(),
                            HttpResponse.BodyHandlers.ofInputStream());
                    String reply;
                    // the platform's answer is a small JSON object; a target streaming more gets cut off
                    try (java.io.InputStream in = response.body()) {
                        reply = new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
                    }
                    String rejection = BotMessages.rejection(channel.kind(), response.statusCode(), reply);
                    status = rejection == null ? "ok" : rejection;
                    // only server errors and rate limiting are worth another attempt
                    if (rejection == null || response.statusCode() < 500 && response.statusCode() != 429) {
                        break;
                    }
                } catch (java.io.IOException e) {
                    status = "error";
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("cannot notify channel {}: {}", channel.id(), e.getClass().getSimpleName());
            status = "error";
        } finally {
            slots.release();
            record(channel, event, status + suffix(report));
        }
    }

    private void record(NotificationChannel channel, String event, String status) {
        try {
            channels.find(channel.id())
                    .ifPresent(c -> channels.save(c.delivered(clock.instant(), event + ": " + status)));
        } catch (RuntimeException e) {
            log.debug("cannot record delivery status: {}", e.toString());
        }
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
