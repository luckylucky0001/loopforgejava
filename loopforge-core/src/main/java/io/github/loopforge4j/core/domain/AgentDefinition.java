package io.github.loopforge4j.core.domain;

import java.time.Instant;
import java.util.List;

/**
 * Reusable role: one free-form brief plus the executor, gates and artifacts it owns. The engine
 * wraps {@link #prompt()} into a task book (see upstream {@code compileTaskPrompt}).
 */
public record AgentDefinition(
        String id,
        String name,
        int version,
        String description,
        String prompt,
        List<String> rules,
        List<String> skills,
        List<String> mcpServers,
        List<AgentHook> hooks,
        String executorId,
        String model,
        String effort,
        AgentLoopPolicy loop,
        List<HumanInterventionPoint> interventions,
        List<ArtifactContract> artifactOutputs,
        List<VerificationGate> verification,
        Instant updatedAt) {

    public AgentDefinition {
        id = id == null ? "" : id.strip();
        name = name == null || name.isBlank() ? id : name;
        version = Math.max(1, version);
        rules = rules == null ? List.of() : List.copyOf(rules);
        skills = skills == null ? List.of() : List.copyOf(skills);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
        loop = loop == null ? AgentLoopPolicy.DISABLED : loop;
        interventions = interventions == null ? List.of() : List.copyOf(interventions);
        artifactOutputs = artifactOutputs == null ? List.of() : List.copyOf(artifactOutputs);
        verification = verification == null ? List.of() : List.copyOf(verification);
    }

    public AgentDefinition withRules(List<String> newRules) {
        return new AgentDefinition(
                id,
                name,
                version,
                description,
                prompt,
                newRules,
                skills,
                mcpServers,
                hooks,
                executorId,
                model,
                effort,
                loop,
                interventions,
                artifactOutputs,
                verification,
                updatedAt);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public AgentDefinition withUpdatedAt(Instant instant) {
        return new AgentDefinition(
                id,
                name,
                version,
                description,
                prompt,
                rules,
                skills,
                mcpServers,
                hooks,
                executorId,
                model,
                effort,
                loop,
                interventions,
                artifactOutputs,
                verification,
                instant);
    }

    /** Fluent builder for tests and catalog code. */
    public static final class Builder {
        private final String id;
        private String name;
        private String description;
        private String prompt;
        private String executorId;
        private String model;
        private String effort;
        private AgentLoopPolicy loop;
        private List<HumanInterventionPoint> interventions = List.of();
        private List<ArtifactContract> artifacts = List.of();
        private List<VerificationGate> verification = List.of();
        private List<String> rules = List.of();
        private List<String> skills = List.of();
        private List<String> mcpServers = List.of();
        private List<AgentHook> hooks = List.of();

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String value) {
            this.name = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder prompt(String value) {
            this.prompt = value;
            return this;
        }

        public Builder executor(String value) {
            this.executorId = value;
            return this;
        }

        public Builder model(String value) {
            this.model = value;
            return this;
        }

        public Builder effort(String value) {
            this.effort = value;
            return this;
        }

        public Builder loop(AgentLoopPolicy value) {
            this.loop = value;
            return this;
        }

        public Builder rules(List<String> value) {
            this.rules = value;
            return this;
        }

        public Builder skills(List<String> value) {
            this.skills = value;
            return this;
        }

        public Builder mcpServers(List<String> value) {
            this.mcpServers = value;
            return this;
        }

        public Builder hooks(List<AgentHook> value) {
            this.hooks = value;
            return this;
        }

        public Builder interventions(List<HumanInterventionPoint> value) {
            this.interventions = value;
            return this;
        }

        public Builder artifacts(List<ArtifactContract> value) {
            this.artifacts = value;
            return this;
        }

        public Builder verification(List<VerificationGate> value) {
            this.verification = value;
            return this;
        }

        public AgentDefinition build() {
            return new AgentDefinition(
                    id,
                    name,
                    1,
                    description,
                    prompt,
                    rules,
                    skills,
                    mcpServers,
                    hooks,
                    executorId,
                    model,
                    effort,
                    loop,
                    interventions,
                    artifacts,
                    verification,
                    null);
        }
    }
}
