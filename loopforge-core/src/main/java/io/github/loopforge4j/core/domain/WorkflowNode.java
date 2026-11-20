package io.github.loopforge4j.core.domain;

import java.util.List;

/**
 * One step of an SOP.
 *
 * <ul>
 *   <li>{@code agent}: runs the role referenced by {@link #agentId()} and requires a terminal gate
 *       decision.
 *   <li>{@code script}: runs {@link #script()} as a deterministic shell or Python step.
 *   <li>{@code approval}: parks the run until a human approves or sends work back.
 *   <li>{@code sop}: runs the workflow {@link #sopWorkflowId()} as a child run.
 * </ul>
 *
 * <p>{@link #maxAttempts()} bounds automatic retries of this node; {@link #maxReworks()} bounds how
 * often it may send work back to {@link #rejectTo()}.
 */
public record WorkflowNode(
        String id,
        String name,
        NodeType type,
        String agentId,
        String executorId,
        String sopWorkflowId,
        String taskPrompt,
        ScriptLanguage scriptLanguage,
        String script,
        List<ArtifactContract> artifactOutputs,
        String rejectTo,
        FailurePolicy onFailure,
        RetryStrategy retryStrategy,
        List<String> carryOver,
        int maxAttempts,
        int maxReworks,
        Integer timeoutSeconds,
        ExecutionScope executionScope,
        Position position,
        PreviewSettings preview) {

    public WorkflowNode {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name;
        artifactOutputs = artifactOutputs == null ? List.of() : List.copyOf(artifactOutputs);
        carryOver = carryOver == null ? List.of() : List.copyOf(carryOver);
        position = position == null ? Position.ORIGIN : position;
        rejectTo = rejectTo == null || rejectTo.isBlank() ? null : rejectTo.trim();
        preview = type == NodeType.PREVIEW && preview == null ? PreviewSettings.DEFAULT : preview;
    }

    /** Effective number of automatic attempts (at least one). */
    public int attemptBudget() {
        return Math.max(1, maxAttempts);
    }

    /** Effective rework budget of the {@link #rejectTo()} target (at least one). */
    public int reworkBudget() {
        return Math.max(1, maxReworks);
    }

    public ExecutionScope effectiveScope() {
        return executionScope == null ? ExecutionScope.RUN : executionScope;
    }

    public ScriptLanguage effectiveScriptLanguage() {
        return scriptLanguage == null ? ScriptLanguage.SHELL : scriptLanguage;
    }

    /** A failure of this node is routed back upstream instead of failing the run. */
    public boolean rejectsOnFailure() {
        return rejectTo != null && (onFailure == null || onFailure == FailurePolicy.REJECT);
    }

    /** Runs commands without an agent (script and preview nodes): no session, no human context. */
    public boolean scriptNode() {
        return type == NodeType.SCRIPT || type == NodeType.PREVIEW;
    }

    public WorkflowNode withArtifactOutputs(List<ArtifactContract> contracts) {
        return new WorkflowNode(
                id,
                name,
                type,
                agentId,
                executorId,
                sopWorkflowId,
                taskPrompt,
                scriptLanguage,
                script,
                contracts,
                rejectTo,
                onFailure,
                retryStrategy,
                carryOver,
                maxAttempts,
                maxReworks,
                timeoutSeconds,
                executionScope,
                position,
                preview);
    }

    public static Builder builder(String id, NodeType type) {
        return new Builder(id, type);
    }

    /** Fluent builder for tests and catalog code. */
    public static final class Builder {
        private final String id;
        private final NodeType type;
        private String name;
        private String agentId;
        private String executorId;
        private String sopWorkflowId;
        private String taskPrompt;
        private ScriptLanguage language;
        private String script;
        private List<ArtifactContract> artifacts = List.of();
        private String rejectTo;
        private FailurePolicy onFailure;
        private RetryStrategy retryStrategy;
        private int maxAttempts = 1;
        private int maxReworks;
        private Integer timeoutSeconds;
        private ExecutionScope executionScope;
        private PreviewSettings preview;

        private Builder(String id, NodeType type) {
            this.id = id;
            this.type = type;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder agent(String value) {
            this.agentId = value;
            return this;
        }

        public Builder executor(String value) {
            this.executorId = value;
            return this;
        }

        public Builder sopWorkflow(String value) {
            this.sopWorkflowId = value;
            return this;
        }

        public Builder taskPrompt(String value) {
            this.taskPrompt = value;
            return this;
        }

        public Builder script(String value) {
            this.script = value;
            this.language = ScriptLanguage.SHELL;
            return this;
        }

        public Builder python(String value) {
            this.script = value;
            this.language = ScriptLanguage.PYTHON;
            return this;
        }

        public Builder artifacts(ArtifactContract... value) {
            this.artifacts = List.of(value);
            return this;
        }

        public Builder rejectTo(String value) {
            this.rejectTo = value;
            return this;
        }

        public Builder onFailure(FailurePolicy value) {
            this.onFailure = value;
            return this;
        }

        public Builder retryStrategy(RetryStrategy value) {
            this.retryStrategy = value;
            return this;
        }

        public Builder maxAttempts(int value) {
            this.maxAttempts = value;
            return this;
        }

        public Builder maxReworks(int value) {
            this.maxReworks = value;
            return this;
        }

        public Builder scope(ExecutionScope value) {
            this.executionScope = value;
            return this;
        }

        public Builder timeoutSeconds(int value) {
            this.timeoutSeconds = value;
            return this;
        }

        public WorkflowNode build() {
            return new WorkflowNode(
                    id,
                    name,
                    type,
                    agentId,
                    executorId,
                    sopWorkflowId,
                    taskPrompt,
                    language,
                    script,
                    artifacts,
                    rejectTo,
                    onFailure,
                    retryStrategy,
                    List.of(),
                    maxAttempts,
                    maxReworks,
                    timeoutSeconds,
                    executionScope,
                    Position.ORIGIN,
                    preview);
        }

        public Builder preview(PreviewSettings value) {
            this.preview = value;
            return this;
        }
    }
}
