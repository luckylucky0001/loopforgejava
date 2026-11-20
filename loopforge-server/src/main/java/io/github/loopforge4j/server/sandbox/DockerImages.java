package io.github.loopforge4j.server.sandbox;

import io.github.loopforge4j.core.domain.DockerfileDefinition;
import io.github.loopforge4j.core.runtime.Cancellation;
import io.github.loopforge4j.core.runtime.CancelledException;
import io.github.loopforge4j.core.runtime.sandbox.ExecResult;
import io.github.loopforge4j.core.runtime.sandbox.LineListener;
import io.github.loopforge4j.core.runtime.sandbox.ProcessRunner;
import io.github.loopforge4j.server.config.LoopForgeProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds the image of a Dockerfile definition once per content version: the tag carries a hash of
 * the Dockerfile and its context files, so an edit builds a new image and runs never see a half
 * rebuilt one.
 */
@Component
public class DockerImages {

    private static final Logger log = LoggerFactory.getLogger(DockerImages.class);
    static final Duration BUILD_TIMEOUT = Duration.ofMinutes(30);

    private final String docker;
    private final Map<String, ReentrantLock> building = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public DockerImages(LoopForgeProperties properties) {
        this(properties.sandbox().docker().binary());
    }

    DockerImages(String docker) {
        this.docker = docker;
    }

    /** {@code loopforge-env/<id>:<content hash>}. */
    public static String tag(DockerfileDefinition dockerfile) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(dockerfile.content().getBytes(StandardCharsets.UTF_8));
            dockerfile.files().forEach((path, content) -> {
                sha.update((byte) 0);
                sha.update(path.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                sha.update((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            });
            String id = dockerfile.id().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
            return "loopforge-env/" + id + ":" + HexFormat.of().formatHex(sha.digest(), 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds the image unless it exists; parallel callers wait for one build. */
    public String ensure(DockerfileDefinition dockerfile) throws IOException {
        String tag = tag(dockerfile);
        ReentrantLock lock = building.computeIfAbsent(tag, k -> new ReentrantLock());
        lock.lock();
        try {
            if (run(List.of(docker, "image", "inspect", "--format", "{{.Id}}", tag), null, Duration.ofMinutes(1))
                    .succeeded()) {
                return tag;
            }
            Path context = Files.createTempDirectory("loopforge-image-");
            try {
                Files.writeString(context.resolve("Dockerfile"), dockerfile.content());
                for (Map.Entry<String, String> file : dockerfile.files().entrySet()) {
                    Path target = context.resolve(file.getKey()).normalize();
                    if (!target.startsWith(context)) {
                        throw new IOException("context file escapes the build directory: " + file.getKey());
                    }
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, file.getValue() == null ? "" : file.getValue());
                }
                log.info("building sandbox image {}", tag);
                ExecResult build =
                        run(List.of(docker, "build", "--quiet", "-t", tag, context.toString()), context, BUILD_TIMEOUT);
                if (!build.succeeded()) {
                    String out = build.output();
                    throw new IOException("docker build of '" + dockerfile.id() + "' failed (exit " + build.exitCode()
                            + "): " + (out.length() <= 2000 ? out : out.substring(out.length() - 2000)));
                }
                return tag;
            } finally {
                try (Stream<Path> walk = Files.walk(context)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static ExecResult run(List<String> argv, Path directory, Duration timeout) throws IOException {
        try {
            return ProcessRunner.run(
                    argv,
                    directory == null ? Path.of(System.getProperty("java.io.tmpdir")) : directory,
                    Map.of(
                            "PATH",
                            System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                            "HOME",
                            System.getProperty("java.io.tmpdir")),
                    null,
                    timeout,
                    LineListener.NONE,
                    Cancellation.create());
        } catch (CancelledException e) {
            throw new IOException("cancelled", e);
        }
    }
}
