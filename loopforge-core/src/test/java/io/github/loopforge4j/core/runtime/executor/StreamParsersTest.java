package io.github.loopforge4j.core.runtime.executor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.runtime.Telemetry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamParsersTest {

    @Test
    void anthropicStreamJson() {
        List<Telemetry> events = new ArrayList<>();
        AnthropicStreamParser parser = new AnthropicStreamParser("claude-code", events::add);
        parser.accept("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s-1\",\"model\":\"claude-x\"}");
        parser.accept("not json at all");
        parser.accept(
                "{\"type\":\"assistant\",\"session_id\":\"s-1\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"working\"},"
                        + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls\"}}]}}");
        parser.accept(
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"t1\",\"is_error\":true}]}}");
        parser.accept(
                "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"s-1\",\"result\":\"done\",\"is_error\":false,"
                        + "\"total_cost_usd\":0.12,\"usage\":{\"input_tokens\":100,\"output_tokens\":20,\"cache_read_input_tokens\":5}}");

        AgentResult result = parser.result(0);
        assertThat(result.output()).isEqualTo("done");
        assertThat(result.sessionId()).isEqualTo("s-1");
        assertThat(result.model()).isEqualTo("claude-x");
        assertThat(result.terminal()).isTrue();
        assertThat(result.failed()).isFalse();
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.usage().totalTokens()).isEqualTo(120);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(5);
        assertThat(result.usage().costStatus()).isEqualTo("reported");
        assertThat(events)
                .extracting(Telemetry::name)
                .containsExactly(
                        "agent.initialized",
                        "agent.output",
                        "tool.call",
                        "tool.call",
                        "usage.final",
                        "agent.completed");
        assertThat(events.get(3).level()).isEqualTo("error");
    }

    @Test
    void anthropicMaxTurnsAndMissingResult() {
        AnthropicStreamParser parser = new AnthropicStreamParser("claude-code", t -> {});
        parser.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}}");
        assertThat(parser.result(0).terminal()).isFalse();
        assertThat(parser.result(0).output()).isEqualTo("partial");
        parser.accept("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true}");
        assertThat(parser.result(1).maxTurns()).isTrue();
    }

    @Test
    void codexJsonl() {
        List<Telemetry> events = new ArrayList<>();
        CodexJsonParser parser = new CodexJsonParser(events::add);
        parser.accept("{\"type\":\"thread.started\",\"thread_id\":\"th-9\"}");
        parser.accept(
                "{\"type\":\"item.started\",\"item\":{\"id\":\"i1\",\"type\":\"command_execution\",\"command\":\"npm test\"}}");
        parser.accept(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"i1\",\"type\":\"command_execution\",\"command\":\"npm test\",\"exit_code\":0}}");
        parser.accept(
                "{\"type\":\"item.completed\",\"item\":{\"id\":\"i2\",\"type\":\"agent_message\",\"text\":\"all green\"}}");
        parser.accept(
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":50,\"cached_input_tokens\":10,\"output_tokens\":7}}");

        AgentResult result = parser.result(0);
        assertThat(result.sessionId()).isEqualTo("th-9");
        assertThat(result.output()).isEqualTo("all green");
        assertThat(result.terminal()).isTrue();
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.usage().inputTokens()).isEqualTo(50);
        assertThat(result.usage().cacheReadTokens()).isEqualTo(10);
        assertThat(events).extracting(Telemetry::category).contains("lifecycle", "tool", "output", "usage");
    }

    @Test
    void codexTurnFailure() {
        CodexJsonParser parser = new CodexJsonParser(t -> {});
        parser.accept("{\"type\":\"turn.failed\",\"error\":{\"message\":\"rate limited\"}}");
        AgentResult result = parser.result(1);
        assertThat(result.failed()).isTrue();
        assertThat(result.output()).isEqualTo("rate limited");
    }
}
