package io.github.loopforge4j.core.runtime.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * The docker driver against a fake docker client that runs {@code exec} commands locally (always),
 * and against a real daemon when one is reachable (CI).
 */
class DockerSandboxTest {

    @TempDir
    Path root;

    @Test
    void commandsRunThroughDockerExecWithoutValuesOnTheCommandLine() throws Exception {
        Path docker = fakeDocker();
        DockerSandboxProvider provider = new DockerSandboxProvider(options(docker.toString()));
        DockerSandbox sandbox = (DockerSandbox) provider.acquire("run-1");
        provider.acquire("run-1"); // reused while running

        Files.createDirectories(sandbox.workspace().resolve("sub"));
        long started = System.nanoTime();
        ExecResult result = sandbox.exec(
                new CommandSpec(
                        List.of("sh", "-c", "pwd; echo \"secret=$API_TOKEN home=$HOME\"; (sleep 30 &) ; echo started"),
                        "sub",
                        Map.of("API_TOKEN", "t0p-s3cret"),
                        null,
                        Duration.ofSeconds(20),
                        "claude-code"),
                LineListener.NONE,
                Cancellation.create());
        assertThat(result.succeeded()).isTrue();
        // the background sleep was ended by the in-container wrapper instead of holding the streams open
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        assertThat(result.output())
                .contains(sandbox.workspace().resolve("sub").toString())
                .contains("secret=t0p-s3cret")
                .contains("home=" + sandbox.workspace().resolve(".loopforge/home/claude-code"));

        String calls = Files.readString(root.resolve("docker-calls.log"));
        assertThat(calls).doesNotContain("t0p-s3cret").contains("-e API_TOKEN ");
        assertThat(calls.lines().filter(l -> l.startsWith("run ")).count()).isEqualTo(1);
        String run = calls.lines().filter(l -> l.startsWith("run ")).findFirst().orElseThrow();
        assertThat(run)
                .contains("--init", "--name loopforge-run-1", "--security-opt no-new-privileges")
                .contains("-v " + sandbox.workspace() + ":" + sandbox.workspace())
                .contains(":ro") // JDK for the gate MCP server
                .contains("--memory 2g", "--pids-limit 256", "--read-only --tmpfs /tmp")
                .endsWith("--entrypoint sleep alpine:3.20 infinity");
        assertThat(calls).contains("loopforge-run-1 sh -c \"$@\"; code=$?; for f in /proc/");
        assertThat(processesWithMarker()).isZero();

        assertThat(provider.reclaim("run-1")).isEqualTo(1);
        provider.release("run-1");
        assertThat(sandbox.workspace()).doesNotExist();
    }

    @Test
    void cancellationKillsTheProcessInsideTheContainer() throws Exception {
        DockerSandboxProvider provider =
                new DockerSandboxProvider(options(fakeDocker().toString()));
        Sandbox sandbox = provider.acquire("run-2");
        Cancellation cancellation = Cancellation.create();
        CompletableFuture<Object> running = CompletableFuture.supplyAsync(() -> {
            try {
                return sandbox.exec(
                        CommandSpec.shell("echo up > up.txt; sleep 60", "s", null), LineListener.NONE, cancellation);
            } catch (CancelledException e) {
                return e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(sandbox.workspace().resolve("up.txt")));
        long started = System.nanoTime();
        cancellation.cancel();
        assertThat(running.get(20, TimeUnit.SECONDS)).isInstanceOf(CancelledException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(15));
        assertThat(processesWithMarker()).isZero();
    }

    @Test
    @EnabledIf("dockerAvailable")
    void realDockerDaemon() throws Exception {
        DockerSandboxOptions options = new DockerSandboxOptions(
                "docker",
                root.resolve("ws"),
                "alpine:3.20",
                "docker",
                "none",
                null,
                null,
                0,
                false,
                null,
                false,
                true,
                Duration.ofMinutes(5));
        DockerSandboxProvider provider = new DockerSandboxProvider(options);
        try {
            Sandbox sandbox = provider.acquire("it-run");
            ExecResult result = sandbox.exec(
                    new CommandSpec(
                            List.of(
                                    "sh",
                                    "-c",
                                    "echo \"v=$SECRET\" > out.txt; id -u; (sleep 300 &); cat /etc/alpine-release"),
                            null,
                            Map.of("SECRET", "abc"),
                            null,
                            Duration.ofMinutes(2),
                            "s"),
                    LineListener.NONE,
                    Cancellation.create());
            assertThat(result.succeeded()).as(result.output()).isTrue();
            assertThat(Files.readString(sandbox.workspace().resolve("out.txt")).strip())
                    .isEqualTo("v=abc");
            // the background sleep was ended when the command returned
            ExecResult left = sandbox.exec(
                    CommandSpec.shell("pgrep -f 'slee[p] 300' | wc -l", "s", null),
                    LineListener.NONE,
                    Cancellation.create());
            assertThat(left.output().strip()).isEqualTo("0");

            Cancellation cancellation = Cancellation.create();
            CompletableFuture<Object> running = CompletableFuture.supplyAsync(() -> {
                try {
                    return sandbox.exec(
                            CommandSpec.shell("touch up.txt; sleep 600", "s", null), LineListener.NONE, cancellation);
                } catch (CancelledException e) {
                    return e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> Files.exists(sandbox.workspace().resolve("up.txt")));
            cancellation.cancel();
            assertThat(running.get(30, TimeUnit.SECONDS)).isInstanceOf(CancelledException.class);
            ExecResult afterCancel = sandbox.exec(
                    CommandSpec.shell("pgrep -f 'slee[p] 600' | wc -l", "s", null),
                    LineListener.NONE,
                    Cancellation.create());
            assertThat(afterCancel.output().strip()).isEqualTo("0");
        } finally {
            provider.release("it-run");
        }
    }

    static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private DockerSandboxOptions options(String docker) {
        return new DockerSandboxOptions(
                "docker",
                root.resolve("ws"),
                "alpine:3.20",
                docker,
                "none",
                "2g",
                null,
                256,
                true,
                null,
                true,
                true,
                Duration.ofMinutes(5));
    }

    /** Host processes started by the fake docker that still carry an exec marker of this test. */
    private long processesWithMarker() throws Exception {
        Process p = new ProcessBuilder(
                        "sh", "-c", "grep -l 'LF_FAKE_ROOT=" + root + "' /proc/[0-9]*/environ 2>/dev/null | wc -l")
                .start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        // the grep pipeline itself does not carry the variable
        return Long.parseLong(out);
    }

    /**
     * Minimal docker CLI: logs calls, tracks one "container" in a state file, and runs exec commands
     * on the host with the requested directory and environment.
     */
    private Path fakeDocker() throws Exception {
        Path script = root.resolve("docker");
        Files.writeString(script, """
                #!/bin/sh
                log=%1$s/docker-calls.log
                state=%1$s/container.state
                echo "$*" >> "$log"
                cmd=$1; shift
                case "$cmd" in
                  inspect) [ -f "$state" ] && echo true || { echo "no such container" >&2; exit 1; } ;;
                  rm) rm -f "$state" ;;
                  run) touch "$state"; echo cid123 ;;
                  ps) [ -f "$state" ] && echo cid123; true ;;
                  exec)
                    dir=.
                    while [ $# -gt 0 ]; do
                      case "$1" in
                        -i) shift ;;
                        -w) dir=$2; shift 2 ;;
                        -e) case "$2" in *=*) export "$2" ;; esac; shift 2 ;;
                        *) break ;;
                      esac
                    done
                    shift  # container
                    cd "$dir" || exit 1
                    # a new session: like a real container, killing the client does not reach the command
                    # (only the command carries the exec marker, as with a real container's exec process)
                    marker=$LOOPFORGE_EXEC_ID
                    exec env -u LOOPFORGE_EXEC_ID setsid -w env LOOPFORGE_EXEC_ID="$marker" LF_FAKE_ROOT=%1$s "$@"
                    ;;
                esac
                """.formatted(root));
        script.toFile().setExecutable(true);
        return script;
    }
}
