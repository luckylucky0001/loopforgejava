package io.github.loopforge4j.server.work;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Keeps webhooks away from the server's own network (SSRF): loopback, link-local (cloud metadata),
 * private, unique-local, any-local and multicast addresses are refused unless the deployment allows
 * private targets. Checked when a channel is saved and again before every delivery (DNS may change).
 */
final class WebhookTargets {

    private WebhookTargets() {}

    /** Problem with the target, or {@code null} when it may be called. */
    static String problem(String url, boolean allowPrivate) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return "url is not a valid URI";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            return "url must be an http(s) URL with a host";
        }
        if (uri.getUserInfo() != null) {
            return "url must not contain credentials";
        }
        if (allowPrivate) {
            return null;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (internal(address)) {
                    return "url resolves to an internal address (" + address.getHostAddress()
                            + "); set loopforge.notifications.allow-private-targets to allow it";
                }
            }
        } catch (UnknownHostException e) {
            return "url host does not resolve";
        }
        return null;
    }

    static boolean internal(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        // IPv6 unique local fc00::/7 and IPv4 carrier-grade NAT 100.64.0.0/10
        return raw.length == 16 && (raw[0] & 0xfe) == 0xfc
                || raw.length == 4 && (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 64;
    }
}
