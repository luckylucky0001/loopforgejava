package io.github.loopforge4j.core.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * File access below a trusted root that never follows a link, on any component, at the moment of
 * access. A workspace belongs to agents that can swap a directory for a link between a check and
 * an open; opening each component relative to the already opened parent (openat with O_NOFOLLOW)
 * closes that window.
 */
public final class SafeFiles {

    /** A path below the root that is a link, missing, or of the wrong kind. */
    public static final class Refused extends IOException {
        private final boolean missing;

        public Refused(String message, boolean missing) {
            super(message);
            this.missing = missing;
        }

        public boolean missing() {
            return missing;
        }
    }

    /** An entry of a directory, with attributes read without following links. */
    public record Entry(String name, BasicFileAttributes attributes) {}

    private SafeFiles() {}

    /** Opens {@code root} itself (trusted, may be reached through links). */
    public static SecureDirectoryStream<Path> openRoot(Path root) throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(root);
        if (!(stream instanceof SecureDirectoryStream<Path> secure)) {
            stream.close();
            throw new IOException("this platform cannot open directories without following links");
        }
        return secure;
    }

    /**
     * Opens the directory {@code parts} below {@code dir}, one component at a time, never through a
     * link. The caller closes the returned stream; {@code dir} stays open.
     */
    public static SecureDirectoryStream<Path> openDirectory(SecureDirectoryStream<Path> dir, List<String> parts)
            throws IOException {
        SecureDirectoryStream<Path> current = dir;
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try {
            for (String part : parts) {
                BasicFileAttributes attrs = attributes(current, part);
                if (attrs == null) {
                    throw new Refused("no such directory: " + part, true);
                }
                if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                    throw new Refused(part + " is not a directory", false);
                }
                current = current.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS);
                opened.add(current);
            }
        } catch (Refused e) {
            opened.forEach(SafeFiles::closeQuietly);
            throw e;
        } catch (IOException e) {
            opened.forEach(SafeFiles::closeQuietly);
            // a component swapped for a link between the check and the open (ELOOP) and the like
            throw new Refused(e.getMessage(), false);
        } catch (RuntimeException e) {
            opened.forEach(SafeFiles::closeQuietly);
            throw e;
        }
        // keep only the last one open
        for (int i = 0; i < opened.size() - 1; i++) {
            closeQuietly(opened.get(i));
        }
        return opened.isEmpty() ? null : opened.getLast();
    }

    /** Attributes of {@code name} in {@code dir} without following a link, or null when missing. */
    public static BasicFileAttributes attributes(SecureDirectoryStream<Path> dir, String name) throws IOException {
        try {
            return dir.getFileAttributeView(Path.of(name), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        }
    }

    /** Entries of an opened directory. */
    public static List<Entry> list(SecureDirectoryStream<Path> dir) throws IOException {
        List<Entry> out = new ArrayList<>();
        for (Path child : dir) {
            String name = child.getFileName().toString();
            BasicFileAttributes attrs = attributes(dir, name);
            if (attrs != null) {
                out.add(new Entry(name, attrs));
            }
        }
        return out;
    }

    /**
     * Reads the regular file {@code relative} (slash-separated, no {@code ..}) below {@code root}.
     *
     * @throws Refused for links, non-regular files and files larger than {@code maxBytes}
     */
    public static byte[] read(Path root, String relative, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        copy(root, relative, out, maxBytes);
        return out.toByteArray();
    }

    /** Copies the regular file {@code relative} below {@code root} to {@code out}, at most {@code maxBytes}. */
    public static long copy(Path root, String relative, OutputStream out, long maxBytes) throws IOException {
        List<String> parts = parts(relative);
        String name = parts.removeLast();
        try (SecureDirectoryStream<Path> base = openRoot(root)) {
            SecureDirectoryStream<Path> dir = parts.isEmpty() ? base : openDirectory(base, parts);
            try {
                BasicFileAttributes attrs = attributes(dir, name);
                if (attrs == null) {
                    throw new Refused("no such file: " + relative, true);
                }
                if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                    throw new Refused(relative + " is not a regular file", false);
                }
                if (attrs.size() > maxBytes) {
                    throw new Refused(relative + " is larger than " + maxBytes + " bytes", false);
                }
                try (SeekableByteChannel channel =
                        dir.newByteChannel(Path.of(name), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                    long total = 0;
                    while (channel.read(buffer) > 0 || buffer.position() > 0) {
                        buffer.flip();
                        total += buffer.remaining();
                        if (total > maxBytes) {
                            throw new Refused(relative + " grew beyond " + maxBytes + " bytes", false);
                        }
                        out.write(buffer.array(), 0, buffer.limit());
                        buffer.clear();
                    }
                    return total;
                }
            } finally {
                if (dir != base) {
                    closeQuietly(dir);
                }
            }
        }
    }

    /** Slash-separated components, refusing empty, {@code .} and {@code ..} ones. */
    public static List<String> parts(String relative) throws Refused {
        List<String> parts = new ArrayList<>();
        for (String part : relative.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") || part.indexOf('\0') >= 0) {
                throw new Refused("path is outside the root", false);
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw new Refused("path is required", false);
        }
        return parts;
    }

    public static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            // best effort
        }
    }
}
