package io.github.loopforge4j.core.runtime.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loopforge4j.core.runtime.Cancellation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Output of short commands must never be lost, even when many run at once on a busy machine
 * (regression: destroying an exited process closed the pipes before the readers drained them).
 */
class OutputDrainTest {

    @TempDir
    Path root;

    @Test
    void shortCommandsKeepTheirOutputUnderLoad() throws Exception {
        LocalSandboxProvider provider = new LocalSandboxProvider(LocalSandboxOptions.defaults(root));
        Sandbox sandbox = provider.acquire("drain");
        int commands = 200;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ExecResult>> results = new ArrayList<>();
            for (int i = 0; i < commands; i++) {
                int n = i;
                results.add(pool.submit(() -> sandbox.exec(
                        CommandSpec.shell("echo line-" + n + "; echo err-" + n + " >&2", "drain", null),
                        LineListener.NONE,
                        Cancellation.create())));
            }
            for (int i = 0; i < commands; i++) {
                ExecResult result = results.get(i).get();
                assertThat(result.succeeded()).isTrue();
                assertThat(result.output()).contains("line-" + i).contains("err-" + i);
            }
        }
    }
}
