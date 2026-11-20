package io.github.loopforge4j.server.work;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.json.Json;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class BotMessagesTest {

    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    static Run run() {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .name("交付流程")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT).script("true").build())
                .build();
        return Run.create("run-1", wf, "", new ExecutionContext("p1", "alice", null, null, null), NOW);
    }

    static NotificationChannel channel(String kind, String url, String secret) {
        return new NotificationChannel(
                "c", "p1", "n", true, kind, url, secret, List.of(), null, null, null, null, null);
    }

    @Test
    void wecomGetsMarkdownWithARunLink() throws Exception {
        BotMessages.Outgoing out = BotMessages.render(
                channel("wecom", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=k", null),
                "run.failed",
                run(),
                "https://lf.example.com/",
                NOW);
        JsonNode body = Json.mapper().readTree(out.body());
        assertThat(body.path("msgtype").asString()).isEqualTo("markdown");
        String content = body.path("markdown").path("content").asString();
        assertThat(content).contains("运行失败", "交付流程（wf）", "项目：p1", "[查看运行](https://lf.example.com/#/runs/run-1)");
        assertThat(out.uri().toString()).endsWith("key=k");
    }

    @Test
    void dingtalkAndFeishuSignTheirMessages() throws Exception {
        BotMessages.Outgoing ding = BotMessages.render(
                channel("dingtalk", "https://oapi.dingtalk.com/robot/send?access_token=t", "SEC123"),
                "run.succeeded",
                run(),
                null,
                NOW);
        String query = ding.uri().getQuery();
        assertThat(query).contains("timestamp=" + NOW.toEpochMilli());
        String sign = URLDecoder.decode(query.substring(query.indexOf("sign=") + 5), StandardCharsets.UTF_8);
        assertThat(sign).isEqualTo(hmac("SEC123", NOW.toEpochMilli() + "\nSEC123"));
        assertThat(Json.mapper()
                        .readTree(ding.body())
                        .path("markdown")
                        .path("title")
                        .asString())
                .contains("运行成功");

        BotMessages.Outgoing feishu = BotMessages.render(
                channel("feishu", "https://open.feishu.cn/open-apis/bot/v2/hook/x", "FS"),
                "run.waiting",
                run(),
                null,
                NOW);
        JsonNode body = Json.mapper().readTree(feishu.body());
        assertThat(body.path("timestamp").asString()).isEqualTo(String.valueOf(NOW.getEpochSecond()));
        assertThat(body.path("sign").asString()).isEqualTo(hmac(NOW.getEpochSecond() + "\nFS", ""));
        assertThat(body.path("content").path("text").asString()).contains("等待人工处理", "运行：run-1");

        BotMessages.Outgoing slack = BotMessages.render(
                channel("slack", "https://hooks.slack.com/x", null), "run.cancelled", run(), "https://lf", NOW);
        assertThat(Json.mapper().readTree(slack.body()).path("text").asString())
                .contains("<https://lf/#/runs/run-1|查看运行>");
    }

    @Test
    void runTextCannotMentionEveryoneOnFeishu() throws Exception {
        Run run = run();
        run.requireNode("a").error("script exited with code 1: <at user_id=\"all\">所有人</at>");
        run.requireNode("a").finish(io.github.loopforge4j.core.domain.NodeStatus.FAILED, NOW);
        String text = Json.mapper()
                .readTree(BotMessages.render(
                                channel("feishu", "https://open.feishu.cn/x", null), "run.failed", run, null, NOW)
                        .body())
                .path("content")
                .path("text")
                .asString();
        assertThat(text).contains("所有人").doesNotContain("<at").doesNotContain("</at>");
    }

    @Test
    void platformErrorCodesCountAsFailures() {
        assertThat(BotMessages.rejection("wecom", 200, "{\"errcode\":0,\"errmsg\":\"ok\"}"))
                .isNull();
        assertThat(BotMessages.rejection("wecom", 200, "{\"errcode\":93000,\"errmsg\":\"invalid key\"}"))
                .isEqualTo("rejected 93000");
        assertThat(BotMessages.rejection("feishu", 200, "{\"code\":19021,\"msg\":\"sign match fail\"}"))
                .isEqualTo("rejected 19021");
        assertThat(BotMessages.rejection("feishu", 200, "{\"StatusCode\":0}")).isNull();
        assertThat(BotMessages.rejection("dingtalk", 500, "")).isEqualTo("http 500");
        assertThat(BotMessages.rejection("slack", 200, "ok")).isNull();
    }

    private static String hmac(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    @org.junit.jupiter.api.Test
    void aReportCannotForgeLinksOrMentions() {
        io.github.loopforge4j.core.domain.Run run = io.github.loopforge4j.core.domain.Run.create(
                "r1",
                io.github.loopforge4j.core.domain.WorkflowDefinition.builder("wf")
                        .build(),
                "x",
                io.github.loopforge4j.core.domain.ExecutionContext.triggeredBy("t"),
                java.time.Instant.EPOCH);
        RunReports.Report report = new RunReports.Report(
                "generated", "标题 @all", "\u0001点我\u0001https://evil.example", "<@all>\u0000v\n<!channel|x>", null);
        for (String kind : List.of("wecom", "dingtalk", "slack", "feishu")) {
            String body = BotMessages.render(
                            channel(kind, "https://hooks.example/x", null),
                            "run.succeeded",
                            run,
                            null,
                            java.time.Instant.EPOCH,
                            report)
                    .body();
            org.assertj.core.api.Assertions.assertThat(body)
                    .as(kind)
                    .doesNotContain("evil.example)", "<@all>", "<!channel", "](https://evil", "<https://evil");
            if (!kind.equals("slack") && !kind.equals("feishu")) {
                org.assertj.core.api.Assertions.assertThat(body).as(kind).doesNotContain("@all");
            }
        }
    }
}
