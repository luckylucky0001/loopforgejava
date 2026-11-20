package io.github.loopforge4j.core.runtime.sandbox;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs processes directly on the engine host inside the run workspace. This is a trust boundary for
 * the <em>environment</em> (secrets of the server process are not inherited) but not for the file
 * system: use it for trusted teams and scripts only, like upstream's {@code local} profile.
 */
public final class LocalSandbox implements Sandbox {

    public static final String CONTROL_DIR = ".loopforge";

    private final String id;
    private final Path workspace;
    private final LocalSandboxOptions options;

    LocalSandbox(String id, Path workspace, LocalSandboxOptions options) {
        this.id = id;
        this.workspace = workspace;
        this.options = options;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String driver() {
        return "local";
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public ExecResult exec(CommandSpec command, LineListener stdout, Cancellation cancellation)
            throws IOException, CancelledException {
        Path directory = resolveDirectory(command.workingDirectory());
        Map<String, String> environment = baseEnvironment(command.label());
        environment.putAll(command.environment());
        return ProcessRunner.run(
                command.argv(),
                directory,
                environment,
                command.stdin(),
                command.timeout() == null ? options.defaultTimeout() : command.timeout(),
                stdout,
                cancellation,
                process -> ProcessRecords.record(processRecords(workspace), process));
    }

    /** Directory with one record per running process group (see {@link ProcessRecords}). */
    static Path processRecords(Path workspace) {
        return workspace.resolve(CONTROL_DIR).resolve("procs");
    }

    private Map<String, String> baseEnvironment(String label) throws IOException {
        Map<String, String> env = new HashMap<>();
        Map<String, String> host = System.getenv();
        for (String key : options.passthroughEnv()) {
            String value = host.get(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        env.putIfAbsent("PATH", "/usr/local/bin:/usr/bin:/bin");
        // CLIs and scripts must emit UTF-8 regardless of the server's locale.
        env.putIfAbsent("LANG", "C.UTF-8");
        if (options.privateHome()) {
            Path home = privateHome(label);
            Files.createDirectories(home.resolve("tmp"));
            env.put("HOME", home.toString());
            env.put("TMPDIR", home.resolve("tmp").toString());
        } else if (host.containsKey("HOME")) {
            env.put("HOME", host.get("HOME"));
        }
        env.put("LOOPFORGE_WORKSPACE", workspace.toString());
        env.put("LOOPFORGE_SANDBOX_ID", id);
        return env;
    }

    @Override
    public Path privateHome(String label) {
        return options.privateHome()
                ? workspace.resolve(CONTROL_DIR).resolve("home").resolve(safeSegment(label))
                : null;
    }

    private Path resolveDirectory(String relative) {
        if (relative == null || relative.isBlank()) {
            return workspace;
        }
        Path resolved = workspace.resolve(relative).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException("working directory escapes the workspace: " + relative);
        }
        return resolved;
    }

    static String safeSegment(String value) {
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() || cleaned.startsWith(".") ? "_" + cleaned : cleaned;
    }
}
