package io.github.loopforge4j.core.runtime.sandbox;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one process with a fully controlled environment, streams stdout line by line and keeps a
 * bounded output tail.
 *
 * <p>Like upstream ({@code Setpgid} + {@code kill(-pgid)} after {@code Wait}), every command runs in
 * its own process group via {@code setsid}, and the whole group is killed when the command exits,
 * times out or is cancelled, so background children never outlive their node. Without {@code
 * setsid} (e.g. macOS) the runner falls back to killing the visible process tree.
 */
public final class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);
    private static final String SETSID = locate("setsid");

    private ProcessRunner() {}

    public static ExecResult run(
            List<String> argv,
            Path directory,
            Map<String, String> environment,
            String stdin,
            Duration timeout,
            LineListener stdout,
            Cancellation cancellation)
            throws IOException, CancelledException {
        cancellation.check();
        long started = System.nanoTime();
        List<String> command = argv;
        if (SETSID != null) {
            // setsid would turn a missing program into exit code 127; fail like a direct launch instead
            requireExecutable(argv.getFirst(), directory, environment.get("PATH"));
            command = new java.util.ArrayList<>(argv.size() + 1);
            command.add(SETSID);
            command.addAll(argv);
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        builder.environment().clear();
        builder.environment().putAll(environment);
        if (stdin == null) {
            builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        }
        Process process = builder.start();
        Cancellation.Registration kill = cancellation.onCancel(() -> killTree(process));
        OutputBuffer tail = new OutputBuffer();
        Thread stdoutReader = Thread.ofVirtual()
                .name("proc-stdout-" + process.pid())
                .start(() -> drain(process.getInputStream(), tail, stdout));
        Thread stderrReader = Thread.ofVirtual()
                .name("proc-stderr-" + process.pid())
                .start(() -> drain(process.getErrorStream(), tail, LineListener.NONE));
        if (stdin != null) {
            Thread.ofVirtual().name("proc-stdin-" + process.pid()).start(() -> feed(process.getOutputStream(), stdin));
        }
        boolean finished;
        try {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (!finished) {
                log.warn("process {} exceeded timeout {}, killing", argv.getFirst(), timeout);
            }
            // Also after a normal exit: background children of the command must not outlive it, and
            // they would otherwise keep the output pipes open.
            killTree(process);
            stdoutReader.join(DRAIN_GRACE);
            stderrReader.join(DRAIN_GRACE);
        } catch (InterruptedException e) {
            killTree(process);
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } finally {
            kill.close();
        }
        if (cancellation.cancelled()) {
            throw new CancelledException();
        }
        int exit = finished ? process.exitValue() : -1;
        return new ExecResult(exit, tail.toString(), !finished, Duration.ofNanos(System.nanoTime() - started));
    }

    /** Kills the command's process group (or, without setsid, its visible descendants). */
    public static void killTree(Process process) {
        if (SETSID != null) {
            killGroup(process.pid());
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.onExit()
                .orTimeout(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(t -> null);
    }

    /**
     * {@code kill -KILL -pgid}; the group id is the pid because setsid made the command its leader. No
     * {@code --}: dash's builtin kill rejects it.
     */
    private static void killGroup(long pgid) {
        try {
            Process killer = new ProcessBuilder("/bin/sh", "-c", "kill -KILL -" + pgid + " 2>/dev/null")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!killer.waitFor(2, TimeUnit.SECONDS)) {
                killer.destroyForcibly();
            }
        } catch (IOException e) {
            log.debug("cannot signal process group {}: {}", pgid, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void requireExecutable(String program, Path directory, String path) throws IOException {
        if (program.contains("/")) {
            if (!java.nio.file.Files.isExecutable(directory.resolve(program))) {
                throw new IOException("Cannot run program \"" + program + "\": not found or not executable");
            }
            return;
        }
        for (String dir : (path == null ? "" : path).split(java.io.File.pathSeparator)) {
            if (!dir.isBlank() && java.nio.file.Files.isExecutable(Path.of(dir, program))) {
                return;
            }
        }
        throw new IOException("Cannot run program \"" + program + "\": not found on PATH");
    }

    private static String locate(String binary) {
        for (String dir : List.of("/usr/bin", "/bin", "/usr/local/bin")) {
            Path candidate = Path.of(dir, binary);
            if (java.nio.file.Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static void drain(InputStream stream, OutputBuffer tail, LineListener listener) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                tail.appendLine(line);
                try {
                    listener.onLine(line);
                } catch (RuntimeException e) {
                    log.warn("line listener failed: {}", e.toString());
                }
            }
        } catch (IOException e) {
            // stream closed because the process was killed
        }
    }

    private static void feed(OutputStream stream, String stdin) {
        try (OutputStream out = stream) {
            out.write(stdin.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // the process exited before reading all input
        }
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
    }
}
