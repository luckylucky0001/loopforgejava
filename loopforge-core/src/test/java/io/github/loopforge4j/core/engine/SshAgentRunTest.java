package io.github.loopforge4j.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.domain.NodeType;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.RunStatus;
import io.github.loopforge4j.core.domain.WorkflowDefinition;
import io.github.loopforge4j.core.domain.WorkflowNode;
import io.github.loopforge4j.core.runtime.git.GitCredentialProvider;
import io.github.loopforge4j.core.runtime.git.GitCredentials;
import io.github.loopforge4j.core.runtime.git.SshAgentTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A run's commands reach the responsible person's SSH key through an agent, only while they run. */
class SshAgentRunTest {

    @Test
    void scriptsSeeTheAgentWhileTheyRunAndNeverTheKey() throws Exception {
        // short root: the agent's socket lives in the workspace and Unix socket paths are short
        Path root = Files.createTempDirectory(Path.of("/tmp"), "lfs");
        String key = SshAgentTest.openSshKey();
        GitCredentialProvider git =
                run -> new GitCredentials(Map.of(), null, null, null, Map.of("github.com", key), "");
        try (EngineFixture f = new EngineFixture(
                root,
                new io.github.loopforge4j.core.store.InMemoryRunStore(),
                false,
                git,
                io.github.loopforge4j.core.delivery.DeliveryProvider.NONE)) {
            f.workflow(WorkflowDefinition.builder("ssh")
                    .node(WorkflowNode.builder("probe", NodeType.SCRIPT)
                            .script("""
                                    eval "set -- $GIT_SSH_COMMAND"
                                    config="$3"
                                    sock=$(sed -n 's/^ *IdentityAgent "\\(.*\\)"$/\\1/p' "$config")
                                    test -S "$sock" && echo "agent at $sock"
                                    grep -q '^Host github.com$' "$config" && echo "github key bound"
                                    if grep -rq "OPENSSH PRIV""ATE KEY" .loopforge; then echo "KEY LEAKED"; fi
                                    echo "$sock" > socket-path.txt
                                    """)
                            .build())
                    .build());
            Run run = f.start("ssh");
            Run done = f.awaitStatus(run.id(), RunStatus.SUCCEEDED);
            String output = done.requireNode("probe").output();
            assertThat(output)
                    .contains("agent at ")
                    .contains("github key bound")
                    .doesNotContain("KEY LEAKED");
            Path socket = Path.of(Files.readString(Path.of(done.workspaceRoot()).resolve("socket-path.txt"))
                    .strip());
            assertThat(socket).doesNotExist();
        }
    }
}
