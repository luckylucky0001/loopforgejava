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

    public Notifier(NotificationStore channels, Clock clock) {
        this.channels = channels;
        this.clock = clock;
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
        String body = Json.write(payload(event, run));
        for (NotificationChannel channel : targets) {
            Thread.ofVirtual().name("notify-" + channel.id()).start(() -> deliver(channel, event, body));
        }
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
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        String status = "failed";
        try {
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
                    status = "error: " + e.getClass().getSimpleName();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            slots.release();
        }
        try {
            String finalStatus = status;
            channels.find(channel.id())
                    .ifPresent(c -> channels.save(c.delivered(clock.instant(), event + ": " + finalStatus)));
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
