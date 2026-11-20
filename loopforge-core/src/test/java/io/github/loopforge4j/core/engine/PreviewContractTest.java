package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Position;
import io.github.loopforge4j.core.domain.PreviewContract;
import io.github.loopforge4j.core.domain.PreviewSettings;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowEdge;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.preview.FrontendDetector;
import io.github.loopforge4j.core.preview.PreviewArchive;
import io.github.loopforge4j.core.preview.PreviewBuild;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.executor.MockAgentExecutor;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import io.github.loopforge4j.core.store.PreviewStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SOP's frontend preview contract: an attached build step, skipped without a frontend. */
class PreviewContractTest {

    @TempDir
    Path root;

    final InMemoryCatalogStore catalog = new InMemoryCatalogStore();

    /** Records what happened to each run's preview. */
    static final class Recording implements PreviewStore {
        final Map<String, String> status = new ConcurrentHashMap<>();
        final Map<String, List<String>> files = new ConcurrentHashMap<>();
        final Map<String, String> reasons = new ConcurrentHashMap<>();
        final AtomicInteger failSaves = new AtomicInteger();

        @Override
        public boolean save(
                String runId, String nodeId, PreviewSettings settings, PreviewArchive.Site site, Duration retention)
                throws IOException {
            if (failSaves.getAndDecrement() > 0) {
                throw new IOException("disk full");
            }
            files.put(runId, site.files());
            status.put(runId, READY);
            reasons.put(runId, (nodeId == null ? "run" : nodeId) + " " + retention.toDays() + "d");
            return true;
        }

        @Override
        public void skip(String runId, String nodeId, PreviewSettings settings, String reason) {
            status.put(runId, SKIPPED);
            reasons.put(runId, reason);
        }

        @Override
        public void fail(String runId, String nodeId, PreviewSettings settings, String reason) {
            status.put(runId, FAILED);
            reasons.put(runId, reason);
        }

        @Override
        public Optional<String> status(String runId) {
            return Optional.ofNullable(status.get(runId));
        }
    }

    RunEngine engine(PreviewStore store) {
        return RunEngine.builder()
                .runStore(new InMemoryRunStore())
                .catalogStore(catalog)
                .executors(ExecutorRegistry.withBuiltIns(CredentialProvider.NONE)
                        .register(new MockAgentExecutor(Duration.ofMillis(5))))
                .sandbox(new LocalSandboxProvider(new LocalSandboxOptions(
                        root, LocalSandboxOptions.DEFAULT_PASSTHROUGH, true, Duration.ofMinutes(2))))
                .previews(store)
                .settings(new EngineSettings("test", "mock", Duration.ofMinutes(2), Duration.ofMillis(25), false))
                .build();
    }

    static PreviewContract contract() {
        return new PreviewContract(true, null, "mock", null, null, null, true, false, false, 3 * 24 * 3600);
    }

    static WorkflowDefinition workflow(String id, String setup) {
        return WorkflowDefinition.builder(id)
                .preview(contract())
                .node(WorkflowNode.builder("setup", NodeType.SCRIPT)
                        .script(setup)
                        .build())
                .build();
    }

    static Run settle(RunEngine engine, String workflow) {
        Run run = engine.start(workflow, "做个页面", ExecutionContext.triggeredBy("tester"));
        await().atMost(Duration.ofSeconds(20))
                .until(() -> engine.require(run.id()).status().terminal() && !engine.scheduling(run.id()));
        return engine.require(run.id());
    }

    @Test
    void theBuildStepIsAttachedAfterEverySinkAndRemovedWithTheContract() {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .preview(contract())
                .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                        .script("true")
                        .position(new Position(100, 40))
                        .build())
                .node(WorkflowNode.builder("b", NodeType.SCRIPT)
                        .script("true")
                        .position(new Position(380, 200))
                        .build())
                .node(WorkflowNode.builder("c", NodeType.SCRIPT).script("true").build())
                .edge("a", "b")
                .build();
        WorkflowDefinition attached = PreviewBuild.attach(wf);
        WorkflowNode build = attached.requireNode(PreviewBuild.NODE_ID);
        assertThat(build.type()).isEqualTo(NodeType.AGENT);
        assertThat(build.agentId()).isEqualTo(PreviewBuild.AGENT_ID);
        assertThat(build.executorId()).isEqualTo("mock");
        assertThat(build.position()).isEqualTo(new Position(660, 200));
        assertThat(build.artifactOutputs())
                .singleElement()
                .satisfies(a -> assertThat(a.path()).isEqualTo("dist/index.html"));
        assertThat(attached.edges())
                .filteredOn(e -> e.target().equals(PreviewBuild.NODE_ID))
                .extracting(WorkflowEdge::source)
                .containsExactly("b", "c");
        // attaching twice changes nothing; a placed step keeps its edges
        assertThat(PreviewBuild.attach(attached)).isEqualTo(attached);
        WorkflowDefinition off = new WorkflowDefinition(
                attached.id(),
                attached.name(),
                attached.version(),
                attached.revision(),
                attached.status(),
                null,
                null,
                List.of(),
                null,
                null,
                attached.nodes(),
                attached.edges(),
                null,
                null,
                null);
        assertThat(PreviewBuild.attach(off).nodes())
                .extracting(WorkflowNode::id)
                .containsExactly("a", "b", "c");
        assertThat(PreviewBuild.attach(off).edges()).hasSize(1);
        assertThat(PreviewBuild.agent(contract()).prompt()).contains("dist/index.html", "base: './'");
        assertThat(WorkflowValidator.problems(WorkflowDefinition.builder("bad")
                        .preview(new PreviewContract(true, "service", null, null, "../x", null, false, false, false, 0))
                        .node(WorkflowNode.builder("a", NodeType.SCRIPT)
                                .script("true")
                                .build())
                        .build()))
                .contains("frontend preview currently supports static sites only")
                .anySatisfy(p -> assertThat(p).contains("sourcePath '../x' is invalid"));
    }

    @Test
    void aFrontendIsBuiltAndArchivedRightAfterTheBuildStep() {
        catalog.saveWorkflow(workflow("fe", "echo '{\"devDependencies\": {\"vite\": \"5\"}}' > package.json"));
        Recording store = new Recording();
        try (RunEngine engine = engine(store)) {
            Run run = settle(engine, "fe");
            assertThat(run.status())
                    .as(() ->
                            String.valueOf(run.requireNode(PreviewBuild.NODE_ID).error()))
                    .isEqualTo(RunStatus.SUCCEEDED);
            assertThat(run.workflow().node(PreviewBuild.NODE_ID)).isPresent();
            assertThat(store.status).containsEntry(run.id(), PreviewStore.READY);
            assertThat(store.files.get(run.id())).containsExactly("index.html");
            assertThat(store.reasons).containsEntry(run.id(), PreviewBuild.NODE_ID + " 3d");
            assertThat(run.requireNode(PreviewBuild.NODE_ID).output()).contains("预览已归档：1 个文件");
        }
    }

    @Test
    void aWorkspaceWithoutAFrontendSkipsThePreview() {
        catalog.saveWorkflow(workflow("be", "mkdir -p src && echo 'package main' > src/main.go"));
        Recording store = new Recording();
        try (RunEngine engine = engine(store)) {
            Run run = settle(engine, "be");
            assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(store.status).containsEntry(run.id(), PreviewStore.SKIPPED);
            assertThat(store.reasons.get(run.id())).contains("工作区没有前端工程");
            assertThat(run.requireNode(PreviewBuild.NODE_ID).output()).contains("已跳过");
            assertThat(Files.exists(Path.of(run.workspaceRoot()).resolve("dist/index.html")))
                    .isFalse();
        }
    }

    @Test
    void aFailedArchiveIsRetriedFromTheFinalWorkspaceWhenTheRunSucceeds() {
        catalog.saveWorkflow(workflow("retry", "echo '<html></html>' > index.html"));
        Recording store = new Recording();
        store.failSaves.set(1);
        try (RunEngine engine = engine(store)) {
            Run run = settle(engine, "retry");
            assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(run.requireNode(PreviewBuild.NODE_ID).output()).contains("预览归档失败", "disk full");
            await().atMost(Duration.ofSeconds(10)).until(() -> PreviewStore.READY.equals(store.status.get(run.id())));
            assertThat(store.reasons).containsEntry(run.id(), "run 3d");
        }
    }

    @Test
    void detectionFollowsUpstreamRules() throws IOException {
        Path ws = Files.createDirectories(root.resolve("detect"));
        assertThat(FrontendDetector.detect(ws).frontend()).isFalse();
        Files.createDirectories(ws.resolve("node_modules/x"));
        Files.writeString(ws.resolve("node_modules/x/index.html"), "x");
        assertThat(FrontendDetector.detect(ws).frontend()).isFalse();
        Files.createDirectories(ws.resolve("web"));
        Files.writeString(ws.resolve("web/package.json"), "{\"scripts\": {\"build\": \"ng build --prod\"}}");
        assertThat(FrontendDetector.detect(ws))
                .isEqualTo(new FrontendDetector.Result(true, "web/package.json (scripts:ng build)"));
        Path deep = Files.createDirectories(root.resolve("deep/a/b/c/d/e/f/g"));
        Files.writeString(deep.resolve("vite.config.ts"), "");
        assertThat(FrontendDetector.detect(root.resolve("deep")).frontend()).isFalse();
    }
}
