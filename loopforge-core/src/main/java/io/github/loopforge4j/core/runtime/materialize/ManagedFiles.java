package io.github.loopforge4j.core.runtime.materialize;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Writes control-plane files below a workspace an agent can also write. Every directory component
 * must be a real directory inside the workspace (an agent may have planted a symlink), and files are
 * replaced by atomic rename so a symlink at the target is replaced rather than followed.
 */
final class ManagedFiles {

    private final Path root;
    private final Path given;

    ManagedFiles(Path workspace) throws IOException {
        this.given = workspace.toAbsolutePath().normalize();
        this.root = workspace.toRealPath();
    }

    Path root() {
        return root;
    }

    /** Creates {@code dir} (owner-only) and verifies every component below the workspace root. */
    Path directory(Path dir) throws IOException {
        Path absolute = dir.toAbsolutePath().normalize();
        // callers may use the workspace path as configured (possibly through a symlinked parent)
        Path normalized = absolute.startsWith(given) && !absolute.startsWith(root)
                ? root.resolve(given.relativize(absolute))
                : absolute;
        if (!normalized.startsWith(root)) {
            throw new IOException("control path escapes the workspace: " + dir);
        }
        Path current = root;
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("control path is a symlink: " + current);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                    restrict(current, "rwx------");
                } catch (FileAlreadyExistsException e) {
                    // created concurrently; validated below
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("control path is not a directory: " + current);
            }
        }
        return normalized;
    }

    /** Writes a file when its content differs; returns whether it changed. */
    boolean write(Path file, String content, boolean executable) throws IOException {
        return write(file, content.getBytes(StandardCharsets.UTF_8), executable);
    }

    boolean write(Path file, byte[] content, boolean executable) throws IOException {
        Path dir = directory(file.getParent());
        Path target = dir.resolve(file.getFileName());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(Files.readAllBytes(target), content)) {
            return false;
        }
        Path tmp = Files.createTempFile(dir, ".lf-", ".tmp");
        try {
            Files.write(tmp, content);
            restrict(tmp, executable ? "rwx------" : "rw-------");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }

    /** Deletes a file or directory tree without following symlinks. */
    void delete(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) && !absolute.startsWith(given)) {
            throw new IOException("refusing to delete outside the workspace: " + path);
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Recreates an empty directory. */
    Path reset(Path dir) throws IOException {
        delete(dir);
        return directory(dir);
    }

    private static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // non-POSIX file system
        }
    }
}
