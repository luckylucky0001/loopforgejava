package io.github.loopforge4j.server.assistant;

import io.github.loopforge4j.core.runtime.SafeFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git checkouts an agent left in a conversation workspace (upstream: two directory levels), read
 * from their {@code .git} files without running git and without following links: the remote
 * {@code origin} and the commits local branches point at.
 */
final class WorkspaceCheckouts {

    /** One checkout: its directory (relative), the origin URL and the tips of its local branches. */
    record Checkout(String dir, String origin, Set<String> tips) {}

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> SKIPPED = Set.of(".loopforge", "node_modules", ".cache", ".git");
    private static final long MAX_FILE = 256 * 1024;

    private WorkspaceCheckouts() {}

    static List<Checkout> find(Path root) {
        List<Checkout> out = new ArrayList<>();
        try (SecureDirectoryStream<Path> base = SafeFiles.openRoot(root)) {
            visit(root, base, "", 0, out);
        } catch (IOException e) {
            // an unreadable workspace has no checkouts to offer
        }
        return out;
    }

    private static void visit(Path root, SecureDirectoryStream<Path> dir, String prefix, int depth, List<Checkout> out)
            throws IOException {
        for (SafeFiles.Entry entry : SafeFiles.list(dir)) {
            if (!entry.attributes().isDirectory()
                    || entry.attributes().isSymbolicLink()
                    || SKIPPED.contains(entry.name())) {
                continue;
            }
            String relative = prefix + entry.name();
            try (SecureDirectoryStream<Path> child =
                    dir.newDirectoryStream(Path.of(entry.name()), LinkOption.NOFOLLOW_LINKS)) {
                var git = SafeFiles.attributes(child, ".git");
                if (git != null && git.isDirectory() && !git.isSymbolicLink()) {
                    read(root, relative).ifPresent(out::add);
                } else if (depth == 0) {
                    visit(root, child, relative + "/", depth + 1, out);
                }
            } catch (IOException e) {
                // a directory swapped or removed meanwhile: skip it
            }
        }
    }

    private static java.util.Optional<Checkout> read(Path root, String dir) {
        try {
            String config = new String(SafeFiles.read(root, dir + "/.git/config", MAX_FILE), StandardCharsets.UTF_8);
            String origin = origin(config);
            if (origin == null) {
                return java.util.Optional.empty();
            }
            Set<String> tips = new LinkedHashSet<>();
            try {
                String packed =
                        new String(SafeFiles.read(root, dir + "/.git/packed-refs", MAX_FILE), StandardCharsets.UTF_8);
                for (String line : packed.split("\n")) {
                    String[] parts = line.strip().split(" ");
                    if (parts.length == 2
                            && parts[1].startsWith("refs/heads/")
                            && SHA.matcher(parts[0]).matches()) {
                        tips.add(parts[0]);
                    }
                }
            } catch (IOException e) {
                // no packed refs
            }
            heads(root, dir + "/.git/refs/heads", 0, tips);
            return java.util.Optional.of(new Checkout(dir, origin, tips));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static void heads(Path root, String dir, int depth, Set<String> tips) {
        if (depth > 4 || tips.size() > 500) {
            return;
        }
        try (SecureDirectoryStream<Path> base = SafeFiles.openRoot(root);
                SecureDirectoryStream<Path> refs = SafeFiles.openDirectory(base, SafeFiles.parts(dir))) {
            for (SafeFiles.Entry entry : SafeFiles.list(refs)) {
                String path = dir + "/" + entry.name();
                if (entry.attributes().isSymbolicLink()) {
                    continue;
                }
                if (entry.attributes().isDirectory()) {
                    heads(root, path, depth + 1, tips);
                } else if (entry.attributes().isRegularFile()) {
                    String sha = new String(SafeFiles.read(root, path, 128), StandardCharsets.US_ASCII).strip();
                    if (SHA.matcher(sha).matches()) {
                        tips.add(sha);
                    }
                }
            }
        } catch (IOException e) {
            // no loose refs
        }
    }

    private static final Pattern REMOTE = Pattern.compile("^\\s*\\[remote \"origin\"]\\s*$");
    private static final Pattern SECTION = Pattern.compile("^\\s*\\[.*]\\s*$");
    private static final Pattern URL = Pattern.compile("^\\s*url\\s*=\\s*(\\S+)\\s*$");

    /** The {@code url} of {@code [remote "origin"]}. */
    static String origin(String config) {
        boolean inOrigin = false;
        for (String line : config.split("\n")) {
            if (REMOTE.matcher(line).matches()) {
                inOrigin = true;
            } else if (SECTION.matcher(line).matches()) {
                inOrigin = false;
            } else if (inOrigin) {
                Matcher m = URL.matcher(line);
                if (m.matches()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    private static final Pattern HTTPS =
            Pattern.compile("^https?://(?:[^@/]+@)?([^/:]+)(?::\\d+)?/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SCP = Pattern.compile("^(?:ssh://)?[^@]+@([^:/]+)[:/]([^/]+)/([^/]+?)(?:\\.git)?/?$");

    /** {@code host}, {@code owner/repo} of a GitHub-style remote, or null. */
    static String[] repository(String origin) {
        for (Pattern p : List.of(HTTPS, SCP)) {
            Matcher m = p.matcher(origin);
            if (m.matches()) {
                return new String[] {m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2) + "/" + m.group(3)};
            }
        }
        return null;
    }
}
