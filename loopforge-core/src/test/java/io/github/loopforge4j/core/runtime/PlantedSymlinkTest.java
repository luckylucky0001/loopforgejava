package io.github.loopforge4j.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.AgentDefinition;
import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.ExecutionContext;
import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.SkillDefinition;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.engine.GateResults;
import io.github.loopforge4j.core.runtime.materialize.AgentAssets;
import io.github.loopforge4j.core.runtime.materialize.AgentMaterializer;
import io.github.loopforge4j.core.runtime.materialize.CompiledAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent controls the workspace (also from inside a container, where the workspace is mounted at
 * the same path) and may replace control directories with symlinks to host paths. Engine-side file
 * operations must never follow them.
 */
class PlantedSymlinkTest {

    @TempDir
    Path root;

    Path workspace;
    Path victim;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createDirectories(root.resolve("ws"));
        victim = Files.createDirectories(root.resolve("host-data"));
        Files.writeString(victim.resolve("loopforge.mv.db"), "database");
        Files.createDirectories(victim.resolve("workspaces/other"));
        Files.writeString(victim.resolve("api-token"), "secret-token");
    }

    @Test
    void staleSkillCleanupDoesNotDeleteThroughALink() throws Exception {
        // a role without skills only cleans up stale skill directories: nothing is written first
        Files.createDirectories(workspace.resolve(".loopforge/agents/dev"));
        Files.createSymbolicLink(workspace.resolve(".loopforge/agents/dev/skills"), victim);
        assertThatThrownBy(() -> materialize(AgentDefinition.builder("dev").build(), AgentAssets.NONE))
                .hasMessageContaining("symlink");
        assertVictimIntact();
        // the same with hooks
        Files.delete(workspace.resolve(".loopforge/agents/dev/skills"));
        Files.createSymbolicLink(workspace.resolve(".loopforge/agents/dev/hooks"), victim);
        assertThatThrownBy(() -> materialize(AgentDefinition.builder("dev").build(), AgentAssets.NONE))
                .hasMessageContaining("symlink");
        assertVictimIntact();
        // and with a skill bound (written first)
        Files.delete(workspace.resolve(".loopforge/agents/dev/hooks"));
        Files.createSymbolicLink(workspace.resolve(".loopforge/agents/dev/skills"), victim);
        AgentAssets assets = new AgentAssets(
                List.of(), List.of(new SkillDefinition("tdd", null, "d", "body", Map.of(), null)), List.of());
        assertThatThrownBy(() -> materialize(
                        AgentDefinition.builder("dev").skills(List.of("tdd")).build(), assets))
                .hasMessageContaining("symlink");
        assertVictimIntact();
    }

    @Test
    void runtimeCleanupRemovesALinkNotItsTarget() throws Exception {
        CompiledAgent compiled = materialize(AgentDefinition.builder("dev").build(), AgentAssets.NONE);
        new ManagedFiles(workspace).delete(compiled.runtimeDir());
        Files.createSymbolicLink(compiled.runtimeDir(), victim);
        compiled.cleanup();
        assertThat(compiled.runtimeDir()).doesNotExist();
        assertVictimIntact();
    }

    @Test
    void writesReplaceLinksAndRefuseLinkedDirectories() throws Exception {
        ManagedFiles files = new ManagedFiles(workspace);
        Files.createDirectories(workspace.resolve(".loopforge"));
        Files.createSymbolicLink(workspace.resolve(".loopforge/prompts"), victim);
        assertThatThrownBy(() -> files.write(workspace.resolve(".loopforge/prompts/api-token"), "prompt", false))
                .hasMessageContaining("symlink");
        // a link at the file itself is replaced, the target keeps its content
        Files.createDirectories(workspace.resolve(".loopforge/scripts"));
        Files.createSymbolicLink(workspace.resolve(".loopforge/scripts/a.sh"), victim.resolve("api-token"));
        files.write(workspace.resolve(".loopforge/scripts/a.sh"), "echo hi", false);
        assertThat(Files.isSymbolicLink(workspace.resolve(".loopforge/scripts/a.sh")))
                .isFalse();
        assertVictimIntact();
    }

    @Test
    void artifactInputsAreNotWrittenThroughALink() throws Exception {
        WorkflowDefinition wf = WorkflowDefinition.builder("wf")
                .node(WorkflowNode.builder("a", NodeType.SCRIPT).script("true").build())
                .node(WorkflowNode.builder("b", NodeType.SCRIPT).script("true").build())
                .chain("a", "b")
                .build();
        Run run = Run.create("r", wf, "", ExecutionContext.EMPTY, Instant.now());
        Artifact artifact =
                new Artifact("x", "r", "a", 1, "api-token", "out.md", "text/markdown", 3, "s", "pwn", Instant.now());
        Files.createDirectories(workspace.resolve(".loopforge/inputs"));
        Files.createSymbolicLink(workspace.resolve(".loopforge/inputs/a"), victim);
        assertThatThrownBy(() -> io.github.loopforge4j.core.engine.ArtifactSupportAccess.materialize(
                        workspace, run, wf.requireNode("b"), List.of(artifact)))
                .hasMessageContaining("symlink");
        assertVictimIntact();
    }

    @Test
    void gateResultsAreNotReadOrDeletedThroughLinks() throws Exception {
        Files.createDirectories(workspace.resolve(".loopforge/gate-results/n"));
        Files.createSymbolicLink(
                workspace.resolve(".loopforge/gate-results/n/attempt-1.json"), victim.resolve("api-token"));
        assertThatThrownBy(() -> GateResults.read(workspace, ".loopforge/gate-results/n/attempt-1.json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-token");
        GateResults.clear(workspace, ".loopforge/gate-results/n/attempt-1.json");
        assertVictimIntact();
        Files.writeString(workspace.resolve(".loopforge/gate-results/n/attempt-2.json"), "{not json secret-token");
        assertThatThrownBy(() -> GateResults.read(workspace, ".loopforge/gate-results/n/attempt-2.json"))
                .hasMessageNotContaining("secret-token");
    }

    private CompiledAgent materialize(AgentDefinition agent, AgentAssets assets) throws Exception {
        return AgentMaterializer.materialize(new AgentMaterializer.Request(
                workspace,
                "inv",
                "claude-code",
                agent,
                assets,
                ".loopforge/gate-results/n/attempt-1.json",
                null,
                null,
                List.of()));
    }

    private void assertVictimIntact() throws Exception {
        assertThat(Files.readString(victim.resolve("loopforge.mv.db"))).isEqualTo("database");
        assertThat(Files.readString(victim.resolve("api-token"))).isEqualTo("secret-token");
        assertThat(victim.resolve("workspaces/other")).isDirectory();
        try (var children = Files.list(victim)) {
            assertThat(children.count()).isEqualTo(3);
        }
    }
}
