package io.github.loopforge4j.server.work;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A target that receives run events of a project (or of every project for a global channel).
 * {@code webhook} deliveries are JSON with an {@code X-LoopForge-Signature: sha256=<hmac>} header
 * when a secret is set; the bot kinds send each platform's native robot message (upstream
 * notification workflows; WeCom, Feishu/Lark and DingTalk group robots, Slack incoming webhooks).
 *
 * @param kind {@code webhook} (default), {@code wecom}, {@code feishu}, {@code dingtalk} or {@code
 *     slack}
 * @param events subset of {@link #EVENTS}; empty means all
 * @param secret webhook HMAC key, or the robot's signing secret (Feishu, DingTalk); write-only,
 *     never returned by the API
 * @param report an AI-written overall report for {@code run.succeeded} (upstream notification
 *     reports); {@code null} or disabled sends the standard message
 */
public record NotificationChannel(
        String id,
        String projectId,
        String name,
        boolean enabled,
        String kind,
        String url,

        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String secret,

        List<String> events,
        Instant lastDeliveryAt,
        String lastStatus,
        Instant createdAt,
        Instant updatedAt,
        Report report) {

    /**
     * @param instruction what the report should focus on (upstream {@code AIInstruction})
     * @param executorId executor that writes it, the assistant's when empty
     * @param model model of that executor, its default when empty
     */
    public record Report(boolean enabled, String instruction, String executorId, String model) {
        public Report {
            instruction = instruction == null ? "" : instruction.strip();
            executorId = executorId == null || executorId.isBlank() ? null : executorId.strip();
            model = model == null || model.isBlank() ? null : model.strip();
        }
    }

    public static final Set<String> EVENTS =
            Set.of("run.started", "run.waiting", "run.succeeded", "run.failed", "run.cancelled", "delivery.merged");

    public static final Set<String> KINDS = Set.of("webhook", "wecom", "feishu", "dingtalk", "slack");

    public NotificationChannel {
        kind = kind == null || kind.isBlank() ? "webhook" : kind.strip();
        events = events == null ? List.of() : List.copyOf(events);
    }

    public boolean hasSecret() {
        return secret != null && !secret.isBlank();
    }

    boolean wants(String event) {
        return enabled && (events.isEmpty() || events.contains(event));
    }

    NotificationChannel delivered(Instant at, String status) {
        return new NotificationChannel(
                id, projectId, name, enabled, kind, url, secret, events, at, status, createdAt, updatedAt, report);
    }

    /** Whether {@code event} asks for an AI report on this channel. */
    boolean reports(String event) {
        return "run.succeeded".equals(event) && report != null && report.enabled();
    }
}
