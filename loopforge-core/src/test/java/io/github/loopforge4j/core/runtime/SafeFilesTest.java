package io.github.loopforge4j.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeFilesTest {

    @TempDir
    Path root;

    @Test
    void readsOnlyRegularFilesReachedWithoutLinks() throws Exception {
        Path ws = Files.createDirectories(root.resolve("ws/dir"));
        Files.writeString(ws.resolve("a.txt"), "hello");
        Path outside = Files.createDirectories(root.resolve("outside"));
        Files.writeString(outside.resolve("secret"), "s3cret");
        Files.createSymbolicLink(ws.resolve("link"), outside.resolve("secret"));
        Files.createSymbolicLink(root.resolve("ws/linkdir"), outside);
        Path workspace = root.resolve("ws");

        assertThat(new String(SafeFiles.read(workspace, "dir/a.txt", 100))).isEqualTo("hello");
        assertThatThrownBy(() -> SafeFiles.read(workspace, "dir/link", 100)).isInstanceOf(SafeFiles.Refused.class);
        assertThatThrownBy(() -> SafeFiles.read(workspace, "linkdir/secret", 100))
                .isInstanceOf(SafeFiles.Refused.class);
        assertThatThrownBy(() -> SafeFiles.read(workspace, "../outside/secret", 100))
                .isInstanceOf(SafeFiles.Refused.class);
        assertThatThrownBy(() -> SafeFiles.read(workspace, "dir/a.txt", 3))
                .isInstanceOf(SafeFiles.Refused.class)
                .hasMessageContaining("larger than");
        assertThatThrownBy(() -> SafeFiles.read(workspace, "dir/none", 100))
                .isInstanceOfSatisfying(
                        SafeFiles.Refused.class, r -> assertThat(r.missing()).isTrue());

        // a FIFO is refused without opening (opening would block)
        Process mkfifo = new ProcessBuilder("mkfifo", ws.resolve("pipe").toString()).start();
        assertThat(mkfifo.waitFor()).isZero();
        assertThatThrownBy(() -> SafeFiles.read(workspace, "dir/pipe", 100)).hasMessageContaining("not a regular file");
    }
}
