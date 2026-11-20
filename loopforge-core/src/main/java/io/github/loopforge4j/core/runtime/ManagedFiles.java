package io.github.loopforge4j.core.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Engine-side file operations below a run workspace, which agents can also write. An agent (in a
 * container too: the workspace is mounted at the same path) may replace any directory with a
 * symlink to a host path, so every operation refuses symlinked directory components, files are
 * replaced by atomic rename (a symlink at the target is replaced, not followed), reads open with
 * {@code NOFOLLOW_LINKS} and deletes never descend through links.
 */
public final class ManagedFiles {

    private final Path root;
    private final Path given;

    public ManagedFiles(Path workspace) throws IOException {
        this.given = workspace.toAbsolutePath().normalize();
        this.root = workspace.toRealPath();
    }

    public Path root() {
        return root;
    }

    /** Creates {@code dir} (owner-only) and verifies every component below the workspace root. */
    public Path directory(Path dir) throws IOException {
        Path normalized = inside(dir);
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

    /** Verifies the existing components of {@code dir} without creating anything. */
    public Path verify(Path dir) throws IOException {
        Path normalized = inside(dir);
        Path current = root;
        for (Path segment : root.relativize(normalized)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("control path is a symlink: " + current);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
        }
        return normalized;
    }

    /** Writes a file when its content differs; returns whether it changed. */
    public boolean write(Path file, String content, boolean executable) throws IOException {
        return write(file, content.getBytes(StandardCharsets.UTF_8), executable ? "rwx------" : "rw-------");
    }

    public boolean write(Path file, byte[] content, boolean executable) throws IOException {
        return write(file, content, executable ? "rwx------" : "rw-------");
    }

    /** Writes with explicit POSIX permissions, e.g. {@code r--r--r--} for read-only inputs. */
    public boolean write(Path file, byte[] content, String permissions) throws IOException {
        Path dir = directory(file.getParent());
        Path target = dir.resolve(file.getFileName());
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && Arrays.equals(Files.readAllBytes(target), content)) {
            return false;
        }
        Path tmp = Files.createTempFile(dir, ".lf-", ".tmp");
        try {
            Files.write(tmp, content);
            restrict(tmp, permissions);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return true;
    }

    /**
     * Reads a small regular file; empty when it does not exist. Symlinks and oversized files are
     * rejected so a planted link cannot make the engine read (and report) a host file.
     */
    public Optional<String> read(Path file, int maxBytes) throws IOException {
        verify(file.getParent());
        Path target = inside(file);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("not a regular file: " + root.relativize(target));
        }
        try (SeekableByteChannel channel =
                Files.newByteChannel(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > maxBytes) {
                throw new IOException("file is larger than " + maxBytes + " bytes: " + root.relativize(target));
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill
            }
            return Optional.of(new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8));
        }
    }

    /** Deletes a file or directory tree; a symlink is removed itself, never followed. */
    public void delete(Path path) throws IOException {
        Path target = inside(path);
        verify(target.getParent());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(target);
            return;
        }
        // Files.walk does not follow links below a real start directory
        try (Stream<Path> walk = Files.walk(target)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Recreates an empty directory. */
    public Path reset(Path dir) throws IOException {
        delete(dir);
        return directory(dir);
    }

    /** Maps a path given through the configured (possibly symlinked) workspace path onto the real root. */
    private Path inside(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path mapped = absolute.startsWith(given) && !absolute.startsWith(root)
                ? root.resolve(given.relativize(absolute))
                : absolute;
        if (!mapped.startsWith(root)) {
            throw new IOException("control path escapes the workspace: " + path);
        }
        return mapped;
    }

    private static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // non-POSIX file system
        }
    }
}
