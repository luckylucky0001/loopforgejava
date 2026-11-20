package io.github.loopforge4j.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxOptions;
import io.github.loopforge4j.core.runtime.sandbox.LocalSandboxProvider;
import io.github.loopforge4j.core.runtime.sandbox.Sandbox;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTerminalsTest {

    @TempDir
    Path root;

    /** Reads until the output contains {@code expected} (or ten seconds pass). */
    static String until(WorkspaceTerminals terminals, String scope, String id, String expected) throws Exception {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            WorkspaceTerminals.Result r = terminals.read(scope, id);
            all.write(r.data());
            String text = all.toString(StandardCharsets.UTF_8);
            if (text.contains(expected) || r.exited()) {
                return text;
            }
            Thread.sleep(100);
        }
        return all.toString(StandardCharsets.UTF_8);
    }

    @Test
    void shellsRunInTheWorkspaceUnderAPseudoTerminalAndOnlyForTheirScope() throws Exception {
        Sandbox sandbox = new LocalSandboxProvider(LocalSandboxOptions.defaults(root)).acquire("run-1");
        Files.writeString(sandbox.workspace().resolve("README.md"), "hello");
        try (WorkspaceTerminals terminals = new WorkspaceTerminals(Clock.systemUTC())) {
            String id = terminals.open(sandbox, "run-1:ann", 120, 40, Map.of()).id();
            terminals.input(
                    "run-1:ann",
                    id,
                    "echo ready-$((40+2)); cat README.md; echo; stty size; test -t 0 && echo tty-$((2*3))\n");
            String out = until(terminals, "run-1:ann", id, "tty-6");
            assertThat(out)
                    .contains("ready-42")
                    .contains("hello")
                    .contains("40 120")
                    .contains("tty-6");

            // another person (scope) cannot read or type into it
            assertThatThrownBy(() -> terminals.read("run-1:bob", id)).hasMessageContaining("无权访问");
            assertThatThrownBy(() -> terminals.input("run-1:bob", id, "x\n")).hasMessageContaining("无权访问");
            assertThatThrownBy(() -> terminals.input("run-1:ann", id, "x".repeat(40_000)))
                    .hasMessageContaining("过长");

            // two per scope
            String second =
                    terminals.open(sandbox, "run-1:ann", 80, 24, Map.of()).id();
            assertThatThrownBy(() -> terminals.open(sandbox, "run-1:ann", 80, 24, Map.of()))
                    .hasMessageContaining("上限");
            terminals.close("run-1:ann", second);

            // exiting the shell ends the terminal and cleans up its pipe
            terminals.input("run-1:ann", id, "exit\n");
            long deadline = System.nanoTime() + 10_000_000_000L;
            boolean exited = false;
            while (!exited && System.nanoTime() < deadline) {
                exited = terminals.read("run-1:ann", id).exited();
                Thread.sleep(100);
            }
            assertThat(exited).isTrue();
            assertThat(terminals.size()).isZero();
            assertThatThrownBy(() -> terminals.read("run-1:ann", id)).hasMessageContaining("已关闭");
            Thread.sleep(300);
            try (var left = Files.list(sandbox.workspace().resolve(".loopforge/term"))) {
                assertThat(left).isEmpty();
            }
        }
    }

    @Test
    void shellsNobodyReadsAreClosed() throws Exception {
        Sandbox sandbox = new LocalSandboxProvider(LocalSandboxOptions.defaults(root)).acquire("run-2");
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        try (WorkspaceTerminals terminals = new WorkspaceTerminals(clock)) {
            String id = terminals.open(sandbox, "run-2:ann", 80, 24, Map.of()).id();
            terminals.reap();
            assertThat(terminals.size()).isEqualTo(1);
            now.set(now.get().plus(WorkspaceTerminals.IDLE).plus(Duration.ofSeconds(1)));
            terminals.reap();
            assertThat(terminals.size()).isZero();
            assertThatThrownBy(() -> terminals.read("run-2:ann", id)).hasMessageContaining("已关闭");
        }
    }
}
