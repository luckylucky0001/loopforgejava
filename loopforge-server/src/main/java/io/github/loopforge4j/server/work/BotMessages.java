package io.github.loopforge4j.server.work;

import io.github.loopforge4j.core.domain.NodeRun;
import io.github.loopforge4j.core.domain.NodeStatus;
import io.github.loopforge4j.core.domain.PullRequest;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.json.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/**
 * Native robot messages for chat platforms: WeCom group robot (Markdown), Feishu/Lark custom bot
 * (text, optional signature), DingTalk custom robot (Markdown, optional signature) and Slack
 * incoming webhooks. Run text is clipped and never carries secrets: it is the SOP, project, status,
 * the first line of a failure and pull request links.
 */
final class BotMessages {

    /** What to POST: target URL (DingTalk signs in the query) and JSON body. */
    record Outgoing(URI uri, String body) {}

    private static final Map<String, String> TITLES = Map.of(
            "run.started", "运行开始",
            "run.waiting", "等待人工处理",
            "run.succeeded", "运行成功",
            "run.failed", "运行失败",
            "run.cancelled", "运行已取消",
            "delivery.merged", "代码已合并");

    private BotMessages() {}

    static Outgoing render(NotificationChannel channel, String event, Run run, String publicUrl, Instant now) {
        return render(channel, event, run, publicUrl, now, null);
    }

    /**
     * With an AI report the message leads with its title, summary and body (Markdown; links and
     * mentions are neutralised like all run text); a report that fell back adds why.
     */
    static Outgoing render(
            NotificationChannel channel,
            String event,
            Run run,
            String publicUrl,
            Instant now,
            RunReports.Report report) {
        List<String> lines = new ArrayList<>();
        if (report != null && report.generated()) {
            if (!report.summary().isBlank()) {
                lines.add(report.summary());
            }
            lines.addAll(report.body().lines().toList());
            lines.add("");
        }
        lines.addAll(lines(event, run, publicUrl));
        if (report != null && !report.generated()) {
            lines.add("汇报\u0000整体 AI 汇报暂不可用：" + clip(report.error(), 200));
        }
        String title = "LoopForge · "
                + (report != null && report.generated() && !report.title().isBlank()
                        ? escape(clip(report.title(), 120))
                        : TITLES.getOrDefault(event, event));
        return switch (channel.kind()) {
            case "wecom" ->
                new Outgoing(
                        URI.create(channel.url()),
                        Json.write(Map.of(
                                "msgtype",
                                "markdown",
                                "markdown",
                                Map.of("content", clip("**" + title + "**\n" + markdown(lines), 4000)))));
            case "dingtalk" -> {
                String url = channel.url();
                if (channel.hasSecret()) {
                    long ts = now.toEpochMilli();
                    String sign = base64(hmac(channel.secret(), ts + "\n" + channel.secret()));
                    url += (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign="
                            + URLEncoder.encode(sign, StandardCharsets.UTF_8);
                }
                yield new Outgoing(
                        URI.create(url),
                        Json.write(Map.of(
                                "msgtype",
                                "markdown",
                                "markdown",
                                Map.of("title", title, "text", clip("### " + title + "\n" + markdown(lines), 4000)))));
            }
            case "feishu" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                if (channel.hasSecret()) {
                    long ts = now.getEpochSecond();
                    // Feishu signs an empty message with the key "timestamp\nsecret"
                    body.put("timestamp", String.valueOf(ts));
                    body.put("sign", base64(hmac(ts + "\n" + channel.secret(), "")));
                }
                body.put("msg_type", "text");
                body.put("content", Map.of("text", clip(title + "\n" + String.join("\n", plain(lines)), 4000)));
                yield new Outgoing(URI.create(channel.url()), Json.write(body));
            }
            case "slack" ->
                new Outgoing(
                        URI.create(channel.url()),
                        Json.write(Map.of("text", clip("*" + title + "*\n" + String.join("\n", slack(lines)), 3000))));
            default -> throw new IllegalArgumentException("not a bot channel: " + channel.kind());
        };
    }

    /**
     * Whether a bot platform accepted the message: HTTP 2xx is not enough, WeCom and DingTalk answer
     * {@code errcode}, Feishu {@code code} (or {@code StatusCode}).
     *
     * @return {@code null} when accepted, else a short status without the response text
     */
    static String rejection(String kind, int status, String body) {
        if (status < 200 || status >= 300) {
            return "http " + status;
        }
        if ("slack".equals(kind)) {
            return null;
        }
        try {
            JsonNode reply = Json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
            JsonNode code = reply.has("errcode")
                    ? reply.get("errcode")
                    : reply.has("code") ? reply.get("code") : reply.get("StatusCode");
            if (code == null || code.asInt(0) == 0) {
                return null;
            }
            return "rejected " + code.asInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Message lines; each is "label\u0000value" or plain text, links as "\u0001text\u0001url". */
    static List<String> lines(String event, Run run, String publicUrl) {
        List<String> out = new ArrayList<>();
        String sop = run.workflow() == null || run.workflow().name() == null
                ? run.workflowId()
                : run.workflow().name();
        out.add("SOP\u0000" + clip(sop, 120) + "（" + run.workflowId() + "）");
        if (run.context().projectId() != null) {
            out.add("项目\u0000" + run.context().projectId());
        }
        out.add("状态\u0000" + run.status().wire());
        if (run.context().triggeredBy() != null) {
            out.add("触发\u0000" + clip(run.context().triggeredBy(), 80));
        }
        switch (event) {
            case "run.waiting" -> {
                for (NodeRun node : run.nodeRuns()) {
                    if (node.status() == NodeStatus.WAITING_APPROVAL) {
                        out.add("待处理\u0000节点 " + node.nodeId());
                    }
                }
            }
            case "run.failed" ->
                run.nodeRuns().stream()
                        .filter(n -> n.status() == NodeStatus.FAILED)
                        .findFirst()
                        .ifPresent(n -> out.add("失败\u0000" + n.nodeId() + "：" + clip(firstLine(n.error()), 200)));
            default -> {
                // no extra lines
            }
        }
        for (PullRequest pr : run.deliveries()) {
            if (pr.url() != null && pr.url().startsWith("http")) {
                out.add("\u0001" + ("merged".equals(pr.state()) ? "已合并 " : "PR ") + "#" + pr.number() + "\u0001"
                        + pr.url());
            }
        }
        if (publicUrl != null && !publicUrl.isBlank()) {
            out.add("\u0001查看运行\u0001" + publicUrl.replaceAll("/+$", "") + "/#/runs/" + run.id());
        } else {
            out.add("运行\u0000" + run.id());
        }
        return out;
    }

    private static String markdown(List<String> lines) {
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("\u0001")) {
                String[] parts = line.substring(1).split("\u0001", 2);
                b.append("[")
                        .append(escape(parts[0]))
                        .append("](")
                        .append(parts[1])
                        .append(")\n");
            } else if (line.contains("\u0000")) {
                String[] parts = line.split("\u0000", 2);
                b.append("> ")
                        .append(parts[0])
                        .append("：")
                        .append(escape(parts[1]))
                        .append('\n');
            } else {
                b.append(escape(line)).append('\n');
            }
        }
        return b.toString();
    }

    /** Feishu text: run text must not form {@code <at user_id="all">} mentions. */
    private static List<String> plain(List<String> lines) {
        return lines.stream()
                .map(l -> l.startsWith("\u0001")
                        ? l.substring(1).replace("\u0001", "：")
                        : l.replace("\u0000", "：").replace("<", "‹").replace(">", "›"))
                .toList();
    }

    private static List<String> slack(List<String> lines) {
        return lines.stream()
                .map(l -> {
                    if (l.startsWith("\u0001")) {
                        String[] parts = l.substring(1).split("\u0001", 2);
                        return "<" + parts[1] + "|" + parts[0].replaceAll("[<>|]", "") + ">";
                    }
                    return l.replace("\u0000", "：").replace("<", "‹").replace(">", "›");
                })
                .toList();
    }

    /** Run text must not open links or mention everyone in Markdown chats. */
    private static String escape(String s) {
        return s.replace("[", "［")
                .replace("]", "］")
                .replace("<", "‹")
                .replace(">", "›")
                .replace("@", "＠");
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.strip().lines().findFirst().orElse("");
    }

    private static String clip(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static byte[] hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
