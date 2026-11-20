package io.github.loopforge4j.core.engine;

import io.github.loopforge4j.core.domain.Artifact;
import io.github.loopforge4j.core.domain.Run;
import io.github.loopforge4j.core.domain.WorkflowNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Test access to package-private input materialization. */
public final class ArtifactSupportAccess {

    private ArtifactSupportAccess() {}

    public static String materialize(Path workspace, Run run, WorkflowNode node, List<Artifact> artifacts)
            throws IOException {
        return ArtifactSupport.materializeInputs(workspace, run, node, artifacts);
    }
}
