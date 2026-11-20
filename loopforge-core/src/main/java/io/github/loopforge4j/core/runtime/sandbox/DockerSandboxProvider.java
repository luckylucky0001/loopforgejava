package io.github.loopforge4j.core.runtime.sandbox;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Docker sandbox profile (upstream {@code dockerSandbox}): one long-lived container per run
 * workspace, created on first use and removed with the workspace. A crashed engine's leftovers are
 * reclaimed by removing the container; the next acquire creates a fresh one on the same workspace.
 */
public final class DockerSandboxProvider implements SandboxProvider {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxProvider.class);
    public static final String LABEL = "io.github.loopforge4j.workspace";
    private static final Duration DOCKER_TIMEOUT = Duration.ofMinutes(10);

    private final DockerSandboxOptions options;
    private final Path root;
    private final LocalSandboxProvider files;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public DockerSandboxProvider(DockerSandboxOptions options) {
        this.options = options;
        this.root = options.root().toAbsolutePath().normalize();
        this.files = new LocalSandboxProvider(
                new LocalSandboxOptions(root, List.of(), options.privateHome(), options.defaultTimeout()));
    }

    @Override
    public boolean supports(String profileId) {
        return options.profile().equals(profileId);
    }

    @Override
    public Sandbox acquire(String workspaceId) throws IOException {
        Path workspace = files.acquire(workspaceId).workspace();
        String name = containerName(workspaceId);
        synchronized (locks.computeIfAbsent(name, k -> new Object())) {
            if (!"true"
                    .equals(docker(List.of("inspect", "-f", "{{.State.Running}}", name), true)
                            .strip())) {
                docker(List.of("rm", "-f", name), true);
                docker(runArguments(name, workspaceId, workspace), false);
                log.info("started sandbox container {} ({})", name, options.image());
            }
        }
        return new DockerSandbox("docker-" + workspaceId, name, workspace, options);
    }

    List<String> runArguments(String name, String workspaceId, Path workspace) {
        List<String> args = new ArrayList<>(List.of(
                "run",
                "-d",
                "--init",
                "--name",
                name,
                "--label",
                LABEL + "=" + workspaceId,
                "--security-opt",
                "no-new-privileges",
                "-v",
                workspace + ":" + workspace,
                "-w",
                workspace.toString(),
                "--network",
                options.network()));
        args.add("--user");
        args.add(options.user() != null && !options.user().isBlank() ? options.user() : currentUser());
        if (options.mountJavaHome()) {
            String javaHome =
                    Path.of(System.getProperty("java.home")).toAbsolutePath().toString();
            args.addAll(List.of("-v", javaHome + ":" + javaHome + ":ro"));
        }
        if (options.memory() != null && !options.memory().isBlank()) {
            args.addAll(List.of("--memory", options.memory()));
        }
        if (options.cpus() != null && !options.cpus().isBlank()) {
            args.addAll(List.of("--cpus", options.cpus()));
        }
        if (options.pidsLimit() > 0) {
            args.addAll(List.of("--pids-limit", Integer.toString(options.pidsLimit())));
        }
        if (options.readOnlyRoot()) {
            args.addAll(List.of("--read-only", "--tmpfs", "/tmp"));
        }
        args.addAll(List.of("--entrypoint", "sleep", options.image(), "infinity"));
        return args;
    }

    @Override
    public int reclaim(String workspaceId) throws IOException {
        String name = containerName(workspaceId);
        boolean existed = !docker(List.of("ps", "-aq", "--filter", "name=^" + name + "$"), true)
                .isBlank();
        docker(List.of("rm", "-f", name), true);
        return existed ? 1 : 0;
    }

    @Override
    public void release(String workspaceId) throws IOException {
        docker(List.of("rm", "-f", containerName(workspaceId)), true);
        locks.remove(containerName(workspaceId));
        files.release(workspaceId);
    }

    static String containerName(String workspaceId) {
        return "loopforge-" + LocalSandbox.safeSegment(workspaceId).replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String docker(List<String> args, boolean tolerateFailure) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(options.docker());
        argv.addAll(args);
        Map<String, String> env = new HashMap<>();
        for (String key : DockerSandboxOptions.CLIENT_ENV) {
            String value = System.getenv(key);
            if (value != null) {
                env.put(key, value);
            }
        }
        env.putIfAbsent("PATH", "/usr/local/bin:/usr/bin:/bin");
        StringBuilder out = new StringBuilder();
        ExecResult result;
        try {
            result = ProcessRunner.run(
                    argv,
                    root,
                    env,
                    null,
                    DOCKER_TIMEOUT,
                    line -> out.append(line).append('\n'),
                    Cancellation.create());
        } catch (CancelledException e) {
            throw new IOException("docker command cancelled", e);
        }
        if (!result.succeeded() && !tolerateFailure) {
            throw new IOException("docker " + args.getFirst() + " failed (exit " + result.exitCode() + "): "
                    + result.output().strip());
        }
        return result.succeeded() ? out.toString() : "";
    }

    /** uid:gid of the server process, read from a file it creates. */
    private static String currentUser() {
        try {
            Path probe = Files.createTempFile("lf-uid", ".probe");
            try {
                return Files.getAttribute(probe, "unix:uid") + ":" + Files.getAttribute(probe, "unix:gid");
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return "1000:1000";
        }
    }
}
