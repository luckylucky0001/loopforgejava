package io.github.loopforge4j.server.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrowserPolicyTest {

    @TempDir
    Path dir;

    @Test
    void webPagesAndWorkspaceFilesLoadMetadataAndOtherFilesDoNot() throws Exception {
        Path workspace = Files.createDirectories(dir.resolve("ws"));
        Files.writeString(workspace.resolve("index.html"), "<p>hi</p>");
        Path outside = Files.writeString(dir.resolve("secret.txt"), "s");
        Files.createSymbolicLink(workspace.resolve("link.txt"), outside);
        BrowserPolicy policy = new BrowserPolicy(workspace);

        assertThat(policy.allowed("https://example.com/a?b=1")).isTrue();
        assertThat(policy.allowed("http://localhost:5173/")).isTrue();
        assertThat(policy.allowed("ws://127.0.0.1:5173/hmr")).isTrue();
        assertThat(policy.allowed("about:blank")).isTrue();
        assertThat(policy.allowed("data:text/html,hi")).isTrue();
        assertThat(policy.allowed("blob:http://localhost/1")).isTrue();
        assertThat(policy.allowed(workspace.resolve("index.html").toUri().toString()))
                .isTrue();
        assertThat(policy.allowed(workspace.resolve("missing.html").toUri().toString()))
                .isTrue();

        assertThat(policy.allowed("http://169.254.169.254/latest/meta-data/")).isFalse();
        assertThat(policy.allowed("http://metadata.google.internal/computeMetadata/v1/"))
                .isFalse();
        assertThat(policy.allowed("http://metadata.google.internal./x")).isFalse();
        assertThat(policy.allowed("http://100.100.100.200/latest/")).isFalse();
        assertThat(policy.allowed("http://[fd00:ec2::254]/")).isFalse();
        assertThat(policy.allowed("http://[fe80::1]/")).isFalse();
        assertThat(policy.allowed(outside.toUri().toString())).isFalse();
        assertThat(policy.allowed(workspace.resolve("link.txt").toUri().toString()))
                .isFalse();
        assertThat(policy.allowed(workspace.resolve("../secret.txt").toUri().toString()))
                .isFalse();
        assertThat(policy.allowed("file://host/share/x")).isFalse();
        assertThat(policy.allowed("chrome://settings")).isFalse();
        assertThat(policy.allowed("javascript:alert(1)")).isFalse();
        assertThat(policy.allowed("ftp://example.com/")).isFalse();
        assertThat(new BrowserPolicy(null)
                        .allowed(workspace.resolve("index.html").toUri().toString()))
                .isFalse();
    }
}
