package io.github.loopforge4j.server.preview;

import io.github.loopforge4j.core.preview.PreviewArchive;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks the zip an execution machine uploads for a preview (upstream runner preview upload): only
 * clean relative names, regular files, no duplicates, within the archive limits. The result is
 * checked again by {@link PreviewArchive#collect} before it is stored.
 */
final class PreviewUploads {

    /** A rejected upload: the message is meant for the machine. */
    static final class Rejected extends IOException {
        Rejected(String message) {
            super(message);
        }
    }

    private PreviewUploads() {}

    static void extract(InputStream in, Path target, PreviewArchive.Limits limits) throws IOException {
        Files.createDirectories(target);
        Set<String> seen = new HashSet<>();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = PreviewArchive.safeRelative(entry.getName());
                if (name == null || !name.equals(entry.getName())) {
                    throw new Rejected("preview upload has an unsafe entry " + clip(entry.getName()));
                }
                if (!seen.add(name)) {
                    throw new Rejected("preview upload repeats " + clip(name));
                }
                if (seen.size() > limits.maxFiles()) {
                    throw new Rejected("preview has more than " + limits.maxFiles() + " files");
                }
                Path file = target.resolve(name).normalize();
                if (!file.startsWith(target)) {
                    throw new Rejected("preview upload has an unsafe entry " + clip(name));
                }
                Files.createDirectories(file.getParent());
                long size = 0;
                try (OutputStream out = Files.newOutputStream(file)) {
                    for (int n; (n = zip.read(buffer)) > 0; ) {
                        size += n;
                        total += n;
                        if (size > limits.maxFileBytes()) {
                            throw new Rejected("preview file " + clip(name) + " is too large");
                        }
                        if (total > limits.maxTotalBytes()) {
                            throw new Rejected("preview is too large");
                        }
                        out.write(buffer, 0, n);
                    }
                }
            }
        }
    }

    static void delete(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // best effort: a temp directory
                }
            });
        } catch (IOException e) {
            // best effort
        }
    }

    private static String clip(String value) {
        return value.length() > 120 ? value.substring(0, 120) + "..." : value;
    }
}
