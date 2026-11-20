package io.github.loopforge4j.core.runtime;

import io.github.loopforge4j.core.runtime.sandbox.CommandSpec;
import io.github.loopforge4j.core.runtime.sandbox.LineListener;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandbox;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interactive shells in a workspace (upstream {@code TerminalPool}): each runs through the
 * workspace's own sandbox, under a pseudo-terminal from {@code script} where the sandbox has it.
 * Input reaches it through a named pipe and output comes back through an append-only file, both in
 * the workspace's control directory, so every sandbox driver works the same way. A shell nobody
 * reads from for {@link #IDLE} is closed.
 */
public final class WorkspaceTerminals implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTerminals.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A browser that stops polling loses its shell after this long. */
    public static final Duration IDLE = Duration.ofSeconds(15);
    /** No shell lives longer than this. */
    public static final Duration MAX_LIFETIME = Duration.ofHours(8);

    static final int PER_SCOPE = 2;
    static final int TOTAL = 64;
    static final int MAX_INPUT = 32 * 1024;
    static final int MAX_READ = 256 * 1024;
    static final long MAX_OUTPUT = 32L * 1024 * 1024;

    /** What a read returns: new output since the last read, and whether the shell is gone. */
    public record Result(String id, byte[] data, boolean exited) {}

    /** A refusal meant for the caller. */
    public static final class TerminalException extends IOException {
        public TerminalException(String message) {
            super(message);
        }
    }

    /** The shell: a PTY when {@code script} is there, else a plain interactive shell. */
    static final String LAUNCH = """
            in="$1"; out="$2"; cols="$3"; rows="$4"
            inner="stty cols $cols rows $rows 2>/dev/null; export TERM=xterm-256color; \
            if command -v bash >/dev/null 2>&1; then exec bash -i; else exec sh -i; fi"
            if command -v script >/dev/null 2>&1; then
              exec script -qfc "$inner" /dev/null < "$in" >> "$out" 2>&1
            fi
            exec sh -c "$inner" < "$in" >> "$out" 2>&1
            """;

    private final class Session {
        final String id;
        final String scope;
        final Path dir;
        final Path out;
        final Cancellation cancellation = Cancellation.create();
        final CompletableFuture<OutputStream> input = new CompletableFuture<>();
        final Instant started;
        volatile Instant seen;
        volatile boolean exited;
        long offset;

        Session(String id, String scope, Path dir, Instant now) {
            this.id = id;
            this.scope = scope;
            this.dir = dir;
            this.out = dir.resolve("out");
            this.started = now;
            this.seen = now;
        }
    }

    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public WorkspaceTerminals(Clock clock) {
        this.clock = clock;
    }

    /**
     * Opens a shell at the workspace root of {@code sandbox}.
     *
     * @param scope who may use it, e.g. the run and the person: other scopes never see it
     */
    public synchronized Result open(Sandbox sandbox, String scope, int cols, int rows, Map<String, String> environment)
            throws IOException {
        long mine =
                sessions.values().stream().filter(s -> s.scope.equals(scope)).count();
        if (mine >= PER_SCOPE || sessions.size() >= TOTAL) {
            throw new TerminalException("终端数量已达上限，请先关闭已有终端");
        }
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        Path root = sandbox.workspace();
        ManagedFiles files = new ManagedFiles(root);
        Path dir = root.resolve(LocalSandbox.CONTROL_DIR).resolve("term").resolve(id);
        files.directory(dir);
        Path in = dir.resolve("in");
        Session session = new Session(id, scope, dir, clock.instant());
        try {
            Files.createFile(session.out);
            Process fifo = new ProcessBuilder("mkfifo", "-m", "600", in.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!fifo.waitFor(10, TimeUnit.SECONDS) || fifo.exitValue() != 0) {
                throw new TerminalException("无法创建终端管道");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delete(dir);
            throw new TerminalException("interrupted");
        } catch (IOException e) {
            delete(dir);
            throw e;
        }
        String rel = LocalSandbox.CONTROL_DIR + "/term/" + id + "/";
        CommandSpec command = new CommandSpec(
                List.of(
                        "sh",
                        "-c",
                        LAUNCH,
                        "sh",
                        rel + "in",
                        rel + "out",
                        Integer.toString(clamp(cols, 20, 500, 100)),
                        Integer.toString(clamp(rows, 5, 200, 30))),
                null,
                environment,
                null,
                MAX_LIFETIME,
                "terminal");
        sessions.put(id, session);
        Thread.ofVirtual().name("terminal-" + id.substring(0, 8)).start(() -> {
            try {
                sandbox.exec(command, LineListener.NONE, session.cancellation);
            } catch (CancelledException e) {
                // closed
            } catch (IOException | RuntimeException e) {
                log.warn("terminal {} ended: {}", id.substring(0, 8), e.toString());
            } finally {
                session.exited = true;
                session.input.completeExceptionally(new TerminalException("终端已退出"));
            }
        });
        // opening the pipe for writing waits for the shell to open it for reading
        Thread.ofVirtual().name("terminal-in-" + id.substring(0, 8)).start(() -> {
            try {
                session.input.complete(Files.newOutputStream(in));
            } catch (IOException e) {
                session.input.completeExceptionally(e);
            }
        });
        return new Result(id, new byte[0], false);
    }

    private Session require(String scope, String id) throws TerminalException {
        Session s = id == null ? null : sessions.get(id);
        if (s == null || !s.scope.equals(scope)) {
            throw new TerminalException("终端已关闭或无权访问");
        }
        s.seen = clock.instant();
        return s;
    }

    /** New output since the last read; a shell that exited and was read to the end is removed. */
    public Result read(String scope, String id) throws IOException {
        Session s = require(scope, id);
        byte[] data;
        synchronized (s) {
            try (RandomAccessFile file = new RandomAccessFile(s.out.toFile(), "r")) {
                long length = file.length();
                if (length > MAX_OUTPUT) {
                    close(scope, id);
                    return new Result(id, "\r\n[输出过多，终端已关闭]\r\n".getBytes(StandardCharsets.UTF_8), true);
                }
                int n = (int) Math.min(MAX_READ, Math.max(0, length - s.offset));
                data = new byte[n];
                file.seek(s.offset);
                file.readFully(data);
                s.offset += n;
            } catch (java.io.FileNotFoundException e) {
                data = new byte[0];
            }
        }
        boolean done = s.exited && data.length == 0;
        if (done) {
            close(scope, id);
        }
        return new Result(id, data, done);
    }

    /** Keys typed in the console, written to the shell's input. */
    public void input(String scope, String id, String data) throws IOException {
        Session s = require(scope, id);
        if (data == null || data.isEmpty()) {
            return;
        }
        if (data.length() > MAX_INPUT) {
            throw new TerminalException("终端输入过长");
        }
        try {
            OutputStream in = s.input.get(2, TimeUnit.SECONDS);
            synchronized (in) {
                in.write(data.getBytes(StandardCharsets.UTF_8));
                in.flush();
            }
        } catch (TimeoutException e) {
            throw new TerminalException("终端尚未就绪，请稍后再试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TerminalException("interrupted");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new TerminalException("终端已退出");
        }
    }

    public boolean close(String scope, String id) {
        Session s = sessions.get(id);
        if (s == null || !s.scope.equals(scope)) {
            return false;
        }
        stop(s);
        return true;
    }

    /** Closes every shell whose scope starts with {@code prefix} (for example when a run is deleted). */
    public void closeAll(String prefix) {
        sessions.values().stream()
                .filter(s -> s.scope.startsWith(prefix))
                .toList()
                .forEach(this::stop);
    }

    public boolean active(String prefix) {
        return sessions.values().stream().anyMatch(s -> s.scope.startsWith(prefix));
    }

    public int size() {
        return sessions.size();
    }

    /** Closes shells nobody read from for {@link #IDLE}, and the ones past {@link #MAX_LIFETIME}. */
    public void reap() {
        Instant now = clock.instant();
        for (Session s : List.copyOf(sessions.values())) {
            if (s.seen.plus(IDLE).isBefore(now) || s.started.plus(MAX_LIFETIME).isBefore(now)) {
                stop(s);
            }
        }
    }

    private void stop(Session s) {
        if (sessions.remove(s.id, s)) {
            s.cancellation.cancel();
            s.input.thenAccept(in -> {
                try {
                    in.close();
                } catch (IOException e) {
                    // already closed
                }
            });
            if (!s.input.isDone()) {
                // the shell never opened the pipe: release the writer waiting for it, then clean up
                Path in = s.dir.resolve("in");
                Thread.ofVirtual().start(() -> {
                    try (var unblock = Files.newInputStream(in)) {
                        // the writer's open completes now
                    } catch (IOException e) {
                        // gone
                    }
                    delete(s.dir);
                });
                return;
            }
            delete(s.dir);
        }
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // left behind
                }
            });
        } catch (IOException e) {
            // already gone
        }
    }

    private static int clamp(int v, int min, int max, int fallback) {
        return v < min || v > max ? fallback : v;
    }

    @Override
    public void close() {
        List.copyOf(sessions.values()).forEach(this::stop);
    }
}
