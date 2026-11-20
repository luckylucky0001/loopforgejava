package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactSupportTest {

    @TempDir
    Path workspace;

    /**
     * Sibling branches materialize the same upstream inputs concurrently into the shared workspace.
     * Regression: an in-place write failed with AccessDeniedException once another branch had made the
     * file read-only (only visible for non-root users, e.g. on CI).
     */
    @Test
    void concurrentMaterializationOfSharedInputsNeverFails() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("start", NodeType.SCRIPT)
                        .script("true")
                        .build())
                .node(WorkflowNode.builder("left", NodeType.SCRIPT)
                        .script("true")
                        .build())
                .node(WorkflowNode.builder("right", NodeType.SCRIPT)
                        .script("true")
                        .build())
                .edge("start", "left")
                .edge("start", "right")
                .build();
        Run run = Run.create("run-1", wf, "", ExecutionContext.EMPTY, Instant.now());
        List<Artifact> artifacts = List.of(
                ArtifactSupport.create(
                        "run-1", "start", 1, "result", "result.md", "text/markdown", "body", Instant.now()),
                ArtifactSupport.create(
                        "run-1", "start", 1, "plan", "plan.json", "application/json", "{}", Instant.now()));
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                WorkflowNode node = wf.requireNode(i % 2 == 0 ? "left" : "right");
                results.add(pool.submit(() -> ArtifactSupport.materializeInputs(workspace, run, node, artifacts)));
            }
            for (Future<String> result : results) {
                assertThat(result.get()).contains("start / result").contains("start / plan");
            }
        }
        Path inputs = workspace.resolve(".loopforge/inputs/start");
        assertThat(Files.readString(inputs.resolve("result.md"))).isEqualTo("body");
        assertThat(inputs.resolve("result.md").toFile().canWrite() && !isRoot()).isFalse();
        try (Stream<Path> files = Files.list(inputs)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("result.md", "plan.json");
        }
    }

    private static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }
}
