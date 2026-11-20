package io.github.loopforge4j.server.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loopforge4j.core.domain.DockerfileDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/** Image builds against a fake docker client (always) and a real daemon when one is reachable (CI). */
class DockerImagesTest {

    @TempDir
    Path root;

    Path fakeDocker() throws Exception {
        Path script = root.resolve("docker");
        Files.writeString(script, """
                #!/bin/sh
                echo "$*" >> %1$s/calls.log
                case "$1 $2" in
                  "image inspect") grep -qx "$5" %1$s/built 2>/dev/null; exit $? ;;
                esac
                if [ "$1" = build ]; then
                  ctx="$5"
                  grep -q FROM "$ctx/Dockerfile" || { echo "no FROM" >&2; exit 1; }
                  cat "$ctx/conf/app.toml" >> %1$s/context.log
                  echo "$4" >> %1$s/built
                fi
                """.formatted(root));
        script.toFile().setExecutable(true);
        return script;
    }

    @Test
    void buildsOncePerContentVersion() throws Exception {
        DockerImages images = new DockerImages(fakeDocker().toString());
        DockerfileDefinition go =
                new DockerfileDefinition("go", null, null, "FROM golang\n", Map.of("conf/app.toml", "x=1"), null);
        String tag = images.ensure(go);
        assertThat(tag).isEqualTo(DockerImages.tag(go));
        assertThat(images.ensure(go)).isEqualTo(tag);
        assertThat(Files.readAllLines(root.resolve("calls.log")))
                .filteredOn(l -> l.startsWith("build"))
                .hasSize(1);
        assertThat(Files.readString(root.resolve("context.log"))).isEqualTo("x=1");
        // a changed context file is a new image
        String next = images.ensure(
                new DockerfileDefinition("go", null, null, "FROM golang\n", Map.of("conf/app.toml", "x=2"), null));
        assertThat(next).isNotEqualTo(tag);
        assertThatThrownBy(
                        () -> images.ensure(new DockerfileDefinition("bad", null, null, "RUN true\n", Map.of(), null)))
                .hasMessageContaining("docker build of 'bad' failed", "no FROM");
        assertThatThrownBy(() -> images.ensure(
                        new DockerfileDefinition("esc", null, null, "FROM x\n", Map.of("../out", "y"), null)))
                .hasMessageContaining("escapes");
    }

    @Test
    @EnabledIf("dockerAvailable")
    void buildsWithARealDaemon() throws Exception {
        DockerImages images = new DockerImages("docker");
        String tag = images.ensure(new DockerfileDefinition(
                "lf-test",
                null,
                null,
                "FROM alpine:3.20\nCOPY hello.txt /hello.txt\n",
                Map.of("hello.txt", "hi\n"),
                null));
        Process p = new ProcessBuilder("docker", "run", "--rm", tag, "cat", "/hello.txt")
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(out).isEqualTo("hi\n");
        new ProcessBuilder("docker", "rmi", "-f", tag).start().waitFor(60, TimeUnit.SECONDS);
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
}
