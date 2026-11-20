package io.github.loopforge4j.core.runtime.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Closed process-launch contract between the engine and a sandbox. Callers name the variables they
 * need explicitly; the sandbox never forwards the server's own environment wholesale.
 *
 * @param argv program and arguments
 * @param workingDirectory directory relative to the workspace root, {@code null} for the root
 * @param environment variables added on top of the sandbox base environment
 * @param stdin text written to the process' standard input, {@code null} for none
 * @param timeout hard wall-clock limit, {@code null} for the sandbox default
 * @param label short provider label used in telemetry and private HOME names
 */
public record CommandSpec(
        List<String> argv,
        String workingDirectory,
        Map<String, String> environment,
        String stdin,
        Duration timeout,
        String label) {

    public CommandSpec {
        Objects.requireNonNull(argv, "argv");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        argv = List.copyOf(argv);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        label = label == null || label.isBlank() ? "process" : label;
    }

    /** {@code sh -lc <script>} at the workspace root. */
    public static CommandSpec shell(String script, String label, Duration timeout) {
        return new CommandSpec(List.of("sh", "-lc", script), null, Map.of(), null, timeout, label);
    }

    public CommandSpec withEnvironment(Map<String, String> extra) {
        java.util.HashMap<String, String> merged = new java.util.HashMap<>(environment);
        merged.putAll(extra);
        return new CommandSpec(argv, workingDirectory, merged, stdin, timeout, label);
    }
}
