package io.github.loopforge4j.core.engine;

import java.util.List;

/** A workflow definition violates engine invariants; {@link #problems()} lists every violation. */
public class WorkflowValidationException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    public WorkflowValidationException(List<String> problems) {
        super("invalid workflow: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
