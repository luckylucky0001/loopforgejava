package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.PreviewSettings;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.executor.CredentialProvider;
import io.github.loopforge4j.core.runtime.executor.ExecutorRegistry;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.store.InMemoryCatalogStore;
import io.github.loopforge4j.core.store.InMemoryRunStore;
import io.github.loopforge4j.core.store.PreviewStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewNodeTest {

    @TempDir
    Path root;

    final InMemoryCatalogStore catalog = new InMemoryCatalogStore();
    final Map<String, Map<String, String>> saved = new ConcurrentHashMap<>();
    final Map<String, PreviewSettings> settings = new ConcurrentHashMap<>();

    RunEngine engine(PreviewStore store) {
        return RunEngine.builder()
                .runStore(new InMemoryRunStore())
                .catalogStore(catalog)
                .executors(ExecutorRegistry.withBuiltIns(CredentialProvider.NONE))
                .sandbox(new LocalSandboxProvider(new LocalSandboxOptions(
                        root, LocalSandboxOptions.DEFAULT_PASSTHROUGH, true, Duration.ofMinutes(2))))
                .previews(store)
                .settings(new EngineSettings("test", "mock", Duration.ofMinutes(2), Duration.ofMillis(25), false))
                .build();
    }

    final PreviewStore recording = (runId, nodeId, s, files) -> {
        settings.put(runId, s);
        saved.put(
                runId,
                files.stream()
                        .collect(Collectors.toMap(
                                PreviewStore.File::path, f -> new String(f.content(), StandardCharsets.UTF_8))));
        return true;
    };

    static WorkflowDefinition workflow(String id, String script, PreviewSettings preview) {
        return WorkflowDefinition.builder(id)
                .node(WorkflowNode.builder("site", NodeType.PREVIEW)
                        .script(script)
                        .preview(preview)
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
    void theBuiltSiteIsArchivedWithoutLinksOrGitData() {
        catalog.saveWorkflow(workflow(
                "wf",
                "mkdir -p out/assets out/.git && echo '<h1>hi</h1>' > out/index.html && echo 'x()' > out/assets/app.js"
                        + " && echo secret > out/.git/config && ln -s /etc/hostname out/leak && echo built",
                new PreviewSettings("out", null, true)));
        try (RunEngine engine = engine(recording)) {
            Run run = settle(engine, "wf");
            assertThat(run.status()).as(() -> run.requireNode("site").error()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(saved.get(run.id()))
                    .containsOnlyKeys("index.html", "assets/app.js")
                    .containsEntry("index.html", "<h1>hi</h1>\n");
            assertThat(settings.get(run.id())).isEqualTo(new PreviewSettings("out", "index.html", true));
            assertThat(run.requireNode("site").output()).contains("built", "预览已归档：2 个文件", "入口 index.html");
        }
    }

    @Test
    void aSiteBuiltEarlierNeedsNoCommandAndAMissingEntryFails() {
        catalog.saveWorkflow(WorkflowDefinition.builder("two")
                .node(WorkflowNode.builder("build", NodeType.SCRIPT)
                        .script("mkdir -p dist && echo ok > dist/index.html")
                        .build())
                .node(WorkflowNode.builder("site", NodeType.PREVIEW).build())
                .edge("build", "site")
                .build());
        catalog.saveWorkflow(workflow("noentry", "mkdir -p dist && echo x > dist/other.html", null));
        catalog.saveWorkflow(workflow("nodir", "true", new PreviewSettings("build", null, false)));
        try (RunEngine engine = engine(recording)) {
            Run two = settle(engine, "two");
            assertThat(two.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(saved.get(two.id())).containsOnlyKeys("index.html");

            Run noEntry = settle(engine, "noentry");
            assertThat(noEntry.status()).isEqualTo(RunStatus.FAILED);
            assertThat(noEntry.requireNode("site").error()).contains("preview entry 'index.html' is missing");
            Run noDir = settle(engine, "nodir");
            assertThat(noDir.requireNode("site").error()).contains("preview directory 'build' was not built");
            assertThat(saved).doesNotContainKeys(noEntry.id(), noDir.id());
        }
    }

    @Test
    void theSiteDirectoryMayNotLeaveTheWorkspaceAndLimitsApply() {
        // a symlinked site directory pointing outside is refused
        catalog.saveWorkflow(workflow("link", "ln -s /etc dist", null));
        catalog.saveWorkflow(workflow(
                "big", "mkdir -p dist && echo x > dist/index.html && head -c 11000000 /dev/zero > dist/big.bin", null));
        try (RunEngine engine = engine(recording)) {
            Run link = settle(engine, "link");
            assertThat(link.status()).isEqualTo(RunStatus.FAILED);
            assertThat(link.requireNode("site").error()).contains("was not built");
            Run big = settle(engine, "big");
            assertThat(big.status()).isEqualTo(RunStatus.FAILED);
            assertThat(big.requireNode("site").error()).contains("big.bin is larger than 10 MB");
        }
        assertThat(WorkflowValidator.problems(workflow("bad", null, new PreviewSettings("../outside", null, false))))
                .anySatisfy(p -> assertThat(p).contains("preview path must be a relative path"));
    }

    @Test
    void withoutAStoreThePreviewIsBuiltButNotKept() {
        catalog.saveWorkflow(workflow("wf", "mkdir -p dist && echo ok > dist/index.html", null));
        try (RunEngine engine = engine(PreviewStore.NONE)) {
            Run run = settle(engine, "wf");
            assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
            assertThat(run.requireNode("site").output()).contains("此执行环境不保存预览");
            assertThat(engine.events(run.id(), 0, 0))
                    .anySatisfy(e -> assertThat(e.type()).isEqualTo("preview.archived"));
        }
    }
}
