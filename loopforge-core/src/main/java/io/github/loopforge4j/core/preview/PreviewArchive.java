package io.github.loopforge4j.core.preview;

import io.github.loopforge4j.core.domain.PreviewSettings;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects the static site a preview archives (upstream {@code ArchiveDir}): only regular files of
 * the site directory inside the workspace, never links, devices or hard links, within limits.
 */
public final class PreviewArchive {

    /** Archive limits (upstream defaults: 20,000 files, 500 MB expanded). */
    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes) {
        public static final Limits DEFAULT = new Limits(20_000, 100L << 20, 500L << 20);
    }

    /**
     * The files of a site, by path relative to its root, in path order. Stores read them with
     * {@link io.github.loopforge4j.core.runtime.SafeFiles} under the same limits: the workspace can
     * change between collecting and reading.
     */
    public record Site(Path root, List<String> files, long bytes, String index, Limits limits) {}

    /** Why a site cannot be archived; {@code missingIndex} lets a non-frontend workspace skip instead. */
    public static final class Failure extends Exception {
        private final boolean missingIndex;

        Failure(String message, boolean missingIndex) {
            super(message);
            this.missingIndex = missingIndex;
        }

        public boolean missingIndex() {
            return missingIndex;
        }
    }

    static final int MAX_PATH = 1_000;

    private PreviewArchive() {}

    public static Site collect(Path workspace, PreviewSettings settings, Limits limits) throws Failure, IOException {
        String source = safeRelative(settings.path());
        String index = safeRelative(settings.index());
        if (source == null) {
            throw new Failure("preview path '" + settings.path() + "' is outside the run workspace", false);
        }
        if (index == null) {
            throw new Failure("preview entry '" + settings.index() + "' is not a relative path", false);
        }
        Path base = workspace.toRealPath();
        Path site = base.resolve(source).normalize();
        if (!site.startsWith(base) || !Files.exists(site, LinkOption.NOFOLLOW_LINKS)) {
            throw new Failure("preview directory '" + source + "' was not built", true);
        }
        // every component must be a real directory: a link could point anywhere on the host
        if (!site.toRealPath().equals(site) || !Files.isDirectory(site, LinkOption.NOFOLLOW_LINKS)) {
            throw new Failure("preview directory '" + source + "' is not a directory in the workspace", false);
        }
        List<String> files = new ArrayList<>();
        long[] total = {0};
        try {
            Files.walkFileTree(site, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(site) && dir.getFileName().toString().equals(".git")
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relative = site.relativize(file).toString().replace('\\', '/');
                    if (attrs.isSymbolicLink()) {
                        throw new Limit("preview rejects the link " + relative);
                    }
                    if (!attrs.isRegularFile()) {
                        throw new Limit("preview rejects the special file " + relative);
                    }
                    if (hardLinked(file)) {
                        throw new Limit("preview rejects the hard link " + relative);
                    }
                    if (relative.length() > MAX_PATH) {
                        throw new Limit("preview path " + relative.substring(0, 80) + "... is too long");
                    }
                    if (files.size() == limits.maxFiles()) {
                        throw new Limit("preview has more than " + limits.maxFiles() + " files");
                    }
                    if (attrs.size() > limits.maxFileBytes()) {
                        throw new Limit("preview file " + relative + " is larger than " + mb(limits.maxFileBytes()));
                    }
                    total[0] += attrs.size();
                    if (total[0] > limits.maxTotalBytes()) {
                        throw new Limit("preview is larger than " + mb(limits.maxTotalBytes()));
                    }
                    files.add(relative);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Limit e) {
            throw new Failure(e.getMessage(), false);
        }
        files.sort(null);
        if (!files.contains(index)) {
            throw new Failure("preview entry '" + index + "' is missing in '" + source + "'", true);
        }
        return new Site(site, List.copyOf(files), total[0], index, limits);
    }

    /** A clean relative path without {@code ..}, backslashes or encoded traversal; null otherwise. */
    public static String safeRelative(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()
                || value.indexOf('\0') >= 0
                || value.contains("\\")
                || value.startsWith("/")
                || value.toLowerCase(java.util.Locale.ROOT).matches(".*%(2e|2f|5c).*")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/", -1)) {
            if (part.equals("..")) {
                return null;
            }
            if (!part.isEmpty() && !part.equals(".")) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private static boolean hardLinked(Path file) {
        try {
            Object links = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return links instanceof Integer n && n > 1;
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private static String mb(long bytes) {
        return (bytes >> 20) + " MB";
    }

    private static final class Limit extends IOException {
        Limit(String message) {
            super(message);
        }
    }
}
