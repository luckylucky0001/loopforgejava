package io.github.loopforge4j.core.runtime.sandbox;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** One directory per run under {@link LocalSandboxOptions#root()}. */
public final class LocalSandboxProvider implements SandboxProvider {

    public static final String PROFILE = "local";

    private final LocalSandboxOptions options;
    private final Path root;

    public LocalSandboxProvider(LocalSandboxOptions options) {
        this.options = options;
        this.root = options.root().toAbsolutePath().normalize();
    }

    @Override
    public boolean supports(String profileId) {
        return PROFILE.equals(profileId);
    }

    @Override
    public Sandbox acquire(String workspaceId) throws IOException {
        Path workspace = workspaceOf(workspaceId);
        Files.createDirectories(workspace.resolve(LocalSandbox.CONTROL_DIR));
        return new LocalSandbox("local-" + workspaceId, workspace, options);
    }

    @Override
    public void release(String runId) throws IOException {
        Path workspace = workspaceOf(runId);
        if (!Files.exists(workspace)) {
            return;
        }
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path workspaceOf(String runId) {
        Path workspace = root.resolve(LocalSandbox.safeSegment(runId)).normalize();
        if (!workspace.startsWith(root)) {
            throw new IllegalArgumentException("invalid run id " + runId);
        }
        return workspace;
    }
}
