package io.github.loopforge4j.server.workspace;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

    /** Workspace IO runs on a few dedicated threads with a deadline: a FIFO can block an open forever. */
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors.newFixedThreadPool(
            4, Thread.ofPlatform().daemon().name("workspace-io-", 0).factory());

    static <T> T io(java.util.concurrent.Callable<T> work) {
        var future = IO.submit(work);
        try {
            return future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new WorkspaceException("工作区读取超时，请稍后再试", false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WorkspaceException("interrupted", false);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof WorkspaceException w) {
                throw w;
            }
            if (e.getCause() instanceof io.github.loopforge4j.core.runtime.SafeFiles.Refused r) {
                throw new WorkspaceException(r.getMessage(), r.missing());
            }
            throw new WorkspaceException("workspace error: " + e.getCause().getMessage(), false);
        }
    }

    private static List<String> split(String rel) {
        return rel.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(rel.split("/")));
    }

    /** Checks that a directory (e.g. a repository) is a real directory of the workspace, reached without links. */
    static void requireDirectory(Path root, String raw) {
        String rel = relative(raw);
        io(() -> {
            try (var base = io.github.loopforge4j.core.runtime.SafeFiles.openRoot(root)) {
                var dir = io.github.loopforge4j.core.runtime.SafeFiles.openDirectory(base, split(rel));
                io.github.loopforge4j.core.runtime.SafeFiles.closeQuietly(dir);
            }
            return null;
        });
    }

    static Tree tree(Path root, String raw) {
        String start = relative(raw);
        return io(() -> {
            List<Entry> entries = new ArrayList<>();
            boolean[] truncated = {false};
            try (var base = io.github.loopforge4j.core.runtime.SafeFiles.openRoot(root)) {
                var dir = start.isEmpty()
                        ? base
                        : io.github.loopforge4j.core.runtime.SafeFiles.openDirectory(base, split(start));
                try {
                    walk(dir, start, entries, truncated, 0);
                } finally {
                    if (dir != base) {
                        io.github.loopforge4j.core.runtime.SafeFiles.closeQuietly(dir);
                    }
                }
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
        });
    }

    private static void walk(
            java.nio.file.SecureDirectoryStream<Path> dir,
            String prefix,
            List<Entry> entries,
            boolean[] truncated,
            int depth)
            throws IOException {
        if (depth > 32) {
            return;
        }
        for (var child : io.github.loopforge4j.core.runtime.SafeFiles.list(dir)) {
            if (entries.size() >= TREE_LIMIT) {
                truncated[0] = true;
                return;
            }
            String rel = prefix.isEmpty() ? child.name() : prefix + "/" + child.name();
            var attrs = child.attributes();
            if (sensitive(rel) || attrs.isSymbolicLink()) {
                continue;
            }
            if (attrs.isDirectory()) {
                if (skippedDirectory(child.name())) {
                    continue;
                }
                entries.add(new Entry(
                        rel,
                        child.name(),
                        "directory",
                        0,
                        attrs.lastModifiedTime().toInstant()));
                java.nio.file.SecureDirectoryStream<Path> sub;
                try {
                    sub = dir.newDirectoryStream(Path.of(child.name()), LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue; // swapped meanwhile
                }
                try {
                    walk(sub, rel, entries, truncated, depth + 1);
                } finally {
                    io.github.loopforge4j.core.runtime.SafeFiles.closeQuietly(sub);
                }
            } else if (attrs.isRegularFile()) {
                entries.add(new Entry(
                        rel,
                        child.name(),
                        "file",
                        attrs.size(),
                        attrs.lastModifiedTime().toInstant()));
            }
            if (truncated[0]) {
                return;
            }
        }
    }

    static Content read(Path root, String raw) {
        String rel = relative(raw);
        if (rel.isEmpty()) {
            throw new WorkspaceException("path is required", false);
        }
        byte[] body = io(() -> {
            try {
                return io.github.loopforge4j.core.runtime.SafeFiles.read(root, rel, FILE_LIMIT);
            } catch (io.github.loopforge4j.core.runtime.SafeFiles.Refused e) {
                if (e.getMessage() != null && e.getMessage().contains("larger than")) {
                    throw new WorkspaceException("file exceeds the 1 MiB preview limit", false);
                }
                throw e;
            }
        });
        String text = text(body);
        return new Content(rel, text, body.length, sha256(text));
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
     * sha256, or {@code missing} to create a new file), atomically, never through a link.
     */
    static String write(Path root, String raw, String content, String expected) {
        if (content == null || content.length() > FILE_LIMIT || content.indexOf('\0') >= 0) {
            throw new WorkspaceException("只支持 1 MB 以内的文本文件", false);
        }
        String rel = relative(raw);
        if (rel.isEmpty()) {
            throw new WorkspaceException("path is required", false);
        }
        List<String> parts = split(rel);
        String name = parts.removeLast();
        return io(() -> {
            try (var base = io.github.loopforge4j.core.runtime.SafeFiles.openRoot(root)) {
                var dir = base;
                List<java.nio.file.SecureDirectoryStream<Path>> opened = new ArrayList<>();
                try {
                    for (String part : parts) {
                        var attrs = io.github.loopforge4j.core.runtime.SafeFiles.attributes(dir, part);
                        if (attrs == null) {
                            if (!"missing".equals(expected)) {
                                throw new WorkspaceException("文件不存在；新建文件时 expectedSha256 传 missing", true);
                            }
                            // created by name, then opened without links: a swapped parent fails the open
                            Path logical = root.toRealPath()
                                    .resolve(String.join("/", parts.subList(0, parts.indexOf(part) + 1)));
                            try {
                                Files.createDirectory(logical);
                            } catch (java.nio.file.FileAlreadyExistsException e) {
                                // created concurrently
                            }
                        } else if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
                            throw new WorkspaceException("workspace path is not a directory", false);
                        }
                        dir = dir.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS);
                        opened.add(dir);
                    }
                    var attrs = io.github.loopforge4j.core.runtime.SafeFiles.attributes(dir, name);
                    if (attrs == null) {
                        if (!"missing".equals(expected)) {
                            throw new WorkspaceException("文件不存在；新建文件时 expectedSha256 传 missing", true);
                        }
                    } else {
                        if (attrs.isSymbolicLink() || !attrs.isRegularFile() || attrs.size() > FILE_LIMIT) {
                            throw new WorkspaceException("不是可编辑文本文件", false);
                        }
                        byte[] previous;
                        try (var channel = dir.newByteChannel(
                                Path.of(name),
                                Set.of(java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                            previous = readAll(channel);
                        }
                        if (expected == null || !expected.equals(sha256(text(previous)))) {
                            throw new WorkspaceException("文件内容已变化，请重新读取后再保存", false);
                        }
                    }
                    String temp = ".lf-edit-" + java.util.UUID.randomUUID() + ".tmp";
                    try (var channel = dir.newByteChannel(
                            Path.of(temp),
                            Set.of(
                                    java.nio.file.StandardOpenOption.CREATE_NEW,
                                    java.nio.file.StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS))) {
                        var buffer = java.nio.ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                        while (buffer.hasRemaining()) {
                            channel.write(buffer);
                        }
                    }
                    try {
                        dir.move(Path.of(temp), dir, Path.of(name));
                    } catch (IOException e) {
                        try {
                            dir.deleteFile(Path.of(temp));
                        } catch (IOException ignored) {
                            // best effort
                        }
                        throw e;
                    }
                } finally {
                    opened.forEach(io.github.loopforge4j.core.runtime.SafeFiles::closeQuietly);
                }
            }
            return sha256(content);
        });
    }

    private static byte[] readAll(java.nio.channels.SeekableByteChannel channel) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        var buffer = java.nio.ByteBuffer.allocate(64 * 1024);
        while (channel.read(buffer) > 0) {
            buffer.flip();
            out.write(buffer.array(), 0, buffer.limit());
            buffer.clear();
            if (out.size() > FILE_LIMIT) {
                throw new WorkspaceException("不是可编辑文本文件", false);
            }
        }
        return out.toByteArray();
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
