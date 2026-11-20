package io.github.loopforge4j.core.runtime.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs only where bubblewrap can create user namespaces (it may be missing or restricted on CI). */
class BwrapSandboxTest {

    /** Outside /tmp: the sandbox's private /tmp would hide test files and mask a missing mount. */
    Path root;

    @org.junit.jupiter.api.BeforeEach
    void createRoot() throws Exception {
        root = Files.createDirectories(Path.of(
                        "target", "bwrap-test", java.util.UUID.randomUUID().toString()))
                .toAbsolutePath();
    }

    @org.junit.jupiter.api.AfterEach
    void deleteRoot() throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }

    @BeforeAll
    static void requireBubblewrap() {
        assumeTrue(BwrapSandboxProvider.available("bwrap"), "bubblewrap is not usable on this host");
    }

    private BwrapSandboxProvider provider(Path secretHome, boolean network) {
        LocalSandboxOptions local = new LocalSandboxOptions(
                root.resolve("workspaces"), LocalSandboxOptions.DEFAULT_PASSTHROUGH, true, Duration.ofMinutes(1));
        return new BwrapSandboxProvider(
                local, new BwrapSandboxProvider.Options("bwrap", "bwrap", List.of(secretHome), network));
    }

    private static ExecResult sh(Sandbox sandbox, String script) throws Exception {
        return sandbox.exec(
                CommandSpec.shell(script, "test", Duration.ofSeconds(30)), LineListener.NONE, Cancellation.create());
    }

    @Test
    void writesOnlyItsWorkspaceAndSeesNoOtherRunsOrServerHome() throws Exception {
        Path home = Files.createDirectories(root.resolve("server-home"));
        Files.writeString(home.resolve("credentials"), "server-secret");
        BwrapSandboxProvider provider = provider(home, true);
        Sandbox other = provider.acquire("other-run");
        Files.writeString(other.workspace().resolve("private.txt"), "other run data");
        Sandbox sandbox = provider.acquire("run-1");
        assertThat(sandbox.driver()).isEqualTo("bwrap");

        assertThat(sh(sandbox, "echo hi > out.txt && cat out.txt").output()).contains("hi");
        assertThat(sandbox.workspace().resolve("out.txt")).hasContent("hi\n");
        // the host is read-only
        ExecResult etc = sh(sandbox, "touch /etc/loopforge-escape 2>&1; echo code=$?");
        assertThat(etc.output()).contains("code=1");
        assertThat(Path.of("/etc/loopforge-escape")).doesNotExist();
        // other workspaces and the server's home are empty
        assertThat(sh(sandbox, "cat " + other.workspace().resolve("private.txt") + " 2>&1; echo code=$?")
                        .output())
                .doesNotContain("other run data")
                .contains("code=1");
        assertThat(sh(sandbox, "cat " + home.resolve("credentials") + " 2>&1; echo code=$?")
                        .output())
                .doesNotContain("server-secret")
                .contains("code=1");
        // /tmp is private to the process
        sh(sandbox, "echo inside > /tmp/loopforge-bwrap-probe");
        assertThat(Path.of("/tmp/loopforge-bwrap-probe")).doesNotExist();
        // processes run in their own PID namespace
        assertThat(sh(sandbox, "ls /proc | grep -c '^[0-9]'").output().strip()).matches("[0-9]{1,2}");
        // the server's environment is not inherited
        assertThat(sh(sandbox, "env").output()).contains("LOOPFORGE_WORKSPACE=").doesNotContain("JAVA_TOOL_OPTIONS");
    }

    @Test
    void networkCanBeIsolated() throws Exception {
        Sandbox sandbox =
                provider(Files.createDirectories(root.resolve("h")), false).acquire("run-net");
        assertThat(sh(sandbox, "cat /proc/net/dev | grep -c ':'").output().strip())
                .isEqualTo("1"); // only lo
    }

    @Test
    void cancellationKillsTheWholeTree() throws Exception {
        Sandbox sandbox =
                provider(Files.createDirectories(root.resolve("h")), true).acquire("run-cancel");
        Cancellation cancellation = Cancellation.create();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                sandbox.exec(
                        CommandSpec.shell("sleep 317 & sleep 318; wait", "test", Duration.ofMinutes(1)),
                        LineListener.NONE,
                        cancellation);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        waitFor(() -> processes("sleep 31[78]") == 2);
        cancellation.cancel();
        assertThatThrownBy(() -> running.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(CancelledException.class);
        waitFor(() -> processes("sleep 31[78]") == 0);
    }

    private static long processes(String pattern) {
        try {
            Process p = new ProcessBuilder("pgrep", "-f", "^" + pattern + "$").start();
            return new String(p.getInputStream().readAllBytes()).lines().count();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(50);
        }
    }

    @Test
    void hiddenPathsSkipTheJdkAndOrderParentsFirst() throws Exception {
        Path a = Files.createDirectories(root.resolve("a/b"));
        Path javaHome = Path.of(System.getProperty("java.home"));
        List<Path> hidden = BwrapSandboxProvider.hiddenPaths(
                root.resolve("a"), List.of(a, javaHome.getParent(), root.resolve("missing")));
        assertThat(hidden).containsExactly(root.resolve("a"), a);
        // the workspace root is hidden even before the first run created it
        assertThat(BwrapSandboxProvider.hiddenPaths(root.resolve("not-yet"), List.of()))
                .containsExactly(root.resolve("not-yet"));
    }
}
