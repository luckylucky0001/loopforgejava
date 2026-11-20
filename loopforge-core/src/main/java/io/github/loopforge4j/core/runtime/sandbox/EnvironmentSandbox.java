package io.github.loopforge4j.core.runtime.sandbox;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.ManagedFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A sandbox with a profile's tooling (upstream environment definitions): its variables in every
 * command (the command's own variables win), its workspace-relative directories first on {@code
 * PATH}, and its install script run once per workspace before the first command.
 */
public final class EnvironmentSandbox implements Sandbox {

    static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(30);
    private static final Map<Path, ReentrantLock> INSTALLS = new ConcurrentHashMap<>();

    private final Sandbox inner;
    private final String environmentId;
    private final Map<String, String> variables;
    private final List<String> pathEntries;
    private final String installScript;

    public EnvironmentSandbox(
            Sandbox inner,
            String environmentId,
            Map<String, String> variables,
            List<String> pathEntries,
            String installScript) {
        this.inner = inner;
        this.environmentId = environmentId == null ? "profile" : environmentId;
        this.variables = Map.copyOf(variables);
        this.pathEntries = List.copyOf(pathEntries);
        this.installScript = installScript;
    }

    @Override
    public String id() {
        return inner.id();
    }

    @Override
    public String driver() {
        return inner.driver();
    }

    @Override
    public Path workspace() {
        return inner.workspace();
    }

    @Override
    public Path privateHome(String label) {
        return inner.privateHome(label);
    }

    @Override
    public ExecResult exec(CommandSpec command, LineListener stdout, Cancellation cancellation)
            throws IOException, CancelledException {
        install(cancellation);
        return inner.exec(wrap(command), stdout, cancellation);
    }

    CommandSpec wrap(CommandSpec command) {
        Map<String, String> env = new LinkedHashMap<>(variables);
        env.putAll(command.environment());
        List<String> argv = command.argv();
        if (!pathEntries.isEmpty()) {
            StringBuilder prefix = new StringBuilder();
            for (String entry : pathEntries) {
                prefix.append(prefix.isEmpty() ? "" : ":")
                        .append("$LOOPFORGE_WORKSPACE/")
                        .append(entry);
            }
            env.put("LOOPFORGE_PATH_PREFIX", prefix.toString());
            List<String> wrapped = new ArrayList<>(List.of(
                    "sh",
                    "-c",
                    "PATH=\"$(eval printf %s \\\"$LOOPFORGE_PATH_PREFIX\\\")${PATH:+:$PATH}\"; export PATH; exec \"$@\"",
                    "sh"));
            wrapped.addAll(argv);
            argv = wrapped;
        }
        return new CommandSpec(
                argv, command.workingDirectory(), env, command.stdin(), command.timeout(), command.label());
    }

    /** Runs the install script once per workspace and script version; parallel nodes wait for it. */
    private void install(Cancellation cancellation) throws IOException, CancelledException {
        if (installScript == null) {
            return;
        }
        Path marker = workspace()
                .resolve(LocalSandbox.CONTROL_DIR)
                .resolve("environments")
                .resolve(environmentId.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + digest(installScript) + ".done");
        if (Files.exists(marker)) {
            return;
        }
        ReentrantLock lock =
                INSTALLS.computeIfAbsent(workspace().toAbsolutePath().normalize(), k -> new ReentrantLock());
        lock.lock();
        try {
            if (Files.exists(marker)) {
                return;
            }
            ExecResult result = inner.exec(
                    wrap(new CommandSpec(
                            List.of("sh", "-c", installScript), null, Map.of(), null, INSTALL_TIMEOUT, "environment")),
                    LineListener.NONE,
                    cancellation);
            if (!result.succeeded()) {
                String output = result.output();
                throw new IOException("environment '" + environmentId + "' install script failed (exit "
                        + result.exitCode() + "): "
                        + (output.length() <= 1000 ? output : output.substring(output.length() - 1000)));
            }
            new ManagedFiles(workspace()).write(marker, "installed\n", false);
        } finally {
            lock.unlock();
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
