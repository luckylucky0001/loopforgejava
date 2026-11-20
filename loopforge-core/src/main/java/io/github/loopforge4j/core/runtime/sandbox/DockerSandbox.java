package io.github.loopforge4j.core.runtime.sandbox;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One container per run workspace; every command is a {@code docker exec}. The workspace is mounted
 * at its host path, so paths in prompts, MCP configs and artifacts mean the same thing on both
 * sides.
 *
 * <p>Variable values never appear on the docker command line (it is visible in {@code ps}): they go
 * into the docker client's own environment and are passed by name ({@code -e NAME}). Killing the
 * client does not stop processes inside the container, so every exec carries a unique {@code
 * LOOPFORGE_EXEC_ID} and is ended by signalling every container process with that marker.
 */
public final class DockerSandbox implements Sandbox {

    private static final Set<String> CLIENT_OWNED = Set.of("PATH", "HOME");
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(15);
    /** Kills every other process carrying this exec's marker, sparing the calling shell. */
    private static final String KILL_MARKED = "for f in /proc/[0-9]*/environ; do p=${f#/proc/}; p=${p%/environ}; "
            + "[ \"$p\" = \"$$\" ] && continue; "
            + "if tr '\\000' '\\n' < \"$f\" 2>/dev/null | grep -qx \"LOOPFORGE_EXEC_ID=$LOOPFORGE_EXEC_ID\"; then "
            + "kill -KILL \"$p\" 2>/dev/null; fi; done";
    /**
     * Runs the command, then ends its background children: they would otherwise keep the exec's
     * output streams open and {@code docker exec} would not return (the local driver kills the
     * process group instead).
     */
    private static final String WRAPPER = "\"$@\"; code=$?; " + KILL_MARKED + "; exit $code";

    private final String id;
    private final String container;
    private final Path workspace;
    private final DockerSandboxOptions options;

    DockerSandbox(String id, String container, Path workspace, DockerSandboxOptions options) {
        this.id = id;
        this.container = container;
        this.workspace = workspace;
        this.options = options;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String driver() {
        return "docker";
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    public String container() {
        return container;
    }

    @Override
    public Path privateHome(String label) {
        return options.privateHome()
                ? workspace.resolve(LocalSandbox.CONTROL_DIR).resolve("home").resolve(LocalSandbox.safeSegment(label))
                : null;
    }

    @Override
    public ExecResult exec(CommandSpec command, LineListener stdout, Cancellation cancellation)
            throws IOException, CancelledException {
        String execId = UUID.randomUUID().toString();
        Map<String, String> inside = new HashMap<>();
        inside.put("LANG", "C.UTF-8");
        Path home = privateHome(command.label());
        if (home != null) {
            java.nio.file.Files.createDirectories(home.resolve("tmp"));
            inside.put("HOME", home.toString());
            inside.put("TMPDIR", home.resolve("tmp").toString());
        }
        inside.put("LOOPFORGE_WORKSPACE", workspace.toString());
        inside.put("LOOPFORGE_SANDBOX_ID", id);
        inside.putAll(command.environment());
        inside.put("LOOPFORGE_EXEC_ID", execId);

        Map<String, String> client = clientEnvironment();
        List<String> argv = new ArrayList<>(List.of(options.docker(), "exec"));
        if (command.stdin() != null) {
            argv.add("-i");
        }
        argv.add("-w");
        argv.add(resolveDirectory(command.workingDirectory()).toString());
        for (Map.Entry<String, String> e : inside.entrySet()) {
            if (CLIENT_OWNED.contains(e.getKey()) || !validName(e.getKey())) {
                // not secret (paths) or not passable by name: given by value
                argv.add("-e");
                argv.add(e.getKey() + "=" + e.getValue());
            } else {
                argv.add("-e");
                argv.add(e.getKey());
                client.put(e.getKey(), e.getValue());
            }
        }
        argv.add(container);
        argv.addAll(List.of("sh", "-c", WRAPPER, "sh"));
        argv.addAll(command.argv());
        Cancellation.Registration kill = cancellation.onCancel(() -> killExec(execId));
        try {
            ExecResult result = ProcessRunner.run(
                    argv,
                    workspace,
                    client,
                    command.stdin(),
                    command.timeout() == null ? options.defaultTimeout() : command.timeout(),
                    stdout,
                    cancellation);
            if (result.timedOut()) {
                killExec(execId);
            }
            return result;
        } finally {
            kill.close();
        }
    }

    /** Kills every container process carrying the exec marker (children inherit it). */
    void killExec(String execId) {
        try {
            ProcessRunner.run(
                    List.of(
                            options.docker(),
                            "exec",
                            "-e",
                            "LOOPFORGE_EXEC_ID=" + execId,
                            container,
                            "sh",
                            "-c",
                            KILL_MARKED + "; true"),
                    workspace,
                    clientEnvironment(),
                    null,
                    KILL_TIMEOUT,
                    LineListener.NONE,
                    Cancellation.create());
        } catch (IOException | CancelledException e) {
            // the container is gone or the daemon unreachable; nothing left to kill
        }
    }

    private Map<String, String> clientEnvironment() {
        Map<String, String> env = new HashMap<>();
        for (String key : DockerSandboxOptions.CLIENT_ENV) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        env.putIfAbsent("PATH", "/usr/local/bin:/usr/bin:/bin");
        return env;
    }

    private static boolean validName(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") && !name.startsWith("DOCKER_");
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
}
