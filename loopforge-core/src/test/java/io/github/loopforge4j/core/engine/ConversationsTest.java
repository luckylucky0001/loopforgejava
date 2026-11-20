package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.TokenUsage;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.executor.AgentExecutor;
import io.github.loopforge4j.core.runtime.executor.AgentInvocation;
import io.github.loopforge4j.core.runtime.executor.AgentResult;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationsTest {

    @TempDir
    Path root;

    /** A CLI that keeps notes in its workspace and numbers its sessions. */
    static final class Notebook implements AgentExecutor {
        final List<AgentInvocation> calls = new CopyOnWriteArrayList<>();

        @Override
        public String kind() {
            return "notebook";
        }

        @Override
        public boolean materializes() {
            return true;
        }

        @Override
        public AgentResult execute(AgentInvocation invocation, Sandbox sandbox, EventSink sink, Cancellation c)
                throws java.io.IOException {
            calls.add(invocation);
            Path notes = sandbox.workspace().resolve("notes.txt");
            String before = Files.exists(notes) ? Files.readString(notes) : "";
            Files.writeString(notes, before + invocation.prompt() + "\n");
            return new AgentResult(
                    "seen: " + before.lines().count(),
                    "session-" + calls.size(),
                    "claude-sonnet-4-5",
                    new TokenUsage(1000, 100, 0, 0, 0, 0, 0, null),
                    2,
                    true,
                    false,
                    false,
                    0);
        }
    }

    @Test
    void turnsShareAWorkspaceAndContinueTheSession() throws Exception {
        Notebook notebook = new Notebook();
        try (EngineFixture f = new EngineFixture(root.resolve("ws"))) {
            f.engine.executors().register(notebook);
            f.engine
                    .executors()
                    .define(new ExecutorDefinition("nb", "Notebook", "notebook", null, List.of(), null, null, null));
            AgentDefinition bot = AgentDefinition.builder("main-bot")
                    .executor("nb")
                    .prompt("你是平台助手")
                    .build();
            McpServerDefinition platform = new McpServerDefinition(
                    "loopforge-platform",
                    "平台",
                    null,
                    McpServerDefinition.HTTP,
                    null,
                    List.of(),
                    "http://127.0.0.1:1/mcp",
                    Map.of(),
                    Map.of("Authorization", "Bearer t"),
                    null,
                    60,
                    null);
            Conversations.Turn first = new Conversations.Turn(
                    "c1",
                    "local",
                    bot,
                    "第一句",
                    null,
                    Duration.ofMinutes(1),
                    List.of(platform),
                    List.of(new RuleDefinition("r", "r", null, "先查平台", 1, null)),
                    Map.of());
            Conversations.Reply one = f.engine.conversations().turn(first, telemetry -> {}, Cancellation.create());
            assertThat(one.text()).isEqualTo("seen: 0");
            assertThat(one.sessionId()).isEqualTo("session-1");
            assertThat(one.usage().costUsd()).isPositive(); // priced from the public table

            Conversations.Reply two = f.engine
                    .conversations()
                    .turn(
                            new Conversations.Turn(
                                    "c1",
                                    "local",
                                    bot,
                                    "第二句",
                                    one.sessionId(),
                                    Duration.ofMinutes(1),
                                    List.of(),
                                    List.of(),
                                    Map.of()),
                            telemetry -> {},
                            Cancellation.create());
            assertThat(two.text()).isEqualTo("seen: 1");
            assertThat(notebook.calls.get(1).resumeSessionId()).isEqualTo("session-1");
            assertThat(notebook.calls.getFirst().compiled().mcpServers())
                    .extracting(McpServerDefinition::id)
                    .contains("loopforge-platform");
            assertThat(notebook.calls.getFirst().compiled().systemInstructions())
                    .contains("先查平台");
            assertThat(notebook.calls.getFirst().gateResultPath()).isNull();
            Path workspace = root.resolve("ws").resolve(Conversations.workspaceId("c1"));
            assertThat(workspace.resolve("notes.txt")).hasContent("第一句\n第二句\n");

            // another conversation has its own workspace
            f.engine
                    .conversations()
                    .turn(
                            new Conversations.Turn(
                                    "c2",
                                    "local",
                                    bot,
                                    "别的对话",
                                    null,
                                    Duration.ofMinutes(1),
                                    List.of(),
                                    List.of(),
                                    Map.of()),
                            telemetry -> {},
                            Cancellation.create());
            assertThat(root.resolve("ws")
                            .resolve(Conversations.workspaceId("c2"))
                            .resolve("notes.txt"))
                    .hasContent("别的对话\n");

            f.engine.conversations().release("c1", "local");
            assertThat(workspace).doesNotExist();
            assertThatThrownBy(() -> f.engine
                            .conversations()
                            .turn(
                                    new Conversations.Turn(
                                            "c3",
                                            "docker",
                                            bot,
                                            "x",
                                            null,
                                            Duration.ofMinutes(1),
                                            List.of(),
                                            List.of(),
                                            Map.of()),
                                    telemetry -> {},
                                    Cancellation.create()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("docker");
        }
    }
}
