package io.github.loopforge4j.core.runtime;

import io.github.loopforge4j.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Snapshots of a run workspace (upstream workspace checkpoints): a zip of its files, read without
 * following links, with a manifest for what zip cannot carry (executable files and symbolic links).
 * The control directory (credentials, runtime files) and reproducible dependency caches are left
 * out; restoring refuses entries that escape the workspace or recreate the control directory.
 */
public final class WorkspaceSnapshot {

    /** The manifest entry; never a workspace path (it starts with the control directory name). */
    static final String MANIFEST = ".loopforge-snapshot.json";

    /** Components never captured (and never restored). */
    static final Set<String> EXCLUDED = Set.of(".loopforge", ".agentflow", "node_modules", ".cache", "__pycache__");

    private WorkspaceSnapshot() {}

    /**
     * @param maxArchiveBytes size of the compressed snapshot
     * @param maxExpandedBytes size of all files together
     * @param maxFiles number of entries
     */
    public record Limits(long maxArchiveBytes, long maxExpandedBytes, int maxFiles) {
        public static final Limits DEFAULT = new Limits(100L << 20, 2L << 30, 200_000);
    }

    /** What a snapshot holds. */
    public record Summary(int files, long bytes) {}

    /** The workspace is larger than the limits allow. */
    public static final class TooLarge extends IOException {
        TooLarge(String message) {
            super(message);
        }
    }

    record Manifest(int version, List<String> executables, Map<String, String> symlinks) {}

    /** Writes a snapshot of {@code root} to {@code out} (which the caller closes). */
    public static Summary create(Path root, OutputStream out, Limits limits) throws IOException {
        Counter counter = new Counter(limits);
        BoundedOutput bounded = new BoundedOutput(out, limits.maxArchiveBytes());
        ZipOutputStream zip = new ZipOutputStream(bounded);
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
        List<String> executables = new ArrayList<>();
        Map<String, String> symlinks = new LinkedHashMap<>();
        try (SecureDirectoryStream<Path> base = SafeFiles.openRoot(root)) {
            walk(root, base, "", zip, counter, executables, symlinks);
        }
        zip.putNextEntry(new ZipEntry(MANIFEST));
        zip.write(Json.write(new Manifest(1, executables, symlinks)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.finish();
        zip.flush();
        return new Summary(counter.files, counter.bytes);
    }

    private static void walk(
            Path root,
            SecureDirectoryStream<Path> dir,
            String prefix,
            ZipOutputStream zip,
            Counter counter,
            List<String> executables,
            Map<String, String> symlinks)
            throws IOException {
        List<SafeFiles.Entry> entries = new ArrayList<>(SafeFiles.list(dir));
        entries.sort(java.util.Comparator.comparing(SafeFiles.Entry::name));
        for (SafeFiles.Entry entry : entries) {
            String name = entry.name();
            // a workspace file posing as the manifest would steer the restore
            if (EXCLUDED.contains(name) || prefix.isEmpty() && MANIFEST.equals(name)) {
                continue;
            }
            String relative = prefix + name;
            var attrs = entry.attributes();
            if (attrs.isSymbolicLink()) {
                counter.entry(0);
                symlinks.put(
                        relative, Files.readSymbolicLink(root.resolve(relative)).toString());
            } else if (attrs.isDirectory()) {
                counter.entry(0);
                zip.putNextEntry(new ZipEntry(relative + "/"));
                zip.closeEntry();
                try (SecureDirectoryStream<Path> child =
                        dir.newDirectoryStream(Path.of(name), LinkOption.NOFOLLOW_LINKS)) {
                    walk(root, child, relative + "/", zip, counter, executables, symlinks);
                }
            } else if (attrs.isRegularFile()) {
                counter.entry(attrs.size());
                PosixFileAttributeView posix = dir.getFileAttributeView(
                        Path.of(name), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (posix != null && posix.readAttributes().permissions().contains(PosixFilePermission.OWNER_EXECUTE)) {
                    executables.add(relative);
                }
                ZipEntry zipEntry = new ZipEntry(relative);
                zipEntry.setLastModifiedTime(attrs.lastModifiedTime());
                zip.putNextEntry(zipEntry);
                try (SeekableByteChannel channel =
                        dir.newByteChannel(Path.of(name), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                    ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                    long copied = 0;
                    while (channel.read(buffer) > 0) {
                        buffer.flip();
                        copied += buffer.remaining();
                        if (copied > attrs.size()) {
                            // a file growing while it is captured would break the limits; it is not the run's result
                            counter.grow(buffer.remaining());
                        }
                        zip.write(buffer.array(), 0, buffer.limit());
                        buffer.clear();
                    }
                }
                zip.closeEntry();
            }
        }
    }

    /**
     * Restores a snapshot into {@code root}, which holds nothing but (possibly) the control directory.
     *
     * @return what was restored
     */
    public static Summary restore(InputStream in, Path root, Limits limits) throws IOException {
        Counter counter = new Counter(limits);
        Manifest manifest = null;
        Path base = root.toRealPath();
        ZipInputStream zip = new ZipInputStream(in);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (MANIFEST.equals(entry.getName())) {
                manifest = Json.read(
                        new String(zip.readNBytes(16 << 20), java.nio.charset.StandardCharsets.UTF_8), Manifest.class);
                continue;
            }
            boolean directory = entry.isDirectory();
            String name =
                    directory ? entry.getName().substring(0, entry.getName().length() - 1) : entry.getName();
            List<String> parts = checked(name);
            if (directory) {
                counter.entry(0);
                directory(base, parts);
                continue;
            }
            Path parent = directory(base, parts.subList(0, parts.size() - 1));
            Path target = parent.resolve(parts.getLast());
            counter.entry(0);
            try (OutputStream out = Files.newOutputStream(
                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n = zip.read(buffer); n > 0; n = zip.read(buffer)) {
                    counter.grow(n);
                    out.write(buffer, 0, n);
                }
            }
            if (entry.getLastModifiedTime() != null) {
                Files.setLastModifiedTime(target, entry.getLastModifiedTime());
            }
        }
        if (manifest != null) {
            for (String executable : manifest.executables() == null ? List.<String>of() : manifest.executables()) {
                List<String> parts = checked(executable);
                Path file = directory(base, parts.subList(0, parts.size() - 1)).resolve(parts.getLast());
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Set<PosixFilePermission> permissions =
                            EnumSet.copyOf(Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS));
                    permissions.add(PosixFilePermission.OWNER_EXECUTE);
                    if (permissions.contains(PosixFilePermission.GROUP_READ)) {
                        permissions.add(PosixFilePermission.GROUP_EXECUTE);
                    }
                    if (permissions.contains(PosixFilePermission.OTHERS_READ)) {
                        permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                    }
                    Files.setPosixFilePermissions(file, permissions);
                }
            }
            for (Map.Entry<String, String> link :
                    (manifest.symlinks() == null ? Map.<String, String>of() : manifest.symlinks()).entrySet()) {
                List<String> parts = checked(link.getKey());
                Path parent = directory(base, parts.subList(0, parts.size() - 1));
                counter.entry(0);
                try {
                    Files.createSymbolicLink(parent.resolve(parts.getLast()), Path.of(link.getValue()));
                } catch (FileAlreadyExistsException e) {
                    // a file of the same name came first: keep the file
                }
            }
        }
        return new Summary(counter.files, counter.bytes);
    }

    /**
     * Copies a stored snapshot to {@code out} without the entries {@code hidden} matches (paths are
     * slash-separated and relative to the workspace).
     */
    public static void filter(InputStream in, OutputStream out, Predicate<String> hidden) throws IOException {
        ZipInputStream zip = new ZipInputStream(in);
        ZipOutputStream copy = new ZipOutputStream(out);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            String path = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (MANIFEST.equals(name) || hidden.test(path)) {
                continue;
            }
            ZipEntry next = new ZipEntry(name);
            if (entry.getLastModifiedTime() != null) {
                next.setLastModifiedTime(entry.getLastModifiedTime());
            }
            copy.putNextEntry(next);
            zip.transferTo(copy);
            copy.closeEntry();
        }
        copy.finish();
        copy.flush();
    }

    /** The checked components of an entry name. */
    private static List<String> checked(String name) throws SafeFiles.Refused {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new SafeFiles.Refused("invalid snapshot entry: " + name, false);
        }
        List<String> parts = SafeFiles.parts(name);
        for (String part : parts) {
            if (EXCLUDED.contains(part) && (part.equals(".loopforge") || part.equals(".agentflow"))) {
                throw new SafeFiles.Refused("snapshot entry inside the control directory: " + name, false);
            }
        }
        return parts;
    }

    /** Creates the directories {@code parts} below {@code base}, refusing links on the way. */
    private static Path directory(Path base, List<String> parts) throws IOException {
        Path current = base;
        for (String part : parts) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new SafeFiles.Refused("snapshot path goes through a link: " + current, false);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException e) {
                    // created concurrently; checked below
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new SafeFiles.Refused("snapshot path is not a directory: " + current, false);
            }
        }
        return current;
    }

    /** Whether the workspace holds anything besides the control directory. */
    public static boolean hasContent(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (!entry.getFileName().toString().equals(".loopforge")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class Counter {
        private final Limits limits;
        int files;
        long bytes;

        Counter(Limits limits) {
            this.limits = limits;
        }

        void entry(long size) throws TooLarge {
            files++;
            if (files > limits.maxFiles()) {
                throw new TooLarge("workspace has more than " + limits.maxFiles() + " files");
            }
            grow(size);
        }

        void grow(long size) throws TooLarge {
            bytes += size;
            if (bytes > limits.maxExpandedBytes()) {
                throw new TooLarge("workspace is larger than " + limits.maxExpandedBytes() + " bytes");
            }
        }
    }

    /** Fails once more than {@code limit} bytes were written. */
    private static final class BoundedOutput extends java.io.FilterOutputStream {
        private long left;

        BoundedOutput(OutputStream out, long limit) {
            super(out);
            this.left = limit;
        }

        @Override
        public void write(int b) throws IOException {
            take(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            take(len);
            out.write(b, off, len);
        }

        private void take(long n) throws TooLarge {
            left -= n;
            if (left < 0) {
                throw new TooLarge("workspace snapshot is larger than the limit");
            }
        }
    }
}
