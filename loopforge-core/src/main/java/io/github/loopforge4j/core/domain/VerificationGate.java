package io.github.loopforge4j.core.domain;

import java.util.List;

/**
 * Objective check an agent role must pass after it finishes. Command gates run shell commands in the
 * run workspace; manual gates park the node until a human approves.
 */
public record VerificationGate(
        String id,
        String name,
        GateMode mode,
        List<String> commands,
        boolean required,
        FailurePolicy onFailure,
        ExecutionScope executionScope) {

    public VerificationGate {
        mode = mode == null ? GateMode.COMMAND : mode;
        commands = commands == null ? List.of() : List.copyOf(commands);
        name = name == null || name.isBlank() ? id : name;
    }

    public static VerificationGate command(String id, String name, FailurePolicy onFailure, String... commands) {
        return new VerificationGate(id, name, GateMode.COMMAND, List.of(commands), true, onFailure, null);
    }

    public ExecutionScope effectiveScope() {
        return executionScope == null ? ExecutionScope.RUN : executionScope;
    }

    public static VerificationGate mr(String id, String name, FailurePolicy onFailure) {
        return new VerificationGate(id, name, GateMode.MR, List.of(), true, onFailure, ExecutionScope.REPOSITORIES);
    }

    /** Commands joined into one shell script; empty when the gate has nothing to run. */
    public String script() {
        return String.join("\n", commands.stream().filter(c -> !c.isBlank()).toList());
    }
}
