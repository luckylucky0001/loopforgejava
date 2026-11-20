package io.github.loopforge4j.core.runtime.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.runtime.Cancellation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentSandboxTest {

    @TempDir
    Path root;

    static CommandSpec shell(String script, Map<String, String> env) {
        return new CommandSpec(List.of("sh", "-c", script), null, env, null, Duration.ofSeconds(30), "script");
    }

    @Test
    void toolingIsInstalledOnceAndOnPath() throws Exception {
        ProfileSandboxProvider provider = new ProfileSandboxProvider(
                "go",
                new LocalSandboxProvider(LocalSandboxOptions.defaults(root)),
                null,
                "go-env",
                Map.of("GREETING", "from-profile", "MODE", "profile"),
                List.of(".tools/bin"),
                "mkdir -p .tools/bin && printf '#!/bin/sh\\necho tool-says-$GREETING\\n' > .tools/bin/hello"
                        + " && chmod +x .tools/bin/hello && echo x >> installs.log");
        assertThat(provider.supports("go")).isTrue();
        assertThat(provider.supports("local")).isFalse();
        Sandbox sandbox = provider.acquire("ws-1");
        ExecResult first = sandbox.exec(
                shell("hello; echo mode=$MODE", Map.of("MODE", "command")), LineListener.NONE, Cancellation.create());
        assertThat(first.output()).contains("tool-says-from-profile", "mode=command");
        sandbox.exec(shell("hello", Map.of()), LineListener.NONE, Cancellation.create());
        // a second acquire of the same workspace does not reinstall
        provider.acquire("ws-1").exec(shell("true", Map.of()), LineListener.NONE, Cancellation.create());
        assertThat(Files.readAllLines(sandbox.workspace().resolve("installs.log")))
                .hasSize(1);
        // another workspace installs its own copy
        provider.acquire("ws-2").exec(shell("hello", Map.of()), LineListener.NONE, Cancellation.create());
        assertThat(Files.readAllLines(root.resolve("ws-2").resolve("installs.log")))
                .hasSize(1);
    }

    @Test
    void aFailingInstallFailsTheCommandAndIsRetried() throws Exception {
        ProfileSandboxProvider provider = new ProfileSandboxProvider(
                "broken",
                new LocalSandboxProvider(LocalSandboxOptions.defaults(root)),
                null,
                "broken-env",
                Map.of(),
                List.of(),
                "echo attempt >> tries.log; echo nope >&2; exit 3");
        Sandbox sandbox = provider.acquire("ws");
        assertThatThrownBy(() -> sandbox.exec(shell("true", Map.of()), LineListener.NONE, Cancellation.create()))
                .hasMessageContaining("broken-env", "exit 3", "nope");
        assertThatThrownBy(() -> sandbox.exec(shell("true", Map.of()), LineListener.NONE, Cancellation.create()));
        assertThat(Files.readAllLines(sandbox.workspace().resolve("tries.log"))).hasSize(2);
    }

    @Test
    void withoutToolingTheDriverSandboxIsUsedAsIs() throws Exception {
        int[] prepared = {0};
        ProfileSandboxProvider provider = new ProfileSandboxProvider(
                "plain",
                new LocalSandboxProvider(LocalSandboxOptions.defaults(root)),
                () -> prepared[0]++,
                null,
                Map.of(),
                List.of(),
                null);
        assertThat(provider.acquire("ws")).isInstanceOf(LocalSandbox.class);
        assertThat(prepared[0]).isEqualTo(1);
        provider.release("ws");
        assertThat(root.resolve("ws")).doesNotExist();
    }
}
