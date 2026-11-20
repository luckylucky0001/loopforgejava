package io.github.loopforge4j.server.browser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * What an agent browser may load: web pages (never the cloud metadata services), the workspace's
 * own files, and browser-internal documents. It runs on this server with this server's network,
 * so it is only given to agents that already share it.
 */
public final class BrowserPolicy {

    private static final Set<String> METADATA_HOSTS =
            Set.of("metadata.google.internal", "metadata", "100.100.100.200", "fd00:ec2::254");

    private final Path root;

    /**
     * @param workspace the workspace whose files {@code file:} URLs may open; none when null
     */
    public BrowserPolicy(Path workspace) {
        Path r = null;
        if (workspace != null) {
            try {
                r = workspace.toRealPath();
            } catch (IOException e) {
                r = workspace.toAbsolutePath().normalize();
            }
        }
        this.root = r;
    }

    public boolean allowed(String url) {
        if (url == null || url.length() > 32 * 1024) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("about:") || lower.startsWith("data:") || lower.startsWith("blob:")) {
            return true;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return switch (scheme) {
            case "http", "https", "ws", "wss" -> !metadata(uri.getHost());
            case "file" -> inWorkspace(uri);
            default -> false;
        };
    }

    /** Cloud instance metadata: credentials of the machine itself. */
    static boolean metadata(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return METADATA_HOSTS.contains(h) || h.startsWith("169.254.") || h.startsWith("fe80:");
    }

    private boolean inWorkspace(URI uri) {
        if (root == null || uri.getAuthority() != null && !uri.getAuthority().isEmpty()) {
            return false;
        }
        Path path;
        try {
            path = Path.of(uri).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return false;
        }
        if (!path.startsWith(root)) {
            return false;
        }
        try {
            // never through a link out of the workspace
            return path.toRealPath().startsWith(root);
        } catch (IOException e) {
            // a missing file loads nothing
            return true;
        }
    }
}
