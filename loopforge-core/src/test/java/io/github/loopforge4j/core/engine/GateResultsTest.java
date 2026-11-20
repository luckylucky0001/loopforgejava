package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.FailurePolicy;
import io.github.loopforge4j.core.domain.GateDecision;
import io.github.loopforge4j.core.domain.GateResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateResultsTest {

    @TempDir
    Path workspace;

    @Test
    void readsAndNormalizesDecision() throws IOException {
        String path = GateResults.path("review/1", 2);
        assertThat(path).isEqualTo(".loopforge/gate-results/review_1/attempt-2.json");
        assertThat(GateResults.read(workspace, path)).isEmpty();
        write(path, "{\"decision\":\" REWORK \",\"reason\":\" 缺少测试 \",\"findings\":[{\"title\":\"no tests\"}]}");
        GateResult result = GateResults.read(workspace, path).orElseThrow();
        assertThat(result.decision()).isEqualTo(GateDecision.REWORK);
        assertThat(result.reason()).isEqualTo("缺少测试");
        assertThat(result.findings()).hasSize(1);
    }

    @Test
    void rejectsInvalidPayloads() throws IOException {
        String path = GateResults.path("n", 1);
        write(path, "{\"decision\":\"maybe\",\"reason\":\"x\"}");
        assertThatThrownBy(() -> GateResults.read(workspace, path)).isInstanceOf(IllegalArgumentException.class);
        write(path, "{\"decision\":\"human\",\"reason\":\"x\"}");
        assertThatThrownBy(() -> GateResults.read(workspace, path)).hasMessageContaining("actionRequired");
        write(path, "{\"decision\":\"pass\"}");
        assertThatThrownBy(() -> GateResults.read(workspace, path)).hasMessageContaining("reason");
        write(path, "not json");
        assertThatThrownBy(() -> GateResults.read(workspace, path)).hasMessageContaining("invalid");
    }

    @Test
    void mapsDecisionsOntoFailurePipeline() {
        assertThat(GateResults.toFailure(GateResult.pass("ok"), null)).isEmpty();
        GateResult rework = new GateResult(GateDecision.REWORK, "bug", null, null, List.of());
        assertThat(GateResults.toFailure(rework, "implement"))
                .get()
                .extracting(NodeFailure::action)
                .isEqualTo(FailurePolicy.REJECT);
        assertThat(GateResults.toFailure(rework, null))
                .get()
                .extracting(NodeFailure::action)
                .isEqualTo(FailurePolicy.FAIL);
        GateResult human = new GateResult(GateDecision.HUMAN, "unsure", "check", null, List.of());
        assertThat(GateResults.toFailure(human, null))
                .get()
                .extracting(NodeFailure::action)
                .isEqualTo(FailurePolicy.HUMAN);
    }

    private void write(String relative, String content) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
