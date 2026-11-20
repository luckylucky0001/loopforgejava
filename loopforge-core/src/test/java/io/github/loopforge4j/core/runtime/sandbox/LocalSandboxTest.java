package io.github.loopforge4j.core.runtime.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSandboxTest {

    @TempDir
    Path root;

    Sandbox sandbox;
    LocalSandboxProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = new LocalSandboxProvider(LocalSandboxOptions.defaults(root));
        sandbox = provider.acquire("run-1");
    }

    @Test
    void runsInWorkspaceWithPrivateHomeAndStreamsLines() throws Exception {
        List<String> lines = new ArrayList<>();
        ExecResult result = sandbox.exec(
                new CommandSpec(
                        List.of("sh", "-c", "pwd; echo $HOME; echo $EXTRA; echo err >&2"),
                        null,
                        Map.of("EXTRA", "x1"),
                        null,
                        Duration.ofSeconds(10),
                        "probe"),
                lines::add,
                Cancellation.create());
        assertThat(result.succeeded()).isTrue();
        assertThat(lines.get(0)).isEqualTo(sandbox.workspace().toString());
        assertThat(lines.get(1))
                .startsWith(sandbox.workspace().resolve(".loopforge/home/probe").toString());
        assertThat(lines.get(2)).isEqualTo("x1");
        assertThat(lines).doesNotContain("err");
        assertThat(result.output()).contains("err");
    }

    @Test
    void stdinIsDeliveredAsUtf8() throws Exception {
        ExecResult result = sandbox.exec(
                new CommandSpec(List.of("sh", "-c", "cat"), null, Map.of(), "你好，世界", null, "cat"),
                LineListener.NONE,
                Cancellation.create());
        assertThat(result.output().strip()).isEqualTo("你好，世界");
    }

    @Test
    void workingDirectoryCannotEscapeWorkspace() {
        assertThatThrownBy(() -> sandbox.exec(
                        new CommandSpec(List.of("true"), "../..", Map.of(), null, null, "x"),
                        LineListener.NONE,
                        Cancellation.create()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeoutKillsTheProcess() throws Exception {
        Instant start = Instant.now();
        ExecResult result = sandbox.exec(
                CommandSpec.shell("sleep 30", "t", Duration.ofMillis(300)), LineListener.NONE, Cancellation.create());
        assertThat(result.timedOut()).isTrue();
        assertThat(result.succeeded()).isFalse();
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void cancellationKillsTheWholeProcessTree() throws Exception {
        Cancellation cancellation = Cancellation.create();
        Path pidFile = sandbox.workspace().resolve("child.pid");
        CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
            try {
                sandbox.exec(
                        CommandSpec.shell("sleep 60 & echo $! > child.pid; wait", "tree", null),
                        LineListener.NONE,
                        cancellation);
                return null;
            } catch (Exception e) {
                return e;
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(20);
        }
        long childPid = Long.parseLong(Files.readString(pidFile).strip());
        cancellation.cancel();
        assertThat(outcome.get(10, TimeUnit.SECONDS)).isInstanceOf(CancelledException.class);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    @Test
    void backgroundChildrenDoNotOutliveTheCommand() throws Exception {
        Instant start = Instant.now();
        ExecResult result = sandbox.exec(
                CommandSpec.shell("sleep 47 & echo $!", "bg", Duration.ofSeconds(10)),
                LineListener.NONE,
                Cancellation.create());
        assertThat(result.succeeded()).isTrue();
        // the background sleep keeps stdout open; it must not delay completion until the drain grace
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(4));
        long pid = Long.parseLong(result.output().strip());
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .isFalse();
    }

    @Test
    void processRecordsAreRemovedAfterNormalExit() throws Exception {
        sandbox.exec(CommandSpec.shell("echo hi", "rec", null), LineListener.NONE, Cancellation.create());
        Path records = sandbox.workspace().resolve(".loopforge/procs");
        assertThat(records).isEmptyDirectory();
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void reclaimKillsGroupsLeftByACrashedEngine() throws Exception {
        Process orphan = new ProcessBuilder("setsid", "sh", "-c", "sleep 60 & wait").start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (orphan.descendants().findAny().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        List<ProcessHandle> children = orphan.descendants().toList();
        long start = orphan.info().startInstant().orElseThrow().toEpochMilli();
        Path records = sandbox.workspace().resolve(".loopforge/procs");
        Files.createDirectories(records);
        // recorded by another engine (pid 1 is never this JVM)
        Files.writeString(records.resolve(Long.toString(orphan.pid())), start + " 1 1");
        // a recycled pid: alive, but with a different start time; must not be killed
        Process unrelated = new ProcessBuilder("sleep", "60").start();
        Files.writeString(records.resolve(Long.toString(unrelated.pid())), "1 1 1");
        // a process that no longer exists
        Files.writeString(records.resolve("999999"), "1 1 1");
        try {
            assertThat(provider.reclaim("run-1")).isEqualTo(1);
            assertThat(orphan.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(children).isNotEmpty().allSatisfy(child -> {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (child.isAlive() && System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                assertThat(child.isAlive()).isFalse();
            });
            assertThat(unrelated.isAlive()).isTrue();
            assertThat(records).isEmptyDirectory();
        } finally {
            unrelated.destroyForcibly();
            orphan.destroyForcibly();
        }
    }

    @Test
    void alreadyCancelledTokenDoesNotStartProcesses() {
        Cancellation cancellation = Cancellation.create();
        cancellation.cancel();
        assertThatThrownBy(() ->
                        sandbox.exec(CommandSpec.shell("touch started", "x", null), LineListener.NONE, cancellation))
                .isInstanceOf(CancelledException.class);
        assertThat(sandbox.workspace().resolve("started")).doesNotExist();
    }

    @Test
    void releaseDeletesWorkspaceIncludingReadOnlyFiles() throws Exception {
        Path file = sandbox.workspace().resolve("ro.txt");
        Files.writeString(file, "x");
        file.toFile().setReadOnly();
        provider.release("run-1");
        assertThat(sandbox.workspace()).doesNotExist();
        provider.release("run-1");
        Path sanitized = provider.acquire("../escape").workspace();
        assertThat(sanitized.normalize()).startsWith(root.toAbsolutePath().normalize());
        assertThat(sanitized.getFileName().toString()).doesNotContain("/");
    }

    @Test
    void outputBufferKeepsTheTail() {
        OutputBuffer buffer = new OutputBuffer(10);
        buffer.appendLine("0123456789");
        buffer.appendLine("abc");
        assertThat(buffer.toString()).startsWith("…(truncated)").endsWith("abc\n");
    }
}
