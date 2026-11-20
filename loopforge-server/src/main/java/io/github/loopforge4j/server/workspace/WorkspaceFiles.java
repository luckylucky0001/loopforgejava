package io.github.loopforge4j.server.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reading and writing a run's workspace for people (upstream {@code run_workspace.go} and {@code
 * workspace_edit.go}): relative paths only, no links, sensitive paths hidden, text up to 1 MiB.
 */
final class WorkspaceFiles {

    static final int TREE_LIMIT = 5_000;
    static final int FILE_LIMIT = 1 << 20;
    static final int SEARCH_RESULTS = 100;
    static final long SEARCH_BYTES = 20L << 20;
    static final long SEARCH_FILE = 256 * 1024;

    /** A rejected request; {@code missing} answers 404. */
    static final class WorkspaceException extends RuntimeException {
        final boolean missing;

        WorkspaceException(String message, boolean missing) {
            super(message);
            this.missing = missing;
        }
    }

    record Entry(String path, String name, String type, long size, Instant modified) {}

    record Tree(List<Entry> entries, boolean truncated) {}

    record Content(String path, String content, long size, String sha256) {}

    record Hit(String path, int line, String preview) {}

    private WorkspaceFiles() {}

    /** Credentials, VCS internals and the platform's own files are never shown (upstream rule). */
    static boolean sensitive(String relative) {
        String[] parts = relative.toLowerCase(Locale.ROOT).split("/");
        for (String part : parts) {
            switch (part) {
                case ".git",
                        ".loopforge",
                        ".agentflow",
                        ".ssh",
                        ".gnupg",
                        ".netrc",
                        ".npmrc",
                        ".git-credentials",
                        "id_rsa",
                        "id_ed25519",
                        "id_ecdsa" -> {
                    return true;
                }
                default -> {}
            }
            if (part.equals(".env") || part.startsWith(".env.") && !part.endsWith(".example")) {
                return true;
            }
        }
        return false;
    }

    private static boolean skippedDirectory(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "node_modules", ".cache", "vendor" -> true;
            default -> false;
        };
    }

    /** A clean relative path, or "" for the root. */
    static String relative(String raw) {
        String value = raw == null ? "" : raw.strip().replace('\\', '/');
        if (value.indexOf('\0') >= 0 || value.startsWith("/")) {
            throw new WorkspaceException("invalid workspace path", false);
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new WorkspaceException("workspace path is not accessible", false);
            }
            parts.add(part);
        }
        String rel = String.join("/", parts);
        if (!rel.isEmpty() && sensitive(rel)) {
            throw new WorkspaceException("workspace path is not accessible", false);
        }
        return rel;
    }

    /** Resolves a path inside the root without following links anywhere on the way. */
    static Path resolve(Path root, String raw, boolean directory) {
        String rel = relative(raw);
        Path base;
        try {
            base = root.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceException("workspace is not available", true);
        }
        Path target = base;
        for (String part : rel.isEmpty() ? new String[0] : rel.split("/")) {
            target = target.resolve(part);
            if (Files.isSymbolicLink(target)) {
                throw new WorkspaceException("symbolic links are not accessible", false);
            }
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("no such path: " + rel, true);
        }
        if (directory && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("workspace path is not a directory", false);
        }
        if (!directory && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceException("workspace path is not a regular file", false);
        }
        return target;
    }

    static Tree tree(Path root, String raw) {
        Path start = resolve(root, raw, true);
        Path base;
        try {
            base = root.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceException("workspace is not available", true);
        }
        List<Entry> entries = new ArrayList<>();
        boolean[] truncated = {false};
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(start)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = base.relativize(dir).toString().replace('\\', '/');
                    if (sensitive(rel) || skippedDirectory(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return add(rel, dir, attrs, "directory");
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String rel = base.relativize(file).toString().replace('\\', '/');
                    if (sensitive(rel) || attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    return add(rel, file, attrs, "file");
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                private FileVisitResult add(String rel, Path path, BasicFileAttributes attrs, String type) {
                    if (entries.size() >= TREE_LIMIT) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    entries.add(new Entry(
                            rel,
                            path.getFileName().toString(),
                            type,
                            type.equals("file") ? attrs.size() : 0,
                            attrs.lastModifiedTime().toInstant()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new WorkspaceException("cannot read the workspace: " + e.getMessage(), false);
        }
        // directories without a visible file below them are noise
        Set<String> parents = new HashSet<>();
        for (Entry e : entries) {
            if (e.type().equals("file")) {
                for (int i = e.path().lastIndexOf('/'); i > 0; i = e.path().lastIndexOf('/', i - 1)) {
                    parents.add(e.path().substring(0, i));
                }
            }
        }
        List<Entry> visible = new ArrayList<>(entries.stream()
                .filter(e -> e.type().equals("file") || parents.contains(e.path()))
                .toList());
        visible.sort(java.util.Comparator.comparing(Entry::path));
        return new Tree(visible, truncated[0]);
    }

    static Content read(Path root, String raw) {
        Path file = resolve(root, raw, false);
        long size;
        byte[] body;
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            size = Files.size(file);
            if (size > FILE_LIMIT) {
                throw new WorkspaceException("file exceeds the 1 MiB preview limit", false);
            }
            body = in.readNBytes(FILE_LIMIT + 1);
        } catch (NoSuchFileException e) {
            throw new WorkspaceException("no such path: " + raw, true);
        } catch (IOException e) {
            throw new WorkspaceException("cannot read " + relative(raw) + ": " + e.getMessage(), false);
        }
        if (body.length > FILE_LIMIT) {
            throw new WorkspaceException("file exceeds the 1 MiB preview limit", false);
        }
        String text = text(body);
        return new Content(relative(raw), text, size, sha256(text));
    }

    /** UTF-8 text without NUL bytes, or a refusal. */
    static String text(byte[] body) {
        for (byte b : body) {
            if (b == 0) {
                throw new WorkspaceException("binary files cannot be previewed", false);
            }
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new WorkspaceException("binary files cannot be previewed", false);
        }
    }

    static String sha256(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes text when the file still has the content the editor read ({@code expected} is its
     * sha256, or {@code missing} to create a new file), atomically.
     */
    static String write(Path root, String raw, String content, String expected) {
        if (content == null || content.length() > FILE_LIMIT || content.indexOf('\0') >= 0) {
            throw new WorkspaceException("只支持 1 MB 以内的文本文件", false);
        }
        String rel = relative(raw);
        if (rel.isEmpty()) {
            throw new WorkspaceException("path is required", false);
        }
        Path base;
        try {
            base = root.toRealPath();
        } catch (IOException e) {
            throw new WorkspaceException("workspace is not available", true);
        }
        Path target = base;
        String[] parts = rel.split("/");
        for (int i = 0; i < parts.length; i++) {
            target = target.resolve(parts[i]);
            if (Files.isSymbolicLink(target)) {
                throw new WorkspaceException("symbolic links are not accessible", false);
            }
            if (i < parts.length - 1
                    && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceException("workspace path is not a directory", false);
            }
        }
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        try {
            if (!exists) {
                if (!"missing".equals(expected)) {
                    throw new WorkspaceException("文件不存在；新建文件时 expectedSha256 传 missing", true);
                }
                Files.createDirectories(target.getParent());
            } else {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) > FILE_LIMIT) {
                    throw new WorkspaceException("不是可编辑文本文件", false);
                }
                String previous = text(Files.readAllBytes(target));
                if (expected == null || !expected.equals(sha256(previous))) {
                    throw new WorkspaceException("文件内容已变化，请重新读取后再保存", false);
                }
            }
            Path temp = Files.createTempFile(target.getParent(), ".lf-edit-", ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                if (exists) {
                    try {
                        Files.setPosixFilePermissions(
                                temp, Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
                    } catch (UnsupportedOperationException e) {
                        // not a POSIX file system
                    }
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new WorkspaceException("写入失败：" + e.getMessage(), false);
        }
        return sha256(content);
    }

    /** Case-insensitive line search over small text files (upstream limits). */
    static List<Hit> search(Path root, String query, boolean[] truncated) {
        Tree tree = tree(root, "");
        truncated[0] = tree.truncated();
        String needle = query.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        long scanned = 0;
        for (Entry entry : tree.entries()) {
            if (!entry.type().equals("file") || entry.size() > SEARCH_FILE) {
                continue;
            }
            if (scanned + entry.size() > SEARCH_BYTES) {
                truncated[0] = true;
                break;
            }
            scanned += entry.size();
            String content;
            try {
                content = read(root, entry.path()).content();
            } catch (WorkspaceException e) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                String preview = lines[i].strip();
                if (preview.codePointCount(0, preview.length()) > 240) {
                    preview = preview.substring(0, preview.offsetByCodePoints(0, 240)) + "…";
                }
                hits.add(new Hit(entry.path(), i + 1, preview));
                if (hits.size() >= SEARCH_RESULTS) {
                    truncated[0] = true;
                    return hits;
                }
            }
        }
        return hits;
    }
}
