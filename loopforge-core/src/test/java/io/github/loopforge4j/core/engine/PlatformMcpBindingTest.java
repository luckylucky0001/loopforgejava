package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ExecutorDefinition;
import io.github.loopforge4j.core.domain.McpServerDefinition;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.RuleDefinition;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunEvent;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.json.Json;
import io.github.loopforge4j.core.mcp.PlatformMcp;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.EventSink;
import io.github.loopforge4j.core.runtime.executor.AgentExecutor;
import io.github.loopforge4j.core.runtime.executor.AgentInvocation;
import io.github.loopforge4j.core.runtime.executor.AgentResult;
import io.github.loopforge4j.core.runtime.executor.MockAgentExecutor;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Roles switch on platform-served MCP servers; the engine binds them per invocation. */
class PlatformMcpBindingTest {

    private static final String TOKEN = "lfr1.secret-token-value";

    @TempDir
    Path root;

    /** Records the compiled role of each call, then behaves like the mock executor. */
    static final class Recorder implements AgentExecutor {
        final List<AgentInvocation> calls = new CopyOnWriteArrayList<>();
        private final MockAgentExecutor mock = new MockAgentExecutor(Duration.ofMillis(1));

        @Override
        public String kind() {
            return "recorder";
        }

        @Override
        public boolean materializes() {
            return true;
        }

        @Override
        public AgentResult execute(AgentInvocation invocation, Sandbox sandbox, EventSink sink, Cancellation c)
                throws java.io.IOException, io.github.loopforge4j.core.runtime.executor.ExecutorException,
                        io.github.loopforge4j.core.runtime.CancelledException {
            calls.add(invocation);
            return mock.execute(invocation, sandbox, sink, c);
        }
    }

    @Test
    void togglesBindPlatformServersRulesAndTheKnowledgeArtifact() throws Exception {
        List<PlatformMcp.Invocation> seen = new CopyOnWriteArrayList<>();
        PlatformMcp platform = invocation -> {
            seen.add(invocation);
            return new PlatformMcp.Binding(
                    List.of(new McpServerDefinition(
                            PlatformMcp.PLATFORM_ID,
                            "平台",
                            null,
                            McpServerDefinition.HTTP,
                            null,
                            List.of(),
                            "http://127.0.0.1:1/mcp",
                            Map.of(),
                            Map.of("Authorization", "Bearer " + TOKEN),
                            null,
                            60,
                            null)),
                    List.of(new RuleDefinition("kb-rule", "知识库", null, "先检索知识库再动手", 50, null)),
                    List.of("未注入 " + PlatformMcp.KNOWLEDGE_ID + "：测试"));
        };
        Recorder recorder = new Recorder();
        try (EngineFixture f = new EngineFixture(root.resolve("ws"), platform)) {
            f.engine.executors().register(recorder);
            f.engine
                    .executors()
                    .define(new ExecutorDefinition("rec", "Recorder", "recorder", null, List.of(), null, null, null));
            f.agent(AgentDefinition.builder("dev")
                            .executor("rec")
                            .prompt("实现功能")
                            .platformMcp(true)
                            .knowledgeBase(true)
                            .build())
                    .workflow(WorkflowDefinition.builder("wf")
                            .node(WorkflowNode.builder("implement", NodeType.AGENT)
                                    .agent("dev")
                                    .build())
                            .build());
            Run run = f.start("wf");
            f.awaitStatus(run.id(), RunStatus.SUCCEEDED);

            assertThat(seen).singleElement().satisfies(inv -> {
                assertThat(inv.runId()).isEqualTo(run.id());
                assertThat(inv.nodeId()).isEqualTo("implement");
                assertThat(inv.agentId()).isEqualTo("dev");
                assertThat(inv.sandboxProfileId()).isEqualTo("local");
                assertThat(inv.platform()).isTrue();
                assertThat(inv.knowledge()).isTrue();
            });
            AgentInvocation call = recorder.calls.getFirst();
            assertThat(call.compiled().mcpServers())
                    .extracting(McpServerDefinition::id)
                    .contains(PlatformMcp.PLATFORM_ID);
            assertThat(call.compiled().systemInstructions()).contains("先检索知识库再动手");
            // distilled knowledge the role leaves behind is kept as an (optional) artifact
            assertThat(call.artifacts()).anySatisfy(c -> {
                assertThat(c.path()).isEqualTo("knowledge.md");
                assertThat(c.required()).isFalse();
            });
            assertThat(f.engine.runs().artifacts(run.id()))
                    .extracting(Artifact::path)
                    .contains("knowledge.md");

            List<RunEvent> events = f.engine.events(run.id(), 0, 1000);
            assertThat(events)
                    .filteredOn(e -> e.type().equals("mcp.platform.skipped"))
                    .singleElement()
                    .satisfies(e -> assertThat(e.message()).contains(PlatformMcp.KNOWLEDGE_ID));
            // the credential reaches the CLI configuration only, never the run record
            assertThat(Json.write(events)).doesNotContain(TOKEN);
            assertThat(Json.write(f.engine.require(run.id()))).doesNotContain(TOKEN);
        }
    }

    @Test
    void aFailingProviderOnlyWarns() throws Exception {
        PlatformMcp broken = invocation -> {
            throw new IllegalStateException("database down " + TOKEN);
        };
        try (EngineFixture f = new EngineFixture(root.resolve("ws"), broken)) {
            f.agent(AgentDefinition.builder("dev")
                            .executor("mock")
                            .platformMcp(true)
                            .build())
                    .workflow(WorkflowDefinition.builder("wf")
                            .node(WorkflowNode.builder("a", NodeType.AGENT)
                                    .agent("dev")
                                    .build())
                            .build());
            Run run = f.start("wf");
            f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            List<RunEvent> events = f.engine.events(run.id(), 0, 1000);
            assertThat(events).anySatisfy(e -> assertThat(e.type()).isEqualTo("mcp.platform.skipped"));
            assertThat(Json.write(events)).doesNotContain(TOKEN);
        }
    }

    @Test
    void withoutAProviderTheRunSaysWhyNothingWasBound() throws Exception {
        try (EngineFixture f = new EngineFixture(root.resolve("ws"))) {
            f.agent(AgentDefinition.builder("dev")
                            .executor("mock")
                            .platformMcp(true)
                            .knowledgeBase(true)
                            .build())
                    .workflow(WorkflowDefinition.builder("wf")
                            .node(WorkflowNode.builder("a", NodeType.AGENT)
                                    .agent("dev")
                                    .build())
                            .build());
            Run run = f.start("wf");
            f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            assertThat(f.engine.events(run.id(), 0, 1000))
                    .filteredOn(e -> e.type().equals("mcp.platform.skipped"))
                    .hasSize(2);
        }
    }

    @Test
    void reservedIdsCannotBeBoundOrCatalogued() {
        AgentDefinition agent = AgentDefinition.builder("dev")
                .mcpServers(List.of(PlatformMcp.PLATFORM_ID, PlatformMcp.KNOWLEDGE_ID))
                .build();
        assertThat(WorkflowValidator.agentProblems(agent))
                .anySatisfy(p -> assertThat(p).contains(PlatformMcp.PLATFORM_ID).contains("reserved"))
                .anySatisfy(p -> assertThat(p).contains(PlatformMcp.KNOWLEDGE_ID));
        assertThat(WorkflowValidator.assetProblems(
                        McpServerDefinition.stdio(PlatformMcp.KNOWLEDGE_ID, "cat", List.of(), Map.of())))
                .anySatisfy(p -> assertThat(p).contains("reserved"));
    }
}
